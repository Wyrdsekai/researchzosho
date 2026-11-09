package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.records.Ops;
import org.researchzosho.records.RecordSource;
import org.researchzosho.records.RecordSources;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * {@code record_search}: the collections of records themselves — newspapers, patents, scanned directories and local
 * histories, archive catalogues — searched one at a time by name. A web engine ranks these low or cannot see inside
 * them at all; what a person did in life is in them. The sources are data ({@link RecordSources}), ordered for the
 * question: the ones in the languages the question is about come first.
 */
public final class RecordSearchTool implements Tool {

    public static final AtomicInteger RECORDS_USED = new AtomicInteger();

    private final List<RecordSource> sources;
    private final RecordSources.Reader reader;
    /** What the question says of its subject besides the name: the years, the places, the people next to them. A record that carries one of them is more than a namesake. */
    private final List<String> saidOfThem;
    /** The collections left out because their years and the person's do not meet. Named in the description, so that the worker knows they exist. */
    private final List<RecordSource> leftOut;

    public RecordSearchTool(Collection<String> languagesOfTheQuestion) { this(RecordSources.ordered(languagesOfTheQuestion), RecordSources.LIVE); }

    /**
     * For one worker: the collections its sub-question names come first in the list it reads. The years its subject lived narrow them
     * only when a run of a field that reads dates is given the tool ({@link #forYears}).
     */
    public RecordSearchTool(Collection<String> languagesOfTheQuestion, String focus) { this(RecordSources.ordered(languagesOfTheQuestion, focus), RecordSources.LIVE, RecordSources.saidOf(focus)); }

    /** The collections for the question, the ones whose years cannot meet the person's left out and named. */
    public RecordSearchTool(List<RecordSource> all, RecordSources.Reader reader, List<String> saidOfThem, int[] lived) {
        this(RecordSources.forYears(all, lived), reader, saidOfThem, all.stream().filter(s -> !RecordSources.forYears(List.of(s), lived).contains(s)).toList());
    }

    public RecordSearchTool(List<RecordSource> sources, RecordSources.Reader reader) { this(sources, reader, List.of()); }

    public RecordSearchTool(List<RecordSource> sources, RecordSources.Reader reader, List<String> saidOfThem) { this(sources, reader, saidOfThem, List.<RecordSource>of()); }

    private RecordSearchTool(List<RecordSource> sources, RecordSources.Reader reader, List<String> saidOfThem, List<RecordSource> leftOut) { this.sources = sources; this.reader = reader; this.saidOfThem = saidOfThem; this.leftOut = leftOut; }

    /** One search as it happened: the run keeps the citations of what was found, and lists what was searched with no result. */
    public record Searched(RecordSource source, String query, int fromYear, int toYear, List<RecordSources.Hit> hits) { }

    private volatile Consumer<Searched> listener = s -> { };

    public RecordSearchTool listen(Consumer<Searched> l) { this.listener = l == null ? s -> { } : l; return this; }

    /** One search a collection did not answer: the run says it was asked and refused, never that it was not searched. */
    public record Refused(RecordSource source, String query, String reason) { }

    private volatile Consumer<Refused> refusals = r -> { };

    public RecordSearchTool refusals(Consumer<Refused> l) { this.refusals = l == null ? r -> { } : l; return this; }

    /** The same tool without the collections named by id: a run whose pool is enumerated by another instrument does not carry the searches that rank. */
    public RecordSearchTool without(Collection<String> ids) {
        return new RecordSearchTool(sources.stream().filter(s -> !ids.contains(s.id())).toList(), reader, saidOfThem, leftOut.stream().filter(s -> !ids.contains(s.id())).toList()).policy(policy).allowance(allowance).listen(listener).refusals(refusals);
    }

    /** Which lists take records out before the model sees them: the run's, or both lists. */
    private volatile Fetch.Policy policy = Fetch.Policy.DEFAULT;

    /** The run's lists; the copies this tool makes keep them. */
    public RecordSearchTool policy(Fetch.Policy p) { this.policy = p == null ? Fetch.Policy.DEFAULT : p; return this; }

