package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.tools.Tool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pictures inside a research run (0.7.0, the second increment of pictures). Two tools on every worker's table when the run goes to the
 * web: {@code image_search}, the words the worker builds from what the run knows (an event's name, a person's name, a place) to the
 * engines of {@link Images#find}, each candidate said with its page, its engine and its size; and {@code image_judge}, the worker's
 * criteria and the candidates' addresses to the model that sees ({@link ModelEntries#seeing()}), one typed verdict each. A candidate the
 * seeing model says yes to is kept on the shelves ({@link Images#keep}) with the verdict in its head and becomes evidence of the run: the
 * worker notes it as it notes a page, by the picture's address, and the references number it like a page, saying it is a picture, where
 * it was kept, the page it stands on and the verdict. The writer is handed the one line that shows it, {@code ![title](/images/<file>)},
 * and may put it under the paragraph that cites it, at most {@link #SHOW_AT_MOST} in a report ({@link #shown}).
 *
 * <p>The chain, in two parts: right after the plan, before any worker draws a turn, the question's own subjects ({@link #first}: the names
 * it carries, or the event it says by a year and an event word), each searched once by its name with the question's subject beside it, the
 * candidates judged in the ask's own connection, the yeses kept as evidence, one held turn each; and after the workers the full names the
 * findings raise ({@link #later}), on the turns and the time left. The log and the trace say what was searched and why, and with the
 * numbers when the second part cannot run. "Uses that to find other images of said person" by likeness is the
 * next increment: a kept picture judged to show a person is its seed ({@link #seeds}); {@link Images.Likeness} is the seam.
 *
 * <p>Bounds: {@link #SEARCHES_PER_SUB} searches a sub-question, {@link #JUDGE_AT_MOST} candidates a judge call, the picture tools' turns
 * the worker's own; a hosted seeing entry's tokens go on the run's tally ({@link #tally}) as the translator's do. When no model sees,
 * {@code image_judge} answers with {@link ModelEntries#noEyes()} and the run goes on without pictures; {@code RESEARCHZOSHO_PICTURES=off}
 * leaves the tools and the chain out of every run.
 */
final class RunPictures {
    private static final ObjectMapper J = new ObjectMapper();

    /** The setting that leaves pictures out of research runs. */
    static final String SETTING = "RESEARCHZOSHO_PICTURES";
    static boolean on() { return !"off".equalsIgnoreCase(Config.get(SETTING, "on")); }

    /** The most searches a worker makes on one sub-question, candidates a judge call takes, candidates a search gives. */
    static final int SEARCHES_PER_SUB = 3, JUDGE_AT_MOST = 8, FIND_COUNT = 8;
    /** The chain's bounds: entities looked for, candidates a search gives, pictures kept of one entity. */
    static final int CHAIN_ENTITIES = 3, CHAIN_COUNT = 6, CHAIN_KEEP = 2;
    /** The most pictures a report shows; the ones the writer asked for come first when more are placed. */
    static final int SHOW_AT_MOST = 5;
    /** The writer's asks for pictures ({@link #FOR}): at most this many a report, one a section, this many candidates each. */
    static final int FOR_AT_MOST = 5, FOR_COUNT = 6;

    static final String SEARCH = "image_search", JUDGE = "image_judge", FOR = "picture_for";
    /** The kinds of picture the writer may name; any other word is "any". */
    static final Set<String> KINDS = Set.of("diagram", "map", "photo", "chart", "any");

    /** A picture kept this run: the kept entry, the verdict it was kept by, which sub-question (or the chain's entity) asked for it, and why. */
    record Picture(Images.Kept kept, Images.Judged judged, String sub, String why, String section) {
        Picture(Images.Kept kept, Images.Judged judged, String sub, String why) { this(kept, judged, sub, why, ""); }
        /** The address a note names as the source: the picture's own. */
        String locator() { return kept.found().image(); }
        /** The Markdown line that shows it. */
        String line() { return "![" + title().replace("]", ")").replace("\n", " ") + "](" + kept.url() + ")"; }
        String title() { return kept.found().title() == null || kept.found().title().isBlank() ? kept.file().substring(kept.file().lastIndexOf('/') + 1) : kept.found().title().strip(); }
        /** The note a worker would have written: the claim, the picture as the source, the verdict's sentence as the quote. */
        String note() {
            return "- A picture judged to show " + judged.criteria().strip().replaceAll("[.?!]+$", "") + ": \"" + title() + "\"" + (kept.found().host().isEmpty() ? "" : ", from " + kept.found().host())
                    + " — source: " + locator() + (judged.why().isBlank() ? "" : " — quote: \"" + judged.why().replace("\"", "'") + "\"");
        }
    }

    private final LibraryStore store;
    private final Consumer<String> log;
    private final BiConsumer<String, ObjectNode> event;
    /** The model that sees pictures this run, chosen once; null when none does. */
    private final ModelEntries.Entry eyes;
    final List<Picture> kept = new CopyOnWriteArrayList<>();
    /** The served addresses of the pictures asked for a section, the library's ({@link #sections}) and the writer's ({@link #FOR}), so the report shows them before the others ({@link #shown}). */
    final Set<String> forSections = ConcurrentHashMap.newKeySet();
    /** The words searched this run, lower-cased, so the chain does not repeat a worker's search. */
    private final Set<String> searched = ConcurrentHashMap.newKeySet();
    private final AtomicInteger searches = new AtomicInteger(), judgedCount = new AtomicInteger();
    /** The seeing entry's tokens this run: prompt, cached, completion, in the tally's order. */
    private final long[] usage = new long[3];

    RunPictures(LibraryStore store, Consumer<String> log, BiConsumer<String, ObjectNode> event) {
        this.store = store; this.log = log == null ? s -> { } : log; this.event = event == null ? (t, o) -> { } : event;
        ModelEntries.Entry e = null;
        if (store != null && on()) { try { e = ModelEntries.seeing(); } catch (RuntimeException x) { e = null; } }
        this.eyes = e;
    }

    /** Whether this run's workers get the picture tools: the setting on, a library to keep pictures in, and the web open. */
    boolean offered(Researcher.Ask ask) { return on() && store != null && ask.web(); }

    /**
     * Whether the chain runs for this ask: it is about a person, an organisation, a place or an event — the question names one, or
     * says an event by a year and an event word ({@link #eventPhrase}), or the run's fields are a dossier's.
     */
    boolean chainFor(Researcher.Ask ask, List<String> fieldNames) {
        if (!offered(ask)) return false;
        boolean dossier = fieldNames != null && (fieldNames.contains("person") || fieldNames.contains("organization"));
        return dossier || !CrossLinks.names(ask.question(), "").isEmpty() || !eventPhrase(ask.question()).isEmpty();
    }

    /**
     * How many turns the picture step needs kept back before the workers draw: one an entity the question names (or one for the subject
     * the findings will name, on a dossier's run), at most {@link #CHAIN_ENTITIES}, and never more than a sixth of what the workers would
     * have; 0 when the chain does not run for this ask, when no model sees, or when the budget has no ceiling.
     */
    int turnsToReserve(Researcher.Ask ask, Researcher.Budget budget, List<Profile> fields) {
        List<String> names = fields == null ? List.of() : fields.stream().map(Profile::name).toList();
        if (eyes == null || budget.unbounded() || !chainFor(ask, names)) return 0;
        int entities = Math.max(1, Math.min(CHAIN_ENTITIES, entities(ask.question(), "").size()));
        int share = Math.max(1, (budget.left() - budget.reserve()) / 6);
        return Math.min(entities, share);
    }

    /** The log's line when turns were kept back: how many, and for what — the question's subjects, the sections' pictures, or both. */
    String reservedLine(Researcher.Ask ask, int forEntities, int forSections) {
        int hold = forEntities + forSections;
        List<String> names = entities(ask.question(), "");
        String of = forEntities == 0 ? "" : "pictures of " + (names.isEmpty() ? "what the findings name" : String.join(", ", names.subList(0, Math.min(names.size(), forEntities))));
        String sections = forSections == 0 ? "" : "the sections on " + forSections + " sub-question" + (forSections == 1 ? "" : "s");
        return "pictures: " + hold + (hold == 1 ? " turn" : " turns") + " kept back from the workers for " + of + (of.isEmpty() || sections.isEmpty() ? "" : " and for ") + sections + ", so the step after them has its turn" + (hold == 1 ? "" : "s");
    }

    ModelEntries.Entry eyes() { return eyes; }
    int searches() { return searches.get(); }
    int judged() { return judgedCount.get(); }

    /** The worker's paragraph about the two tools. */
    static String register() {
        return "PICTURES: image_search finds pictures on the web for words you build from what the run knows — an event's name, a person's name, a place — each with the page it stands on. "
                + "image_judge has the model that sees pictures say whether each shows what you name (yes, no or unsure, with one sentence why); a picture it says yes to is kept on the shelves and is evidence: "
                + "note it as you note a page — claim: what it shows, source: the picture's address as image_judge printed it, quote: the verdict's sentence. "
                + "At most " + SEARCHES_PER_SUB + " searches on your sub-question and " + JUDGE_AT_MOST + " pictures a judge call; a picture is worth the turn when the question is about a person, a place or an event. "
                + "image_judge takes an optional reference, a kept picture's entry id or /images/ address that shows the person: the candidates are then judged beside it, for the same one.\n\n";
    }

    /** The two tools for one worker, sharing what its searches found so a judge can name a candidate by its address. */
    List<Tool> toolsFor(String sub) {
        Map<String, Images.Found> found = new LinkedHashMap<>();
        AtomicInteger here = new AtomicInteger();
        return List.of(new SearchTool(sub, found, here), new JudgeTool(sub, found));
    }

    /** image_search: the worker's words to the engines; the candidates numbered with their pages and addresses. */
    private final class SearchTool implements Tool {
        private final String sub; private final Map<String, Images.Found> found; private final AtomicInteger here;
        SearchTool(String sub, Map<String, Images.Found> found, AtomicInteger here) { this.sub = sub; this.found = found; this.here = here; }
        @Override public String name() { return SEARCH; }
        @Override public String description() {
            return "Find pictures on the web for words: an event's name, a person's name, a place, a thing. Each candidate comes with the page it stands on, its size and the engine that found it; "
                    + "the pictures the library already keeps come first. Nothing is kept by a search. At most " + SEARCHES_PER_SUB + " calls on this sub-question.";
        }
        @Override public ObjectNode parametersSchema(ObjectMapper j) {
            ObjectNode p = j.createObjectNode(); p.put("type", "object");
            ObjectNode props = p.putObject("properties");
            props.putObject("query").put("type", "string").put("description", "the words, as a person would type them into an image search");
            props.putObject("count").put("type", "integer").put("description", "how many candidates, at most " + FIND_COUNT);
            p.putArray("required").add("query");
            return p;
        }
        @Override public String execute(JsonNode args) {
            String words = args.path("query").asText("").strip();
            if (words.isEmpty()) return "ERROR: query is needed: the words to find pictures for.";
            if (here.get() >= SEARCHES_PER_SUB) return "ERROR: image_search has been called " + SEARCHES_PER_SUB + " times on this sub-question, its most. Judge what it found with image_judge, or go on without pictures.";
            here.incrementAndGet(); searches.incrementAndGet(); searched.add(words.toLowerCase(Locale.ROOT));
            int count = Math.min(Math.max(args.path("count").asInt(FIND_COUNT), 1), FIND_COUNT);
            Images.Result r = Images.find(store, words, count);
            StringBuilder b = new StringBuilder(r.said(words));
            if (r.found().isEmpty()) return b.append(r.answered().isEmpty() ? " No picture search answered; go on without pictures." : " Try other words.").toString();
            int i = 0;
            for (Images.Found f : r.found()) {
                i++; found.put(f.image(), f);
                b.append('\n').append(i).append(". ").append(f.said()).append("\n   page: ").append(f.page() == null || f.page().isBlank() ? "(none given)" : f.page()).append("\n   picture: ").append(f.image());
                if (Images.SHELVES.equals(f.source())) { String web = Images.locatorOf(store, f.image()); if (web != null) b.append(" (on the shelves already; its address on the web, to note it by: ").append(web).append(')'); }
            }
            b.append("\n\nTo use any as evidence, call image_judge with criteria (what the picture should show, in words) and images (the picture addresses above, at most ").append(JUDGE_AT_MOST)
             .append("). A picture judged yes is kept on the shelves and becomes a source you note.");
            ObjectNode o = J.createObjectNode(); o.put("sub", sub); o.put("words", words); o.put("found", r.found().size()); o.put("engines", String.join(", ", r.answered()));
            event.accept("picture_search", o);
            return b.toString();
        }
    }

    /** image_judge: the criteria and the candidates to the seeing model; a yes kept and said as a source. */
    private final class JudgeTool implements Tool {
        private final String sub; private final Map<String, Images.Found> found;
        JudgeTool(String sub, Map<String, Images.Found> found) { this.sub = sub; this.found = found; }
        @Override public String name() { return JUDGE; }
        @Override public String description() {
            return "Have the model that sees pictures judge candidates from image_search against your criteria: does each show what you name? Yes, no or unsure, with one sentence why. "
                    + "A picture judged yes is kept on the shelves with its page and its verdict and becomes a source of this research: note it by the address printed. At most " + JUDGE_AT_MOST + " pictures a call.";
        }
        @Override public ObjectNode parametersSchema(ObjectMapper j) {
            ObjectNode p = j.createObjectNode(); p.put("type", "object");
            ObjectNode props = p.putObject("properties");
            props.putObject("criteria").put("type", "string").put("description", "what the picture should show, in words: a person by name, an event, a place, a thing");
            ObjectNode imgs = props.putObject("images"); imgs.put("type", "array").put("description", "the picture addresses image_search listed, at most " + JUDGE_AT_MOST); imgs.putObject("items").put("type", "string");
            props.putObject("reference").put("type", "string").put("description", "optional: a kept picture (its entry id or /images/ address) that shows the person or thing; each candidate is then judged beside it, for the same one");
            p.putArray("required").add("criteria").add("images");
            return p;
        }
        @Override public String execute(JsonNode args) {
            String criteria = args.path("criteria").asText("").strip();
            if (criteria.isEmpty()) return "ERROR: criteria is needed: what the pictures should show, in words.";
            List<Images.Found> pick = new ArrayList<>();
            JsonNode imgs = args.path("images");
            if (imgs.isTextual()) { for (String s : imgs.asText().split("[\\s,]+")) if (!s.isBlank()) pick.add(candidate(s)); }
            else for (JsonNode n : imgs) { String s = n.isTextual() ? n.asText() : n.path("image").asText(""); if (!s.isBlank()) pick.add(candidate(s)); }
            pick.removeIf(f -> f == null);
            if (pick.isEmpty()) return "ERROR: images is needed: the picture addresses image_search listed.";
            if (pick.size() > JUDGE_AT_MOST) pick = new ArrayList<>(pick.subList(0, JUDGE_AT_MOST));
            if (eyes == null) return ModelEntries.noEyes() + " This run goes on without pictures: note what the pages say instead.";
            // a reference the worker names, else the portrait this run confirmed for the person the criteria name: the candidates are judged beside it
            Pass pass = new Pass(entityOf(criteria));
            String named = args.path("reference").asText("").strip();
            if (!named.isEmpty()) { try { pass.reference(referenceFor(named)); } catch (IOException e) { return "ERROR: the reference " + named + " is not a kept picture: " + e.getMessage(); } }
            else { Picture known = personReference.get(pass.entity().toLowerCase(Locale.ROOT)); if (known != null) { try { pass.reference(referenceFor(known)); } catch (IOException e) { /* the portrait's file is gone: the candidates are judged by the words */ } } }
            int[] yes = {0}, judgedHere = {0}, same = {0}; StringBuilder b = new StringBuilder(); int[] i = {0};
            judgeAll(criteria, pick, pass, l -> {
                i[0]++;
                if (l.skipped() != null) { b.append('\n').append(i[0]).append(". ").append(l.title()).append(": skipped, ").append(l.skipped()).append('.'); return; }
                if (l.sameAs() != null) { same[0]++; b.append('\n').append(i[0]).append(". ").append(l.title()).append(": the same picture as ").append(l.sameAs()).append('.'); return; }
                Images.Judged j = l.judged();
                judgedHere[0]++;
                b.append('\n').append(i[0]).append(". ").append(j.said());
                if (!j.yes()) return;
                yes[0]++;
                Picture p = keep(l, sub, "the worker asked for pictures of " + criteria);
                if (p == null) b.append(" Not kept: ").append(lastKeepProblem.get());
                else { b.append(" KEPT as ").append(p.kept().id()).append("; note it with source: ").append(p.locator()).append(j.found().page() == null || j.found().page().isBlank() ? "" : " (its page: " + j.found().page() + ")").append('.'); seed(pass, p); }
            });
            String head = "Judged against \"" + criteria + "\" by " + eyes.name() + " (" + eyes.model() + ")" + pass.said() + ": " + yes[0] + " of " + judgedHere[0] + (yes[0] == 1 ? " shows" : " show") + " it."
                    + (same[0] == 0 ? "" : " " + same[0] + (same[0] == 1 ? " candidate was" : " candidates were") + " the same picture as one already judged.");
            String tail = yes[0] == 0 ? "" : "\n\nNote each kept picture now: note(claim: what it shows, source: the picture address printed above, quote: the verdict's sentence).";
            return head + b + tail;
        }
        private Images.Found candidate(String address) {
            String a = address.strip();
            Images.Found f = found.get(a);
            if (f != null) return f;
            if (a.startsWith("/images/")) { Images.Shelved s = Images.entryFor(store, a); return s == null ? null : s.asFound(); }
            return a.matches("(?i)^https?://.+") ? new Images.Found(a, "", "", "the worker", 0, 0, "") : null;
        }
    }

    private final ThreadLocal<String> lastKeepProblem = ThreadLocal.withInitial(() -> "");

    /**
     * One candidate as a judge pass saw it: its bytes when they came, the verdict when it was judged, or {@code sameAs} — a few words naming
     * the picture it is the same as (the same address a query string apart, the same bytes under another address, one kept this run), so
     * it is said and not judged again.
     */
    record Looked(Images.Found found, Images.Fetched got, Images.Judged judged, String sameAs, String skipped, String namedBy, String beside, LikenessHelper.Measure measure) {
        Looked(Images.Found found, Images.Fetched got, Images.Judged judged, String sameAs) { this(found, got, judged, sameAs, null, Images.BY_NONE, "", null); }
        Looked(Images.Found found, Images.Fetched got, Images.Judged judged, String sameAs, String skipped) { this(found, got, judged, sameAs, skipped, Images.BY_NONE, "", null); }
        Looked(Images.Found found, Images.Fetched got, Images.Judged judged, String sameAs, String skipped, String namedBy, String beside) { this(found, got, judged, sameAs, skipped, namedBy, beside, null); }
        String title() { return found.title() == null || found.title().isBlank() ? found.image() : "\"" + found.title() + "\""; }
    }

    /**
     * One judge pass's state: the entity the criteria name, and the reference the candidates are judged beside once there is one — a picture
     * the worker named, the portrait this run confirmed earlier for the person, or the first yes of this very pass when the entity is a person
     * ("that person's image is found and it uses that to find other images of said person"). {@code against} counts the candidates judged beside it.
     */
    final class Pass {
        private final String entity;
        private Images.Reference reference; private String referenceId = "";
        int against = 0;
        /** Set by a caller that wants one picture: {@link #judgeAll} judges no further candidate once it is. */
        volatile boolean enough = false;
        Pass(String entity) { this.entity = entity == null ? "" : entity; }
        String entity() { return entity; }
        Images.Reference reference() { return reference; }
        void reference(Images.Reference r) { reference = r; referenceId = r == null ? "" : r.id(); }
        /** " (N of them beside the picture kept as <id>)" for the log and the tool's answer; "" when none was. */
        String said() { return against == 0 ? "" : " (" + against + " of them beside the picture kept as " + referenceId + (Vision.seesTwo(eyes) ? "" : ", by its description, since the model takes one picture at a time") + ")"; }
    }

    /** The entity a criteria names: the words before ", in connection with" and before a bracket. */
    static String entityOf(String criteria) { return criteria == null ? "" : criteria.replaceFirst(",\\s*in connection with .*$", "").replaceFirst("\\s*\\(.*$", "").strip(); }

    /**
     * Whether a name is a person's: two or three capitalised words, none an organisation's word or an event's, no digit. The deterministic
     * reading; the judge's own words decide whether a kept picture of the person is a likeness ({@link #seed}).
     */
    static boolean isPersonName(String name) {
        if (name == null) return false;
        String[] w = name.strip().split("\\s+");
        if (w.length < 2 || w.length > 3) return false;
        for (String x : w) { if (!x.matches("^[A-Z][\\p{L}'’.-]+$") && !x.matches("^(?:de|van|von|da|di|del|la|le|bin|ibn|al)$")) return false; if (CrossLinks.ORG_WORDS.contains(x) || EVENT_WORDS.matcher(x).matches() || DEMONYMS.contains(x)) return false; }
        return !name.matches(".*\\d.*");
    }

    /** What the judge's sentence must say for a kept yes to be the person's likeness: a face or likeness compared, or a label, caption or title naming them. */
    static final Pattern LIKENESS_WHY = Pattern.compile("(?i)\\b(face|faces|facial|likeness|portrait|resembl\\w*|features|moustache|beard|label|labelled|labeled|caption|captioned|titled|title|named|names|name)\\b");

    /** A kept yes for a person whose verdict rested on a likeness or a label becomes the reference for the rest of the pass and for the run's later searches for that person. */
    void seed(Pass pass, Picture p) {
        if (pass.reference() != null || !isPersonName(pass.entity()) || !p.judged().yes() || !LIKENESS_WHY.matcher(p.judged().why()).find()) return;
        try { pass.reference(referenceFor(p)); personReference.putIfAbsent(pass.entity().toLowerCase(Locale.ROOT), p); } catch (IOException e) { /* the picture stays evidence; nothing is judged beside it */ }
    }

    /** The reference a kept picture of this run is: its bytes from the library's copy, no description (the judge sees it), its entry. */
    Images.Reference referenceFor(Picture p) throws IOException {
        Images.Fetched got = Images.bytesOf(store, p.kept().url(), false);
        return new Images.Reference(got.bytes(), got.contentType(), "", "", p.kept());
    }

    /** The reference a worker names: a kept picture's entry id or /images/ address. */
    Images.Reference referenceFor(String named) throws IOException {
        Images.Fetched got = Images.bytesOf(store, named, false);
        Images.Shelved s = Images.byHash(store, Images.sha256(got.bytes()));
        return new Images.Reference(got.bytes(), got.contentType(), "", "", s == null ? null : new Images.Kept(s.raw(), s.id(), s.file(), s.url(), s.cited(null)));
    }

    /** The kept portraits of this run, by the person's name in lower case: the references for the run's later searches for them. */
    private final Map<String, Picture> personReference = new ConcurrentHashMap<>();

    /**
     * Every candidate to the seeing model, one call each, the tokens of each call added to the run's tally — each PICTURE once: the same
     * address a query string apart, the same bytes under another address (a coin came through two engines and was judged and kept twice,
     * 2026-11-08) and a picture kept this run are said as the same and not judged again. Asked as a run asks ({@link Images#SHOWS_RULE});
     * beside the pass's reference, when it has one, for the same person or thing ({@link Images#judgeAgainst}, or by the reference's
     * description for a model that takes one picture at a time). {@code onJudged} sees each candidate as it is judged, so a caller can keep a
     * yes before the next candidate and make it the reference.
     */
    List<Looked> judgeAll(String criteria, List<Images.Found> pick, Pass pass, Consumer<Looked> onJudged) {
        List<Looked> out = new ArrayList<>();
        Map<String, Looked> bySha = new HashMap<>(); Set<String> addresses = new HashSet<>();
        // with a reference and the likeness helper up, every candidate is fetched and measured first, and the model sees the best-measured first (0.7.0)
        Map<Images.Found, Images.Fetched> fetched = new HashMap<>(); Map<Images.Found, LikenessHelper.Measure> measures = new HashMap<>();
        List<Images.Found> order = pick;
        if (pass != null && pass.reference() != null && LikenessHelper.scorer() != null) {
            List<Images.Scored> scored = new ArrayList<>();
            for (Images.Found f : pick) {
                Images.Fetched got = null;
                try { got = Images.fetch(store, f); } catch (Stopping.Requested stop) { throw stop; } catch (Exception e) { /* fetched again below, where the problem is said */ }
                if (got != null) fetched.put(f, got);
                LikenessHelper.Measure m = got != null && Images.looksAtable(got) ? Images.measure(pass.reference(), got.bytes(), got.contentType()) : null;
                if (m != null) measures.put(f, m);
                scored.add(new Images.Scored(f, got, m));
            }
            order = new ArrayList<>(); for (Images.Scored s : Images.inMeasuredOrder(scored)) order.add(s.found());
        }
        for (Images.Found f : order) {
            if (pass != null && pass.enough) break;   // the caller has the one picture it wanted: the rest are not judged
            Looked l;
            if (!addresses.add(Images.imageKey(f.image()))) l = new Looked(f, null, null, "one judged already in this call, the same address a query string apart");
            else {
                Images.Fetched got = fetched.get(f); String problem = null;
                try { if (got == null) got = Images.fetch(store, f); }
                catch (Stopping.Requested stop) { throw stop; }
                catch (Exception e) { problem = e.getMessage() == null ? "the picture could not be fetched" : e.getMessage().startsWith("the picture") ? e.getMessage() : "the picture could not be fetched: " + e.getMessage(); }
                if (problem != null) l = new Looked(f, null, new Images.Judged(f, Images.Verdict.UNJUDGED, problem, eyes.model(), criteria), null);
                else if (!Images.looksAtable(got)) l = new Looked(f, got, null, null, Images.notLookable(got));   // an SVG, a PDF: nothing goes to the model
                else if (pass.reference() != null && isPersonName(pass.entity()) && f.image().startsWith("/images/") && keptAsAThing(f, pass.entity()) != null)   // a kept picture of a thing or a place is no candidate for a person (two calls answering "a person vs an artefact", J-0009)
                    l = new Looked(f, got, null, null, "kept as a picture of " + keptAsAThing(f, pass.entity()) + ", not a person; not judged for " + pass.entity());
                else {
                    String sha = Images.sha256(got.bytes());
                    Picture held = keptThisRun(sha);
                    Looked earlier = bySha.get(sha);
                    if (held != null) l = new Looked(f, got, null, "\"" + held.title() + "\", kept as " + held.kept().id());
                    else if (earlier != null) l = new Looked(f, got, null, earlier.title() + ", judged already in this call");
                    else if (pass.reference() != null && Images.sha256(pass.reference().bytes()).equals(sha)) l = new Looked(f, got, null, "the reference itself, kept as " + pass.reference().id());
                    else {
                        DriveClient.forgetUsage();
                        Images.Judged j; String by = Images.BY_NONE, beside = "";
                        if (pass.reference() != null) {
                            boolean person = isPersonName(pass.entity());
                            String what = person ? "person (" + pass.entity() + ")" : pass.entity().isEmpty() ? "thing" : pass.entity();
                            String name = person ? pass.entity() : null;   // the machine-read naming (a title, an address) outranks the model's face reading
                            j = Vision.seesTwo(eyes) ? Images.judgeAgainst(eyes, pass.reference(), Images.SAME, what, name, f, got.bytes(), got.contentType()) : Images.judgeByWords(eyes, pass.reference(), Images.SAME, what, name, f, got.bytes(), got.contentType());
                            pass.against++; by = Images.namedBy(f, name); beside = pass.reference().id();
                            j = Images.measured(j, measures.get(f), Images.SAME, person, name);   // the helper's number first: a face score outranks the model's reading either way, unless the picture's own words name the person
                        } else j = Images.judge(eyes, criteria, f, got.bytes(), got.contentType(), true);
                        long[] u = DriveClient.lastUsage();
                        if (u != null) synchronized (usage) { usage[0] += u[0]; usage[2] += u.length > 1 ? u[1] : 0; }
                        judgedCount.incrementAndGet();
                        l = new Looked(f, got, j, null, null, by, beside, measures.get(f));
                        bySha.put(sha, l);
                        // every judged candidate on the trace — a plain no is the most common outcome, and the only way to read a run afterwards (J-0008: why a photograph was not kept could not be told); a yes that is kept writes its own line with kept
                        if (!j.yes()) event.accept("picture", traced(l, pass.entity(), criteria, "", ""));
                    }
                }
            }
            if (l.skipped() != null) { ObjectNode o = traced(l, pass.entity(), criteria, "", ""); o.put("skipped", l.skipped()); event.accept("picture", o); }   // a skip is on the trace too, with its reason (a kept thing in a person pass, an SVG)
            out.add(l);
            if (onJudged != null) onJudged.accept(l);
        }
        return out;
    }

    /**
     * What a shelf candidate's own entry says it shows, when that is a thing or a place and not the person: the entity of the criteria it was
     * judged yes against ("Antikythera" from "Antikythera, in connection with Valerios Stais"), when that entity is no person's name and not
     * the person asked for; null when the entry says nothing of the kind, or says a person.
     */
    String keptAsAThing(Images.Found f, String person) {
        Images.Shelved s = Images.entryFor(store, f.image());
        if (s == null || s.verdict() != Images.Verdict.YES || s.criteria() == null || s.criteria().isBlank()) return null;
        String shows = entityOf(s.criteria()).replaceFirst("^the same ", "");
        if (shows.isEmpty() || isPersonName(shows) || shows.equalsIgnoreCase(person) || shows.toLowerCase(Locale.ROOT).contains(person.toLowerCase(Locale.ROOT))) return null;
        return shows;
    }

    /** The picture kept this run whose bytes have this hash; null for none. A kept file is named by the hash's first sixteen hex digits. */
    Picture keptThisRun(String sha) {
        String name = "/" + sha.substring(0, 16) + ".";
        for (Picture p : kept) if (p.kept().file().contains(name)) return p;
        return null;
    }

    /** One judged candidate's trace line: the sub-question or entity, the criteria, the picture, its verdict and why, where its text names the person, the reference it was judged beside, and kept or not. */
    static ObjectNode traced(Looked l, String sub, String criteria, String kept, String notKept) {
        Images.Judged j = l.judged();
        ObjectNode o = J.createObjectNode();
        o.put("sub", sub); o.put("criteria", j == null ? criteria : j.criteria()); o.put("image", l.found().image()); o.put("page", l.found().page()); o.put("title", l.found().title()); o.put("source", l.found().source());
        if (j != null) { o.put("verdict", j.verdict().word()); o.put("why", j.why()); o.put("model", j.model()); }
        o.put("named_by", l.namedBy() == null ? Images.BY_NONE : l.namedBy()); o.put("beside", l.beside() == null || l.beside().isBlank() ? "none" : l.beside());
        if (l.measure() != null) o.put("likeness", l.measure().said());
        o.put("kept", kept == null ? "" : kept); if (notKept != null && !notKept.isBlank()) o.put("not_kept", notKept);
        return o;
    }

    /** A yes kept on the shelves on the bytes it was judged by, with its verdict, filed as the run's evidence and written to the trace; null when it could not be kept (the reason in {@link #lastKeepProblem}). */
    Picture keep(Images.Judged j, Images.Fetched got, String sub, String why) { return keep(new Looked(j.found(), got, j, null), sub, why); }

    /** The same, from the candidate as the pass saw it, so the trace line carries where its text names the person and what it was judged beside. */
    Picture keep(Looked l, String sub, String why) { return keep(l, sub, why, ""); }

    /** The same, for a picture asked for a section of the report: {@code section} is the sub-question or the heading it belongs to. */
    Picture keep(Looked l, String sub, String why, String section) {
        Images.Judged j = l.judged(); Images.Fetched got = l.got();
        ObjectNode o = traced(l, sub, j.criteria(), "", "");
        o.put("why_searched", why); o.put("verdict_why", j.why()); if (section != null && !section.isEmpty()) o.put("section", section);
        try {
            Images.Kept k = Images.keep(store, j.found(), got, j.why(), "", j);
            Picture p = new Picture(k, j, sub, why, section == null ? "" : section);
            kept.add(p);
            o.put("kept", k.id()); o.put("file", k.file()); o.put("url", k.url());
            event.accept("picture", o);
            return p;
        } catch (IOException e) {
            lastKeepProblem.set(e.getMessage());
            o.put("kept", ""); o.put("not_kept", e.getMessage());
            event.accept("picture", o);
            return null;
        }
    }

    // ---- the writer's ask ----

    /** The writer's paragraph about {@link #FOR}: where a picture earns its place, and what to do with the answer. */
    static String writerRegister() {
        return " PICTURES FOR THE REPORT: picture_for brings a picture a section needs — the thing the section describes: a mechanism's diagram, a map of the place, the object itself, the person, a chart that exists. "
                + "Ask for one where the reader needs to see the thing, one a section and at most " + FOR_AT_MOST + " in the report, before you write that section; put the line it answers on a line of its own under the paragraph it belongs to, "
                + "and cite it by the number it gives. A section of argument stands on its words.";
    }

    static final String LIBRARY = "the library", WRITER = "the writer";

    /** What one ask came to: the words searched and the criteria judged, the counts, the picture kept (null for none), and the sentence that says it. */
    record Asked(String words, String criteria, int found, int judged, Picture picture, String said) { }

    /**
     * One ask for a picture a section needs, the library's ({@link #sections}) or the writer's ({@link PictureForTool}): the question's
     * subject joins the words unless they carry it, a kind the words do not say joins the search and not the criteria; the candidates are
     * judged in the ask's connection under the run's own rule ({@link Images#SHOWS_RULE}: a diagram must show this mechanism's gearing, not
     * any gears), the first yes is kept with the section it belongs to and no further candidate goes to the model; the trace carries the ask
     * ({@code picture_for}, with who asked).
     */
    Asked askFor(String by, String section, String what, String kind, String subject) {
        String subj = subject == null ? "" : subject.strip();
        boolean carries = subj.isEmpty() || what.toLowerCase(Locale.ROOT).contains(subj.toLowerCase(Locale.ROOT));
        String words = (carries ? what : what + " " + subj) + (kind.equals("any") || kind.equals("photo") || what.toLowerCase(Locale.ROOT).contains(kind) ? "" : " " + kind);
        String criteria = carries ? what : what + ", in connection with " + subj;
        Images.Result r = Images.find(store, words, FOR_COUNT);
        searches.incrementAndGet(); searched.add(words.toLowerCase(Locale.ROOT));
        Pass pass = new Pass(entityOf(criteria));
        Picture[] got = {null}; int[] judgedHere = {0};
        String named = WRITER.equals(by) ? "'" + section + "'" : "on \"" + section + "\"";
        String why = by + " asked for " + what + " for the section " + named;
        judgeAll(criteria, r.found(), pass, l -> {
            if (l.judged() == null) return;
            judgedHere[0]++;
            if (!l.judged().yes()) return;
            Picture p = keep(l, "a picture for the section " + named, why, section);
            if (p != null) { got[0] = p; pass.enough = true; forSections.add(p.kept().url()); }
        });
        String said = r.found().isEmpty() ? r.said(words) : r.found().size() + " found for \"" + words + "\"" + (r.answered().isEmpty() ? "" : " through " + String.join(" and ", r.answered())) + ", " + judgedHere[0] + " judged against \"" + criteria + "\"";
        tracedAsk(by, section, what, kind, words, criteria, judgedHere[0], got[0] == null ? "" : got[0].kept().id(), got[0] == null ? "none" : "kept as " + got[0].kept().id());
        return new Asked(words, criteria, r.found().size(), judgedHere[0], got[0], said);
    }

    /** The trace's line for one ask, whatever came of it: who asked, the section, what, the words and criteria, the counts, the outcome. */
    void tracedAsk(String by, String section, String what, String kind, String words, String criteria, int judged, String kept, String outcome) {
        ObjectNode o = J.createObjectNode();
        o.put("by", by); o.put("section", section); o.put("what", what); o.put("kind", kind); o.put("words", words); o.put("criteria", criteria); o.put("judged", judged); o.put("kept", kept); o.put("outcome", outcome);
        event.accept("picture_for", o);
    }

    /**
     * The tool for the writer: {@code subject} is the question's own subject, which joins the search words and the judge's criteria;
     * {@code register} makes a kept picture a source of the run and answers the number the writer cites it by.
     */
    PictureForTool pictureFor(String subject, Researcher.Budget budget, Function<Picture, String> register) { return new PictureForTool(subject, budget, register); }

    /**
     * picture_for: the writer names the section and what the picture should show, and {@link #askFor} runs; the answer is the line to place
     * under the paragraph and the number to cite. One ask a section, {@link #FOR_AT_MOST} a report counting the pictures the library kept
     * for the sections already; each ask that runs takes one turn of the write-up's reserve ({@link Researcher.Budget#takeSynthesis}), and
     * none runs once the write-up's own time is up ({@link Researcher.Budget#writeUpTimeUp}). The library asks first ({@link #sections});
     * this tool stays for a writer that asks for more.
     */
    final class PictureForTool implements Tool {
        private final String subject; private final Researcher.Budget budget; private final Function<Picture, String> register;
        /** The sections asked for already, lower-cased. */
        private final Set<String> sections = new HashSet<>();
        /** Asks that ran, pictures they found, asks refused for a bound. */
        int calls = 0, found = 0, refused = 0;
        PictureForTool(String subject, Researcher.Budget budget, Function<Picture, String> register) { this.subject = subject == null ? "" : subject.strip(); this.budget = budget; this.register = register; }
        @Override public String name() { return FOR; }
        @Override public String description() {
            return "Bring a picture a section needs: the thing the section describes — a mechanism's diagram, a map of a place, an object, a person, a chart that exists. The library searches, has the model that sees judge the candidates "
                    + "against what you name (the picture must show the thing itself), keeps the first yes and answers the line to place under the paragraph and the number to cite it by. One call a section, at most " + FOR_AT_MOST + " a report.";
        }
        @Override public ObjectNode parametersSchema(ObjectMapper j) {
            ObjectNode p = j.createObjectNode(); p.put("type", "object");
            ObjectNode props = p.putObject("properties");
            props.putObject("section").put("type", "string").put("description", "the heading of the section the picture is for");
            props.putObject("what").put("type", "string").put("description", "what the picture should show, in words, as a caption would say it: \"a diagram of the gear train of the Antikythera mechanism\"");
            props.putObject("kind").put("type", "string").put("description", "diagram, map, photo, chart or any");
            p.putArray("required").add("section").add("what");
            return p;
        }
        @Override public String execute(JsonNode args) {
            String section = args.path("section").asText("").strip(), what = args.path("what").asText("").strip().replaceAll("[.?!]+$", "");
            String kind = args.path("kind").asText("any").strip().toLowerCase(Locale.ROOT); if (!KINDS.contains(kind)) kind = "any";
            if (what.isEmpty()) return "ERROR: what is needed: what the picture should show, in words.";
            if (section.isEmpty()) return "ERROR: section is needed: the heading of the section the picture is for.";
            if (eyes == null) return ModelEntries.noEyes() + " The report goes on without the picture.";
            if (calls + keptForSections >= FOR_AT_MOST) { refused++; tracedAsk(WRITER, section, what, kind, "", "", 0, "", "refused: " + FOR_AT_MOST + " asked for already"); return "No more pictures for this report: " + FOR_AT_MOST + " were asked for already, its most. Go on with the sections."; }
            if (!sections.add(section.toLowerCase(Locale.ROOT))) { refused++; tracedAsk(WRITER, section, what, kind, "", "", 0, "", "refused: the section has its ask"); return "A picture was asked for the section '" + section + "' already, and a section has one at most. Go on with the section."; }
            if (budget.writeUpTimeUp()) { refused++; tracedAsk(WRITER, section, what, kind, "", "", 0, "", "refused: no time"); return "No time left for a picture of " + what + ": " + minutes(budget.deadlineMs() - System.currentTimeMillis()) + " min left, the write-up's own estimate is " + minutes(budget.wrapUpMs()) + " min. Go on with the section."; }
            if (!budget.takeSynthesis()) { refused++; tracedAsk(WRITER, section, what, kind, "", "", 0, "", "refused: no turn"); return "No turn left for a picture of " + what + ": " + budget.used() + " of " + budget.ceiling() + " turns used, the rest is the cite-check's. Go on with the section."; }
            calls++;
            Asked a = askFor(WRITER, section, what, kind, subject);
            if (a.picture() == null) {
                log.accept("write-up: a picture for the section '" + section + "' (" + what + "): " + a.said() + ", none kept");
                return (a.found() == 0 ? a.said() : "No picture of " + what + " was found that the model that sees says shows it: " + a.said() + ".") + " The section stands on its words.";
            }
            found++;
            String number = register == null ? "" : register.apply(a.picture());
            log.accept("write-up: a picture for the section '" + section + "' (" + what + "): " + a.said() + ", kept as " + a.picture().kept().id() + (number.isEmpty() ? "" : ", cited as " + number));
            Images.Judged j = a.picture().judged();
            return "A picture for the section '" + section + "': \"" + a.picture().title() + "\"" + (j.found().host().isEmpty() ? "" : ", from " + j.found().host()) + ", judged by " + eyes.name() + " (" + eyes.model() + "): yes" + (j.why().isBlank() ? "" : " — " + j.why())
                    + ". Put this line on a line of its own under the paragraph it belongs to:\n" + a.picture().line() + (number.isEmpty() ? "" : "\nCite it as " + number + " after that paragraph; the library writes its reference.");
        }
    }

    // ---- the sections' pictures, decided by the library ----

    /**
     * One picture the library asks for on the writer's behalf: the sub-question it is for, what the picture should show as a caption would
     * say it, its kind; or, with {@code skipped} set, why the sub-question gets none.
     */
    record SectionAsk(String sub, String what, String kind, String skipped) { }

    /** Nouns that name a place a map can show; the head of the phrase the ask is built around. */
    static final Set<String> PLACE_HEADS = Set.of("wreck", "shipwreck", "site", "island", "islands", "city", "cities", "town", "village", "harbour", "harbor", "port", "bay", "coast", "coastline", "sea", "strait", "river", "lake", "mountain", "mountains", "valley", "desert", "museum", "location", "place", "region", "route", "voyage", "excavation", "battlefield", "cave", "temple", "palace", "castle", "fort", "fortress", "ruins", "country", "province", "territory", "border", "borders", "trail", "road", "railway", "canal", "bridge", "district", "quarter", "cemetery", "necropolis", "settlement", "colony");
    /** Nouns that name a mechanism, a device or a process a diagram shows. */
    static final Set<String> MECHANISM_HEADS = Set.of("mechanism", "mechanisms", "gear", "gears", "gearing", "train", "dial", "dials", "pointer", "pointers", "pin-and-slot", "axle", "axles", "wheel", "wheels", "engine", "engines", "machine", "machines", "device", "devices", "circuit", "circuits", "process", "cycle", "assembly", "structure", "layout", "anatomy", "system", "apparatus", "linkage", "spring", "lever", "valve", "pump", "reactor", "turbine", "algorithm", "pipeline", "workflow", "architecture", "escapement", "differential", "epicyclic", "transmission", "clockwork", "orrery", "astrolabe", "schematic");
    /** Nouns that name an object a photograph shows. */
    static final Set<String> OBJECT_HEADS = Set.of("inscription", "inscriptions", "coin", "coins", "statue", "statues", "fragment", "fragments", "vase", "vases", "manuscript", "manuscripts", "painting", "paintings", "artefact", "artefacts", "artifact", "artifacts", "tablet", "tablets", "ship", "ships", "building", "buildings", "tool", "tools", "instrument", "instruments", "letter", "letters", "document", "documents", "book", "books", "sword", "helmet", "amphora", "amphorae", "pottery", "mosaic", "fresco", "relief", "bust", "specimen", "fossil", "skull", "bone", "bones", "seal", "medal", "banner", "flag", "plate", "folio", "scroll", "papyrus", "codex", "stele", "sarcophagus", "jewellery", "jewelry", "weapon", "weapons", "armour", "armor", "costume", "dress", "mask", "figurine", "idol", "altar", "tomb", "grave", "monument", "plaque");
    /** Words that say a sub-question argues or interprets: its section stands on its words, and no picture is asked for. */
    static final Set<String> ABSTRACT_WORDS = Set.of("why", "whether", "significance", "meaning", "debate", "debates", "debated", "interpret", "interpreted", "interpretation", "interpretations", "controversy", "controversies", "controversial", "theory", "theories", "hypothesis", "hypotheses", "influence", "influenced", "legacy", "impact", "accuracy", "accurate", "reliability", "reliable", "consensus", "implication", "implications", "purpose", "scholarly", "scholars", "dating", "chronology", "origin", "origins", "attribution", "attributed", "comparison", "compare", "compared", "assess", "assessment", "argument", "arguments", "importance", "reception", "criticism", "criticisms", "consequence", "consequences", "motive", "motives", "reputation",
            "extent", "understanding", "speculative", "speculation", "assumptions", "assumption", "challenge", "challenges", "creator", "patron", "likely", "reconstruction");
    static final Set<String> DETERMINERS = Set.of("the", "a", "an", "this", "that", "these", "those", "its", "their", "his", "her", "our", "each", "every", "both", "all", "two", "three", "four", "five", "main", "central", "famous", "ancient", "original", "whole", "entire", "inner", "outer", "upper", "lower", "front", "back", "rear", "large", "small", "great", "little", "old", "new");
    static final Set<String> AUX = Set.of("does", "did", "do", "is", "are", "was", "were", "has", "have", "had", "can", "could", "would", "should", "will", "might", "may", "exactly", "precisely", "really", "first");
    /** Heads that name an event whose place is the thing to show: "the wreck site". */
    static final Set<String> SITE_HEADS = Set.of("wreck", "shipwreck", "excavation", "battle", "battlefield", "crash", "landing", "eruption");

    /**
     * What a sub-question's section needs shown, read from its words alone, deterministically: a sub-question that argues or interprets
     * (why, significance, interpretation, dating …) gets none; one that names a place gets a map of it, one that names a mechanism or a
     * process a diagram of it, one that names a person a portrait, one that names an object or another thing a photograph of it; a phrase of
     * titles, periods and peoples alone names nothing ({@link #onlyRolesPeriodsOrPeoples}); and the thing the run looks for pictures of
     * already — the question's own subjects, in {@code chainEntities}, lower-cased — gets no second ask. The phrase is the words around the
     * head noun: back over determiners and names, forward over the head's own kind of word, names and "of the …".
     */
    static SectionAsk sectionAsk(String sub, Set<String> chainEntities) {
        String s = sub == null ? "" : sub.strip();
        if (JAPANESE.matcher(s).find()) return sectionAskJa(s);
        List<String> words = new ArrayList<>();
        for (String w : s.split("\\s+")) { String x = w.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", ""); if (!x.isEmpty()) words.add(x); }
        if (words.isEmpty()) return new SectionAsk(s, "", "", "it has no words");
        boolean abstractWords = false; for (String w : words) if (ABSTRACT_WORDS.contains(w.toLowerCase(Locale.ROOT))) abstractWords = true;
        if (abstractWords) {   // a section of argument stands on its words — unless a person is NAMED in them, whose portrait the section may carry (a role word alone, "creator", "patron", names nobody)
            for (String n : CrossLinks.names(s, "")) {
                String name = named(n);
                if (name.isEmpty() || onlyRolesPeriodsOrPeoples(name) || !isPersonName(name)) continue;
                String last = name.substring(name.lastIndexOf(' ') + 1).toLowerCase(Locale.ROOT);
                if (MECHANISM_HEADS.contains(last) || OBJECT_HEADS.contains(last) || PLACE_HEADS.contains(last)) continue;   // "Antikythera Mechanism" passes for a person's name by its two capitals; its last word says it is a thing
                if (chainEntities != null && chainEntities.contains(name.toLowerCase(Locale.ROOT))) continue;   // the question's own subject has the chain's pictures
                return new SectionAsk(s, "a portrait of " + name, "photo", null);
            }
            return new SectionAsk(s, "", "", "it asks why, or weighs significance or interpretation; a section of argument stands on its words");
        }
        int place = headAt(words, PLACE_HEADS), mech = headAt(words, MECHANISM_HEADS), object = headAt(words, OBJECT_HEADS);
        String what, kind;
        if (place >= 0) { String phrase = phraseAround(words, place, PLACE_HEADS); kind = "map"; what = "a map of " + phrase + (SITE_HEADS.contains(words.get(place).toLowerCase(Locale.ROOT)) && !phrase.toLowerCase(Locale.ROOT).matches(".*\\bsite\\b.*") ? " site" : ""); }   // "the shipwreck site near Antikythera" says site already (J-0013)
        else if (mech >= 0) { kind = "diagram"; what = "a diagram of " + phraseAround(words, mech, MECHANISM_HEADS); }
        else {
            String person = null, thing = null;
            for (String n : CrossLinks.names(s, "")) {
                String name = named(n);
                if (name.isEmpty() || onlyRolesPeriodsOrPeoples(name)) continue;
                if (isPersonName(name)) { if (person == null) person = name; } else if (thing == null) thing = name;
            }
            if (person != null) { kind = "photo"; what = "a portrait of " + person; }
            else if (object >= 0) { kind = "photo"; what = "a photograph of " + phraseAround(words, object, OBJECT_HEADS); }
            else if (thing != null) { kind = "photo"; what = "a photograph of " + thing; }
            else return new SectionAsk(s, "", "", "its words name no thing a picture can show");
        }
        String bare = what.replaceFirst("^a (?:map|diagram|portrait|photograph) of (?:the |an? )?", "").replaceFirst(" site$", "").toLowerCase(Locale.ROOT);
        if (chainEntities != null && chainEntities.contains(bare)) return new SectionAsk(s, "", "", "the run looks for pictures of " + bare + " already");
        return new SectionAsk(s, what, kind, null);
    }

    static final Pattern JAPANESE = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]");
    /** The Japanese heads: a mechanism or a device gets a diagram (図), a place a map (地図), an object a photograph (写真). */
    static final List<String> MECHANISM_HEADS_JA = List.of("機構", "歯車列", "歯車", "装置", "仕組み", "構造", "機械", "回路", "工程"), PLACE_HEADS_JA = List.of("沈没船", "遺跡", "島", "都市", "場所", "博物館", "港", "海岸", "地域", "村", "町", "神殿", "遺構", "城", "川", "湖", "山"), OBJECT_HEADS_JA = List.of("碑文", "硬貨", "破片", "彫像", "像", "壺", "写本", "文書", "道具", "刀", "兜", "絵画", "絵");
    static final List<String> ABSTRACT_JA = List.of("なぜ", "意義", "意味", "影響", "論争", "解釈", "評価", "理由", "仮説", "推測", "前提");
    static final Pattern QUESTION_FRAME_JA = Pattern.compile("^(?:どのように|どうやって|どうして|なぜ|どこで|どこに|どこの|何が|何を|いつ|誰が|誰の)");

    /** A Japanese sub-question read the same way: the phrase up to the head word, with the kind's word after it. */
    static SectionAsk sectionAskJa(String s) {
        for (String w : ABSTRACT_JA) if (s.contains(w)) return new SectionAsk(s, "", "", "it asks why, or weighs significance or interpretation; a section of argument stands on its words");
        String body = QUESTION_FRAME_JA.matcher(s).replaceFirst("");
        int place = firstAt(body, PLACE_HEADS_JA), mech = firstAt(body, MECHANISM_HEADS_JA), object = firstAt(body, OBJECT_HEADS_JA);
        if (place >= 0) return new SectionAsk(s, phraseJa(body, place, PLACE_HEADS_JA) + "の地図", "map", null);
        if (mech >= 0) return new SectionAsk(s, phraseJa(body, mech, MECHANISM_HEADS_JA) + "の図", "diagram", null);
        if (object >= 0) return new SectionAsk(s, phraseJa(body, object, OBJECT_HEADS_JA) + "の写真", "photo", null);
        return new SectionAsk(s, "", "", "its words name no thing a picture can show");
    }
    /** The index where the first of {@code heads} occurs in the text, -1 for none. */
    static int firstAt(String text, List<String> heads) { int best = -1; for (String h : heads) { int i = text.indexOf(h); if (i >= 0 && (best < 0 || i < best)) best = i; } return best; }
    /** The text up to and including the head that starts at {@code at}, a leading particle dropped. */
    static String phraseJa(String text, int at, List<String> heads) {
        String head = ""; for (String h : heads) if (text.startsWith(h, at) && h.length() > head.length()) head = h;
        return text.substring(0, at + head.length()).replaceAll("^[はがをにでのとも、。\\s]+", "");
    }

    /** The index of the first word that is one of {@code heads}, -1 for none. */
    static int headAt(List<String> words, Set<String> heads) { for (int i = 0; i < words.size(); i++) if (heads.contains(words.get(i).toLowerCase(Locale.ROOT))) return i; return -1; }

    static final Set<String> PREPOSITIONS = Set.of("of", "in", "at", "on", "near", "off", "by", "from");

    /**
     * The phrase around a head word: back over determiners, hyphenated words and names; forward over the head's kind of word, a preposition
     * ("of the …", "in Athens"), determiners, hyphenated words and names; a trailing joiner dropped; "the" put before a bare head ("museum" is
     * "the museum").
     */
    static String phraseAround(List<String> words, int head, Set<String> heads) {
        int from = head, to = head;
        while (from > 0 && (DETERMINERS.contains(words.get(from - 1).toLowerCase(Locale.ROOT)) || nameLike(words.get(from - 1)))) from--;
        while (to + 1 < words.size() && (heads.contains(words.get(to + 1).toLowerCase(Locale.ROOT)) || PREPOSITIONS.contains(words.get(to + 1).toLowerCase(Locale.ROOT)) || DETERMINERS.contains(words.get(to + 1).toLowerCase(Locale.ROOT)) || nameLike(words.get(to + 1)))) to++;
        while (to > head && (PREPOSITIONS.contains(words.get(to).toLowerCase(Locale.ROOT)) || DETERMINERS.contains(words.get(to).toLowerCase(Locale.ROOT)))) to--;
        String phrase = String.join(" ", words.subList(from, to + 1));
        String first = words.get(from);
        return DETERMINERS.contains(first.toLowerCase(Locale.ROOT)) || nameLike(first) ? phrase : "the " + phrase;
    }

    /** A word that reads as part of a name: hyphenated, or capitalised and not a question word or an auxiliary. */
    static boolean nameLike(String w) {
        String l = w.toLowerCase(Locale.ROOT);
        return w.contains("-") || (Character.isUpperCase(w.charAt(0)) && !QUESTION_WORDS.contains(l) && !AUX.contains(l) && !ABSTRACT_WORDS.contains(l));
    }

    /** The plan's asks, kept for the step after the workers; and how many pictures that step kept. */
    private final List<SectionAsk> planned = new ArrayList<>();
    private volatile int keptForSections = 0;

    /**
     * At the plan: the sub-questions whose words name a thing a picture can show, each its ask, at most {@link #FOR_AT_MOST}; the rest are
     * traced with the reason ({@code picture_for}, by the library, skipped). Nothing when the run has no pictures or no model sees. Returns
     * the count, so the turns can be held.
     */
    int planSections(List<String> subs, Researcher.Ask ask) {
        planned.clear();
        if (!offered(ask) || eyes == null || subs == null || subs.isEmpty()) return 0;
        Set<String> chain = new HashSet<>(); for (String e : entities(ask.question(), "")) chain.add(e.toLowerCase(Locale.ROOT));
        Set<String> whats = new HashSet<>(); List<String> said = new ArrayList<>();
        for (String sub : subs) {
            SectionAsk a = sectionAsk(sub, chain);
            if (a.skipped() == null && !whats.add(a.what().toLowerCase(Locale.ROOT))) a = new SectionAsk(sub, "", "", "the same picture is asked for another sub-question already");
            if (a.skipped() == null && planned.size() >= FOR_AT_MOST) a = new SectionAsk(sub, "", "", FOR_AT_MOST + " pictures for the sections are asked for already, the most for a report");
            if (a.skipped() != null) { tracedAsk(LIBRARY, sub, "", "", "", "", 0, "", "skipped: " + a.skipped()); continue; }
            planned.add(a); said.add("\"" + a.what() + "\" for \"" + Acquisitions.compress(sub, 80) + "\"");
        }
        if (!planned.isEmpty()) log.accept("pictures: " + planned.size() + " of " + subs.size() + " sub-question(s) name a thing a picture can show; the library will ask for " + String.join("; ", said) + " before the write-up");
        else log.accept("pictures: none of the " + subs.size() + " sub-question(s) names a thing a picture can show beyond what the run looks for already; the sections get no picture of their own");
        return planned.size();
    }
    List<SectionAsk> planned() { return List.copyOf(planned); }
    int keptForSections() { return keptForSections; }

    /** Turns kept back at the plan for the sections' pictures: one an ask, never more than a sixth of what the workers would have; 0 without a ceiling. */
    int sectionTurnsToReserve(Researcher.Budget budget) {
        if (planned.isEmpty() || budget.unbounded()) return 0;
        return Math.min(planned.size(), Math.max(1, (budget.left() - budget.reserve()) / 6));
    }

    /**
     * After the workers and before the write-up: the plan's asks, on the held turns, one picture a sub-question, only while the write-up
     * still fits — when it does not, the log says so with the numbers, as the chain's line does. The kept pictures come back as a piece of
     * evidence in the workers' shape, each saying the section it belongs to, so the SOURCES list hands the writer the lines with their
     * sections ({@link #writerNote}). "" when nothing was kept.
     */
    String sections(Researcher.Ask ask, Researcher.Budget budget) {
        if (planned.isEmpty() || eyes == null) return "";
        String subject = subjectOf(ask.question());
        StringBuilder notes = new StringBuilder();
        for (int i = 0; i < planned.size(); i++) {
            SectionAsk a = planned.get(i);
            List<SectionAsk> rest = planned.subList(i, planned.size());
            List<String> names = new ArrayList<>(); for (SectionAsk b : rest) names.add("\"" + Acquisitions.compress(b.sub(), 60) + "\"");
            String stop = budget.writeUpTimeUp() ? "no time left for pictures for the sections on " + String.join(", ", names) + ": " + minutes(budget.deadlineMs() - System.currentTimeMillis()) + " min left, the write-up's estimate is " + minutes(budget.wrapUpMs()) + " min"
                    : !budget.takeHeld() ? "no turn left for pictures for the sections on " + String.join(", ", names) + ": " + budget.used() + " of " + budget.ceiling() + " turns used, " + budget.reserve() + " kept for the write-up" : null;
            if (stop != null) { log.accept("pictures: " + stop); for (SectionAsk b : rest) tracedAsk(LIBRARY, b.sub(), b.what(), b.kind(), "", "", 0, "", stop.startsWith("no time") ? "refused: no time" : "refused: no turn"); break; }
            Asked got = askFor(LIBRARY, a.sub(), a.what(), a.kind(), subject);
            log.accept("pictures: a picture for \"" + a.sub() + "\" (" + a.what() + "): " + got.said() + (got.picture() == null ? ", none kept" : ", kept as " + got.picture().kept().id()));
            if (got.picture() != null) { keptForSections++; notes.append(got.picture().note()).append('\n'); }
        }
        if (notes.isEmpty()) return "";
        return "SUB-QUESTION: pictures for the report's sections\nSUMMARY: The library looked for a picture for each sub-question whose words name a thing a picture can show, and had the model that sees pictures ("
                + eyes.model() + ") judge the candidates against what the section describes; the ones it said yes to are kept on the shelves, listed below as sources, and each belongs to the section named.\nEVIDENCE:\n" + notes.toString().strip();
    }

    // ---- the chain ----

    /**
     * The entities the question and the findings name, the question's own first, then the findings' by how often they are named: at most
     * {@link #CHAIN_ENTITIES}. People, organisations and places as {@link CrossLinks#names} reads them.
     */
    static List<String> entities(String question, String evidence) { return entities(question, evidence, CHAIN_ENTITIES); }

    /** The same, at most {@code most}. */
    static List<String> entities(String question, String evidence, int most) {
        Map<String, Integer> score = new LinkedHashMap<>();
        String q = question == null ? "" : question;
        Set<String> asked = new LinkedHashSet<>();
        for (String n : CrossLinks.names(q, "")) { String name = named(n); if (!name.isEmpty()) asked.add(name); }
        if (asked.isEmpty()) { String event = eventPhrase(q); if (!event.isEmpty()) asked.add(event); }   // "the eruption of 79 AD": the event, said in the question's own words
        for (String n : asked) score.merge(n, 1_000_000, Integer::sum);
        // the evidence without its own labels (SUB-QUESTION, SUMMARY, EVIDENCE, a tool's TEXT), which read as acronyms; an acronym counts only when the question carries it
        String ev = EVIDENCE_LABELS.matcher(evidence == null ? "" : evidence).replaceAll(" ");
        for (String raw : CrossLinks.names(ev, q)) {
            String n = named(raw);
            if (n.isEmpty()) continue;
            // a name the question does not carry is chased only when it is a full name of two or more words — a person, an organisation, an
            // artefact, an event; a single word, a passing place (the museum's city, Athens, chosen by count on a live run, 2026-11-08), a
            // surname alone or an acronym is not a picture subject for this ask; nor is a phrase of titles, periods and peoples alone
            // ("Ephor General", "Hellenistic Greek": ten judge calls for nothing on J-0009)
            if (!asked.contains(n) && (!n.contains(" ") || onlyRolesPeriodsOrPeoples(n))) continue;
            int count = 0; Matcher m = Pattern.compile(Pattern.quote(n)).matcher(ev); while (m.find()) count++;
            score.merge(n, count, Integer::sum);
        }
        List<String> out = new ArrayList<>(score.keySet());
        out.sort((a, b) -> Integer.compare(score.get(b), score.get(a)));
        // a name that is part of a longer one already taken names the same thing
        List<String> picked = new ArrayList<>();
        for (String n : out) { boolean inside = false; for (String p : picked) if (p.toLowerCase(Locale.ROOT).contains(n.toLowerCase(Locale.ROOT)) || n.toLowerCase(Locale.ROOT).contains(p.toLowerCase(Locale.ROOT))) { inside = true; break; } if (!inside) picked.add(n); if (picked.size() >= most) break; }
        return picked;
    }

    /** A title or a role, which names an office and not a person: "Ephor General" (J-0009). */
    static final Set<String> ROLE_WORDS = Set.of("Ephor", "General", "Director", "Professor", "Doctor", "Dr", "Minister", "President", "King", "Queen", "Prince", "Princess", "Emperor", "Empress", "Saint", "St",
            "Captain", "Admiral", "Colonel", "Major", "Bishop", "Archbishop", "Pope", "Lord", "Lady", "Sir", "Dame", "Chief", "Curator", "Keeper", "Secretary", "Ambassador", "Governor", "Mayor", "Senator",
            "Judge", "Reverend", "Father", "Brother", "Sister", "Mr", "Mrs", "Ms", "Madame", "Monsieur", "Herr", "Frau", "Count", "Countess", "Duke", "Duchess", "Baron", "Baroness", "Earl", "Marquis",
            "Chancellor", "Prime", "Deputy", "Vice", "Chairman", "Chairwoman", "Chair", "Officer", "Inspector", "Sergeant", "Lieutenant", "Commander", "Archaeologist", "Historian", "Ephorate", "Excavator", "Founder");
    /** A period, a culture, a style or a field of study, which names a time or a subject and not a thing a picture shows: "Hellenistic Greek", "Classical Archaeology". */
    static final Set<String> PERIOD_WORDS = Set.of("Hellenistic", "Classical", "Archaic", "Geometric", "Mycenaean", "Minoan", "Byzantine", "Roman", "Ottoman", "Medieval", "Mediaeval", "Renaissance", "Baroque", "Rococo",
            "Gothic", "Victorian", "Edwardian", "Georgian", "Regency", "Modern", "Modernist", "Contemporary", "Ancient", "Antique", "Bronze", "Iron", "Stone", "Age", "Neolithic", "Palaeolithic", "Paleolithic",
            "Mesolithic", "Prehistoric", "Imperial", "Republican", "Colonial", "Industrial", "Enlightenment", "Antiquity", "Late", "Early", "Middle", "High", "Golden", "Period", "Era", "Epoch", "Century",
            "Archaeology", "Archeology", "History", "Philology", "Numismatics", "Epigraphy", "Anthropology", "Astronomy", "Mathematics", "Studies", "Literature", "Philosophy", "Science", "Sciences", "Art", "Arts",
            "Architecture", "Sculpture", "Painting", "Pottery", "Culture", "Civilisation", "Civilization", "Style", "School", "Tradition", "Dynasty");
    static final Set<String> JOINERS = Set.of("of", "and", "the", "&", "de", "van", "von", "da", "di", "del", "la", "le");

    /** Whether every real word of a phrase is a title or role, a period, culture, style or field word, or a people's adjective: then it names no person, thing or place, and is not chased. */
    static boolean onlyRolesPeriodsOrPeoples(String name) {
        int real = 0;
        for (String w : name.strip().split("\\s+")) {
            String x = w.replaceAll("[.,;:'’]+$", "");
            if (x.isEmpty() || JOINERS.contains(x.toLowerCase(Locale.ROOT))) continue;
            real++;
            if (!ROLE_WORDS.contains(x) && !PERIOD_WORDS.contains(x) && !DEMONYMS.contains(x)) return false;
        }
        return real > 0;
    }

    /** Words that pass for a name by their capital and are none: a people's or a period's adjective ("Greek" was read as a name on a live run, 2026-11-08). */
    static final Set<String> DEMONYMS = Set.of("Greek", "Greeks", "Roman", "Romans", "British", "French", "German", "Germans", "English", "American", "Americans", "Japanese", "Chinese", "Italian", "Italians", "Spanish", "Russian", "Russians", "Egyptian", "Egyptians", "Byzantine", "Ottoman", "European", "Europeans", "Asian", "African", "Indian", "Persian", "Arab", "Arabic", "Hebrew", "Latin", "Christian", "Christians", "Muslim", "Muslims", "Jewish", "Western", "Eastern", "Northern", "Southern", "Medieval", "Victorian", "Ancient", "Modern", "Dutch", "Danish", "Swedish", "Norwegian", "Polish", "Turkish", "Korean", "Mexican", "Canadian", "Australian", "Irish", "Scottish", "Welsh", "Hellenistic", "Classical", "Renaissance");

    /** A name as the chain takes it: the article a sentence's start gave it taken off ("The Greek" is "Greek"); "" for a people's or a period's adjective, which is no name. */
    static String named(String n) {
        String name = n == null ? "" : n.strip().replaceFirst("^(?:The|A|An)\\s+", "").replaceFirst("\\s+(?:and|of|the|&|de)$", "").strip();   // "Athens and" is Athens
        return name.isEmpty() || DEMONYMS.contains(name) ? "" : name;
    }

    /** The words that say an event without a name: with a year beside them, the question is about an event. */
    static final Pattern EVENT_WORDS = Pattern.compile("(?i)\\b(battle|war|siege|eruption|earthquake|tsunami|flood|fire|storm|hurricane|typhoon|famine|plague|epidemic|pandemic|election|coronation|revolution|uprising|riot|massacre|treaty|summit|conference|expedition|voyage|shipwreck|wreck|salvage|excavation|discovery|launch|landing|crash|disaster|explosion|strike|festival|games|olympics|exhibition|fair|trial|assassination|coup|invasion|liberation|independence|founding|opening|collapse)s?\\b");
    static final Pattern YEAR = Pattern.compile("\\b(?:1[0-9]{3}|20[0-9]{2}|[1-9][0-9]{1,2}\\s*(?:AD|BC|BCE|CE))\\b");
    static final Set<String> QUESTION_WORDS = Set.of("what", "who", "whom", "whose", "when", "where", "which", "why", "how", "did", "does", "do", "was", "were", "is", "are", "the", "a", "an", "of", "in", "at", "on", "to", "for", "and", "or", "from", "by", "with", "about", "happened", "happen", "find", "found", "tell", "me", "please", "exactly", "really", "there", "that", "this", "it", "its");

    /** The event a question says without naming anyone, in the question's own words: an event word with a year beside it, the question words taken off; "" when it says none. */
    static String eventPhrase(String question) {
        String q = question == null ? "" : question;
        if (!EVENT_WORDS.matcher(q).find() || !YEAR.matcher(q).find()) return "";
        List<String> words = new ArrayList<>();
        for (String w : q.replaceAll("[^\\p{L}\\p{N}\\s]", " ").split("\\s+")) if (!w.isBlank() && !QUESTION_WORDS.contains(w.toLowerCase(Locale.ROOT))) words.add(w);
        if (words.isEmpty()) return "";
        return String.join(" ", words.size() > 8 ? words.subList(0, 8) : words);
    }

    /** The words the evidence is laid out with, which are not names. */
    static final Pattern EVIDENCE_LABELS = Pattern.compile("(?m)^(?:SUB-QUESTION|SUMMARY|EVIDENCE|TEXT of|ERROR):?|\\b(?:SUB-QUESTION|SUMMARY|EVIDENCE|SOURCES|COVERAGE)\\b");

    /** The question's own subject: the longest name it carries, "" when it names none. */
    static String subjectOf(String question) {
        String best = "";
        for (String n : CrossLinks.names(question == null ? "" : question, "")) if (n.length() > best.length()) best = n;
        return best;
    }

    /**
     * The first part, right after the plan and before any worker draws a turn: the question's own subjects — the names it carries, or the
     * event it says by a year and an event word — searched, judged and kept on the held turns. Nothing has been spent yet, so the write-up's
     * margin cannot refuse it: on two live runs a step that came after the workers lost the race to their turns and their time (2026-11-08).
     * Bounded as the chain is: at most {@link #CHAIN_ENTITIES} subjects, {@link #CHAIN_COUNT} candidates each, {@link #CHAIN_KEEP} kept, one
     * turn each. Returns a piece of evidence in the workers' shape, "" when nothing was kept.
     */
    String first(Researcher.Ask ask, Researcher.Budget budget, List<String> fieldNames) {
        if (!chainFor(ask, fieldNames)) return "";
        List<String> subjects = entities(ask.question(), "");
        if (subjects.isEmpty()) return "";
        if (eyes == null) {
            log.accept("pictures: the question names " + String.join(", ", subjects) + "; no model sees pictures, so none were looked for (researchzosho models test <name> probes one)");
            return "";
        }
        return part(ask, subjects, budget, "the question names", "what the question names");
    }

    /**
     * The second part, after the workers: the full names the findings raise — the chaining from an event to a person the findings name to
     * that person's pictures — on whatever held turns remain, then the run's own, and only while the write-up still fits. When it cannot
     * run the log says so with the numbers, the minutes left against the write-up's estimate, or the turns used against the ceiling.
     */
    String later(Researcher.Ask ask, String evidence, Researcher.Budget budget, List<String> fieldNames) {
        if (!chainFor(ask, fieldNames) || eyes == null) return "";
        List<String> names = new ArrayList<>();
        // the findings without the pictures' own pieces: a kept picture's title ("Alaska Red Fox") is not a name the findings raise
        String findings = PICTURE_PIECES.matcher(evidence == null ? "" : evidence).replaceAll("");
        for (String n : entities(ask.question(), findings, CHAIN_ENTITIES * 2)) if (!searchedEntities.contains(n) && !searched.contains(n.toLowerCase(Locale.ROOT))) names.add(n);
        if (names.size() > CHAIN_ENTITIES) names = new ArrayList<>(names.subList(0, CHAIN_ENTITIES));
        if (names.isEmpty()) return "";
        if (budget.writeUpTimeUp()) { log.accept(noTime(budget, String.join(", ", names))); return ""; }
        return part(ask, names, budget, "the findings name", "the people, organisations and things the findings name");
    }

    /** The pieces of evidence the picture steps wrote, and a picture's note wherever it stands: not findings for the second part to read names from. */
    static final Pattern PICTURE_PIECES = Pattern.compile("(?m)^(?:SUB-QUESTION: pictures (?:of|kept|for) .*|SUMMARY: (?:The library looked for pictures|The library looked for a picture|Pictures the model that sees judged) .*|- A picture judged to show .*)$");

    /** "no time left for pictures of …: 17.0 min left, the write-up's estimate is 17.7 min" — one line with the numbers, so a reader need not compute them from the trace. */
    static String noTime(Researcher.Budget budget, String names) {
        return "pictures: no time left for pictures of " + names + ": " + minutes(budget.deadlineMs() - System.currentTimeMillis()) + " min left, the write-up's estimate is " + minutes(budget.wrapUpMs()) + " min";
    }

    static String minutes(long ms) { return String.format(Locale.ROOT, "%.1f", Math.max(0, ms) / 60_000.0); }

    /** The entities a part searched, so the second part does not search the first part's again. */
    private final Set<String> searchedEntities = ConcurrentHashMap.newKeySet();

    /** One part: each entity searched by its name with the question's subject beside it, the candidates judged in the ask's connection, the yeses kept as evidence, one turn each. */
    private String part(Researcher.Ask ask, List<String> entities, Researcher.Budget budget, String because, String of) {
        String subject = subjectOf(ask.question());
        StringBuilder notes = new StringBuilder(); List<String> done = new ArrayList<>();
        for (String entity : entities) {
            String words = subject.isEmpty() || entity.equalsIgnoreCase(subject) || entity.toLowerCase(Locale.ROOT).contains(subject.toLowerCase(Locale.ROOT)) || subject.toLowerCase(Locale.ROOT).contains(entity.toLowerCase(Locale.ROOT)) ? entity : entity + " " + subject;
            if (searched.contains(words.toLowerCase(Locale.ROOT)) || searched.contains(entity.toLowerCase(Locale.ROOT))) { log.accept("pictures: \"" + words + "\" was searched already; not again"); continue; }
            if (!budget.takeHeld()) {
                log.accept(budget.writeUpTimeUp() ? noTime(budget, entity) : "pictures: no turn left for pictures of " + entity + ": " + budget.used() + " of " + budget.ceiling() + " turns used, " + budget.reserve() + " kept for the write-up");
                break;
            }
            searchedEntities.add(entity);
            String why = because + " " + entity + (subject.isEmpty() || entity.equals(subject) ? "" : " beside " + subject);
            // the judge is asked for the entity in the ask's own connection, not a bare noun: "Athens" alone passed a coin from elsewhere (2026-11-08)
            String criteria = words.equals(entity) ? entity : entity + ", in connection with " + subject;
            Images.Result r = Images.find(store, words, CHAIN_COUNT);
            searches.incrementAndGet(); searched.add(words.toLowerCase(Locale.ROOT));
            // a person's first confirmed likeness becomes the reference for the rest of the pass: the candidates after it are judged beside it
            Pass pass = new Pass(entity);
            Picture known = personReference.get(entity.toLowerCase(Locale.ROOT));
            if (known != null) { try { pass.reference(referenceFor(known)); } catch (IOException ignored) { } }
            int[] keptHere = {0}, judgedHere = {0};
            judgeAll(criteria, r.found(), pass, l -> {
                if (l.judged() == null) return;
                judgedHere[0]++;
                if (!l.judged().yes()) return;
                if (keptHere[0] >= CHAIN_KEEP) { event.accept("picture", traced(l, entity, criteria, "", "the " + CHAIN_KEEP + " allowed for this entity were kept already")); return; }
                Picture p = keep(l, "pictures of " + entity, why);
                if (p != null) { keptHere[0]++; notes.append(p.note()).append('\n'); seed(pass, p); }
            });
            if (keptHere[0] > 0) done.add(entity);
            log.accept("pictures: \"" + words + "\" searched because " + why + ": " + r.found().size() + " found" + (r.answered().isEmpty() ? " (no engine answered)" : " through " + String.join(" and ", r.answered())) + ", " + judgedHere[0] + " judged against \"" + criteria + "\"" + pass.said() + ", " + keptHere[0] + " kept");
            ObjectNode o = J.createObjectNode(); o.put("entity", entity); o.put("words", words); o.put("why", why); o.put("when", because.startsWith("the question") ? "before the workers" : "after the workers"); o.put("criteria", criteria); o.put("found", r.found().size()); o.put("judged", judgedHere[0]); o.put("kept", keptHere[0]); if (pass.against > 0) { o.put("beside", pass.against); o.put("reference", pass.referenceId); }
            event.accept("picture_chain", o);
        }
        if (notes.isEmpty()) return "";
        return "SUB-QUESTION: pictures of " + String.join(", ", done) + "\nSUMMARY: The library looked for pictures of " + of + " and had the model that sees pictures ("
                + eyes.model() + ") judge each against the name; the ones it said yes to are kept on the shelves and listed below as sources.\nEVIDENCE:\n" + notes.toString().strip();
    }

    /**
     * The pictures kept this run that no note names, as one piece of evidence in the workers' shape, so the references and the evidence
     * table carry every kept picture whether or not the worker noted it; "" when every kept picture is noted already.
     */
    String unnoted(String evidence) {
        StringBuilder b = new StringBuilder();
        for (Picture p : kept) if (evidence == null || !evidence.contains(p.locator())) b.append(p.note()).append('\n');
        if (b.isEmpty()) return "";
        return "SUB-QUESTION: pictures kept this run\nSUMMARY: Pictures the model that sees judged to show what a worker asked for, kept on the shelves with their verdicts and not noted by the worker; listed here so the report's references carry them.\nEVIDENCE:\n" + b.toString().strip();
    }

    /** The kept pictures judged to show a person, the seeds of a search by likeness in the next increment ({@link Images.Likeness}); every kept yes until a picture says what it shows. */
    List<Picture> seeds() { return List.copyOf(kept); }

    // ---- the report ----

    /** The line a Markdown picture is, on its own: its words and its address under this library. */
    static final Pattern IMAGE_LINE = Pattern.compile("(?m)^[ \\t]*!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)[ \\t]*$");

    /**
     * The report's pictures as the writer placed them, checked: a line that shows a picture stays only when the picture is one the
     * library keeps ({@code /images/<file>} and the file is there), the first {@link #SHOW_AT_MOST} of them; the rest, and a picture of
     * the web the writer wrote on its own, are taken off, the log saying so.
     */
    static String shown(LibraryStore store, String text, Consumer<String> log) { return shown(store, text, log, Set.of()); }

    /** The same; the pictures at the served addresses in {@code first} — the writer's own asks — are shown before the others when more than {@link #SHOW_AT_MOST} are placed. */
    static String shown(LibraryStore store, String text, Consumer<String> log, Set<String> first) {
        if (text == null || text.indexOf("![") < 0) return text == null ? "" : text;
        Matcher m = IMAGE_LINE.matcher(text);
        List<String> srcs = new ArrayList<>(); List<Boolean> keptLine = new ArrayList<>();
        while (m.find()) { String src = m.group(2); srcs.add(src); keptLine.add(store != null && src.startsWith("/images/") && Images.kept(store, src.substring("/images/".length())) != null); }
        // which placed lines show: kept pictures only, the writer's own asks first, then the rest in the report's order, SHOW_AT_MOST in all
        Set<Integer> show = new HashSet<>();
        for (int pass = 0; pass < 2; pass++)
            for (int i = 0; i < srcs.size() && show.size() < SHOW_AT_MOST; i++)
                if (keptLine.get(i) && (pass == 0) == (first != null && first.contains(srcs.get(i)))) show.add(i);
        m.reset();
        StringBuilder out = new StringBuilder();
        int i = -1, dropped = 0;
        while (m.find()) { i++; if (show.contains(i)) continue; dropped++; m.appendReplacement(out, ""); }
        m.appendTail(out);
        int shown = show.size();
        if (dropped > 0 && log != null) log.accept("pictures: " + dropped + " picture line(s) the writer placed taken off the report (" + (shown >= SHOW_AT_MOST ? "more than " + SHOW_AT_MOST + ", or " : "") + "not a picture this library keeps); " + shown + " shown");
        return dropped == 0 ? text : out.toString().replaceAll("\n{3,}", "\n\n");
    }

    /** What the references say of a work that is a kept picture: "(a picture, kept as …, from the page …; judged "…": yes — …)"; "" for a work that is not one. */
    static String referenceNote(LibraryStore store, Path rp, List<Picture> runKept) {
        Images.Shelved s = pictureAt(store, rp);
        if (s == null) return "";
        StringBuilder b = new StringBuilder("  (a picture, kept as ").append(s.id());
        if (Images.DOCUMENT.equals(s.source())) b.append(", read out of the document ").append(s.page());
        else if (!s.page().isBlank()) b.append(", from the page ").append(s.page());
        Images.Judged now = judgedThisRun(s, runKept);   // an entry kept on an earlier run carries no verdict against this run's criteria: this run's stands
        if (now != null) b.append("; judged \"").append(now.criteria()).append("\": ").append(now.verdict().word()).append(now.why().isBlank() ? "" : " — " + now.why());
        else if (s.verdict() != Images.Verdict.UNJUDGED) b.append("; judged \"").append(s.criteria()).append("\": ").append(s.verdict().word()).append(s.why().isBlank() ? "" : " — " + s.why());
        return b.append(')').toString();
    }

    /** This run's judgement of the picture an entry is, when the run judged and kept it; null otherwise. */
    static Images.Judged judgedThisRun(Images.Shelved s, List<Picture> runKept) {
        if (runKept == null) return null;
        for (Picture p : runKept) if (p.kept().id().equals(s.id()) && p.judged() != null) return p.judged();
        return null;
    }

    /** The kept picture a work's capture is, by the capture's path as the references found it; null for a work that is not one. */
    static Images.Shelved pictureAt(LibraryStore store, Path rp) {
        if (store == null || rp == null || !Images.KIND.equals(RawCapture.field(rp, "kind"))) return null;
        return Images.shelved(store, rp);
    }

    /** What the writer is told beside a picture's line in the SOURCES list: the one line that shows it, to copy. */
    static String writerNote(LibraryStore store, Path rp, List<Picture> runKept) {
        Images.Shelved s = pictureAt(store, rp);
        if (s == null) return "";
        Images.Judged now = judgedThisRun(s, runKept);
        String judged = now != null ? ", judged \"" + now.criteria() + "\": " + now.verdict().word() : s.verdict() == Images.Verdict.UNJUDGED ? "" : ", judged \"" + s.criteria() + "\": " + s.verdict().word();
        String line = "![" + s.title().replace("]", ")") + "](" + s.url() + ")";
        Picture p = pictureThisRun(s, runKept);
        if (p != null && !p.section().isEmpty()) return "  — a picture" + judged + "; it belongs to the section on \"" + p.section() + "\": to show it, put this line on a line of its own under the paragraph of that section: " + line;
        return "  — a picture" + judged + "; to show it, put this line on a line of its own under the paragraph that cites it: " + line;
    }

    /** The picture of this run an entry is, when the run kept it; null otherwise. */
    static Picture pictureThisRun(Images.Shelved s, List<Picture> runKept) {
        if (runKept == null) return null;
        for (Picture p : runKept) if (p.kept().id().equals(s.id())) return p;
        return null;
    }

    /** A hosted seeing entry's tokens on the run's tally, as the translator's are; nothing for a local one or when no picture was judged. */
    void tally(LibraryStore store, String jobId, String reportId, long seconds) {
        if (eyes == null || !eyes.hosted()) return;
        long[] u; synchronized (usage) { u = usage.clone(); }
        if (u[0] + u[2] == 0) return;
        try { Spend.record(store, jobId, reportId, eyes, u, eyes.cost(u[0], u[1], u[2]), seconds, "filed", "pictures"); } catch (IOException ignored) { /* the tally is a record, not a gate */ }
    }

    /** The run's tokens on the seeing entry so far: prompt, cached, completion. */
    long[] usage() { synchronized (usage) { return usage.clone(); } }
}