    /**
     * Whether a search of a collection with a per-run allowance ({@link RecordSource#perRun()}) may go. The run answers, counting across its
     * workers; the tool asks before anything is sent, so a refused search costs nothing of the month's allowance. Without a run, every search goes.
     */
    private volatile Predicate<RecordSource> allowance = s -> true;
    public RecordSearchTool allowance(Predicate<RecordSource> a) { this.allowance = a == null ? s -> true : a; return this; }
    /** How a refusal begins; the search log does not count it as a search. */
    public static final String USED_UP = "The searches this run may make of ";

    /** The same tool with only the collections of the fields a run belongs to: a family run does not read about npm, a software run not about parish newspapers. */
    public RecordSearchTool forFields(Collection<String> fields) { return new RecordSearchTool(RecordSources.forFields(sources, fields), reader, saidOfThem, RecordSources.forFields(leftOut, fields)).policy(policy).allowance(allowance); }

    /** The same tool with the collections whose years cannot meet the person's ({@code lived}, {born, until}) left out and named; itself when null. */
    public RecordSearchTool forYears(int[] lived) {
        if (lived == null) return this;
        List<RecordSource> kept = RecordSources.forYears(sources, lived);
        List<RecordSource> out = new ArrayList<>(leftOut);
        for (RecordSource s : sources) if (!kept.contains(s)) out.add(s);
        return new RecordSearchTool(kept, reader, saidOfThem, out).policy(policy).allowance(allowance);
    }

    public boolean any() { return !sources.isEmpty(); }

    /**
     * The first thing a query names: its first quoted phrase, else its first word where words are not parts of a name. A field search
     * (inventor:"…") is left whole, and so is a query in an office's own language (ti=bowl and pd within "2012 2022", pd>=2012): its
     * first quoted phrase is a date range, and retried alone it brought back twenty unrelated patents as finds (2026-11-05).
     */
    public static String firstTerm(String query) {
        String q = query.strip();
        if (q.matches("^\\w+:.*")) return q;
        if (q.matches("^\\w+\\s*(=|>=|<=|>|<).*") || q.contains(" within ")) return q;
        Matcher m = Pattern.compile("[\"“]([^\"”]+)[\"”]").matcher(q);
        if (m.find()) return "\"" + m.group(1).strip() + "\"";
        // without quotes, a space ends a name only in a script that writes names without spaces (高峰譲吉 戸籍); "Artur Elis" is one name of two words
        String[] words = q.split("[\\s　]+");
        boolean spaced = words[0].codePoints().allMatch(c -> c < 0x2E80);
        return words.length > 1 && !spaced ? words[0] : q;
    }

    @Override public String name() { return "record_search"; }

    @Override public String description() {
        StringBuilder b = new StringBuilder("Search ONE collection by its id: a web engine cannot see inside these, or buries what is in them. "
                + "A hit gives its date, its link and how to cite it; web_fetch the link to read it. Start with the fewest words, a name alone; add "
                + "one more word only when that finds too much. Write the query the way the collection's own entries are written: their language and "
                + "script, and an older spelling as well. A collection that pages gives its whole page for limit and the next page for page=2, then 3, "
                + "until a page comes back short: that is how everything in a class, under a name or in a span of years is taken in, rather than a first page. SOURCES:");
        for (RecordSource s : sources) {
            b.append("\n- ").append(s.id()).append(": ").append(s.name());
            if (!s.countries().isEmpty()) b.append(" [").append(String.join(", ", s.countries())).append("]");
            if (!s.years().isEmpty()) b.append(" ").append(s.years());
            b.append(". ").append(s.holds()).append('.');
            if (s.hasAllowance()) b.append(" At most ").append(s.perRun()).append(" searches in one run.");
            if (s.pages()) b.append(" Pages, up to ").append(s.pageSize()).append(" a page.");
        }
        if (!leftOut.isEmpty()) b.append("\nLeft out, because their years and this person's do not meet: ").append(String.join("; ", leftOut.stream().map(s -> s.id() + " (" + s.years() + ")").toList())).append('.');
        return b.toString();
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        ArrayNode ids = props.putObject("source").put("type", "string").put("description", "The id of the source to search.").putArray("enum");
        for (RecordSource s : sources) ids.add(s.id());
        props.putObject("query").put("type", "string").put("description", "A name or a few words, in the language of the source. Quotes keep a name together.");
        props.putObject("from_year").put("type", "integer").put("description", "Earliest year, for a source that has dates.");
        props.putObject("to_year").put("type", "integer").put("description", "Latest year.");
        props.putObject("limit").put("type", "integer").put("description", "How many records (default 8; at most 20, or the collection's whole page where it pages).");
        props.putObject("page").put("type", "integer").put("description", "Which page of the same search, counted from 1: 2 for the records after the first page, when it was full. Only a collection that pages has one.");
        p.putArray("required").add("source").add("query");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String id = args.path("source").asText("").strip(), query = args.path("query").asText("").strip();
        if (query.isEmpty()) return "ERROR: empty query";
        RecordSource s = sources.stream().filter(x -> x.id().equalsIgnoreCase(id)).findFirst().orElse(null);
        if (s == null) return "ERROR: no source \"" + id + "\". The sources are: " + String.join(", ", sources.stream().map(RecordSource::id).toList());
        int limit = Math.min(Math.max(args.path("limit").asInt(8), 1), s.pageSize());
        int page = Math.max(1, args.path("page").asInt(1));
        if (page > 1 && !s.pages()) return s.name() + " gives one page only: there is no page " + page + " of it. Narrow the search by its years or its words instead.";
        if (s.hasAllowance() && !allowance.test(s)) {
            List<String> others = sources.stream().filter(o -> o != s && o.kind().equals(s.kind()) && !o.hasAllowance()).map(RecordSource::id).toList();
            return USED_UP + s.id() + " are used: it allows " + s.perRun() + " in one run, because its monthly allowance is small. Search another collection"
                    + (others.isEmpty() ? "" : " (" + String.join(", ", others) + ")") + ", or search the web.";
        }
        try {
            int from = args.path("from_year").asInt(0), to = args.path("to_year").asInt(0);
            RecordSources.Kept first = RecordSources.kept(RecordSources.search(s, query, from, to, limit, page, reader), policy);
            List<RecordSources.Hit> hits = first.hits();   // what the lists leave out is never seen by the run, not even as a citation
            RECORDS_USED.incrementAndGet();
            // Several words together often find nothing in a collection that matches them as one phrase or wants them all on one page
            // (a run searched "高峰譲吉 夫人 子供", "高峰譲吉 戸籍", "高峰譲吉 家族" … nine times and never the name). The name alone is tried
            // before the search counts as one that found nothing, and what is recorded as not found is then the name, which means something.
            String alone = firstTerm(query);
            // every record was left out: not a search that found nothing, so the search log does not write it down as one
            if (hits.isEmpty() && !first.note().isEmpty()) return RecordSources.allLeftOut(s, query, first);
            // a later page that is empty ends the search: the pages before it held everything, and nothing was searched for and not found
            if (hits.isEmpty() && page > 1) return "Page " + page + " of " + s.name() + " for " + query + " is empty: the pages before it held everything this search finds.";
            if (hits.isEmpty() && !alone.equals(query)) {
                RecordSources.Kept second = RecordSources.kept(RecordSources.search(s, alone, from, to, limit, reader), policy);
                if (second.hits().isEmpty() && !second.note().isEmpty()) return RecordSources.allLeftOut(s, alone, second);
                listener.accept(new Searched(s, alone, from, to, second.hits()));
                if (second.hits().isEmpty()) return RecordSources.render(s, alone, second.hits(), saidOfThem, policy);
                return "Nothing in " + s.name() + " has all of these words together: " + query + ". Searched again for " + alone + " alone:\n\n" + RecordSources.render(s, alone, second.hits(), saidOfThem, policy)
                        + (second.note().isEmpty() ? "" : "\n" + second.note());
            }
            listener.accept(new Searched(s, page > 1 ? query + " (page " + page + ")" : query, from, to, hits));
            return (page > 1 ? "Page " + page + " of the search" + (hits.size() < limit ? ", the last: it came back short.\n\n" : ".\n\n") : "") + RecordSources.render(s, query, hits, saidOfThem, policy) + (first.note().isEmpty() ? "" : "\n" + first.note())
                    + Ops.subgroupsSaid(s.id(), hits.stream().map(RecordSources.Hit::title).toList());   // the patent classification's groups come with what stands under them
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            refusals.accept(new Refused(s, query, reason));
            return "ERROR: " + s.name() + " did not answer (" + reason + "). This is not a search that found nothing: try once more or use another source.";
        }
    }
}
