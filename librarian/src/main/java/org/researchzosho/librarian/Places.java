package org.researchzosho.librarian;

import org.researchzosho.tools.ArchiveSearchTool;
import org.researchzosho.tools.BookSearchTool;
import org.researchzosho.tools.ChannelUploadsTool;
import org.researchzosho.tools.ChannelsLikeTool;
import org.researchzosho.tools.ContentPolicy;
import org.researchzosho.tools.CustomSearchTool;
import org.researchzosho.tools.ExaSearchTool;
import org.researchzosho.tools.ForumSearchTool;
import org.researchzosho.tools.NdlFulltextTool;
import org.researchzosho.tools.OldNewspapersTool;
import org.researchzosho.tools.PodcastSearchTool;
import org.researchzosho.tools.ProductSearchTool;
import org.researchzosho.tools.PubMed;
import org.researchzosho.tools.PubMedSearchTool;
import org.researchzosho.tools.RecordSearchTool;
import org.researchzosho.tools.ScholarSearchTool;
import org.researchzosho.tools.SearchTally;
import org.researchzosho.tools.SemanticScholarTool;
import org.researchzosho.tools.SoftwareArchiveTool;
import org.researchzosho.tools.TavilySearchTool;
import org.researchzosho.tools.Tool;
import org.researchzosho.tools.VideoDetailsTool;
import org.researchzosho.tools.VideoSearchTool;
import org.researchzosho.tools.VideoSitesTool;
import org.researchzosho.tools.WebFetchTool;
import org.researchzosho.tools.WebSearchTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The places a run can look, one of each: the sources of research and the tools that look there. The four fields that were
 * sources wearing a field's coat, books, podcasts, videos and software, are here by their {@link Profile#sourceName}; the library's own list of where its reports came from is {@link Sources}, another thing; the rest are the
 * core's places. The tools are the ones {@link Researcher#webTools} gives every run today, place by place, so the brain's one search
 * with a place to look can be built from this list.
 *
 * <p>The places of 0.7.0 have their words in the table's one pattern, what it answers · when it pays over the others · what it costs,
 * with one literal call: pubmed and semantic-scholar, free; exa and tavily, paid, on the table only with a key and while the month's limit
 * allows; and the keeper's own search service, under the name the keeper gives it.
 */
public final class Places {

    private Places() { }

    record Spec(String name, String words, List<String> keys, BiFunction<String, ContentPolicy, List<Tool>> tools, BooleanSupplier available, Supplier<String> unavailable) { }

    private static ContentPolicy p(ContentPolicy policy) { return policy == null ? ContentPolicy.defaults() : policy; }

    static final List<Spec> SPECS = List.of(
            new Spec("web", "The web through a search engine, then the page: the place for most questions, and the one to start from.",
                    List.of("RESEARCHZOSHO_BRAVE_KEY", "RESEARCHZOSHO_SERPER_KEY", "RESEARCHZOSHO_SERPAPI_KEY", "RESEARCHZOSHO_FIRECRAWL_KEY"),
                    (focus, policy) -> List.of(new WebSearchTool().focus(focus).policy(p(policy).fetchPolicy()), new WebFetchTool().focus(focus).policy(p(policy))), () -> true, () -> ""),
            new Spec("scholar", "Papers, books and chapters by DOI in Crossref and OpenAlex: the primary literature a web engine ranks low. It pays whenever the question has a literature.",
                    List.of(), (focus, policy) -> List.of(new ScholarSearchTool().policy(p(policy).fetchPolicy())), () -> true, () -> ""),
            new Spec("pubmed", "Articles in medicine and the life sciences from PubMed, best match first, each with its journal, year, kind and abstract, and the free full text of PubMed Central's open articles"
                    + " · for a disease, a drug, a treatment, the body or a living thing, before the web; full_text=true searches inside the open articles · free, a few searches a second. "
                    + "Example: {\"where\": \"pubmed\", \"query\": \"metformin gestational diabetes\"}",
                    List.of(PubMed.KEY), (focus, policy) -> List.of(new PubMedSearchTool().policy(p(policy).fetchPolicy())), () -> true, () -> ""),
            new Spec("semantic-scholar", "Papers in every field from Semantic Scholar, each with its venue, year, how often it is cited, its abstract and a free PDF when there is one"
                    + " · when the question has a literature outside medicine, or it matters which papers others cite · free; busy at times without a key. "
                    + "Example: {\"where\": \"semantic-scholar\", \"query\": \"retrieval augmented generation hallucination\"}",
                    List.of(SemanticScholarTool.KEY), (focus, policy) -> List.of(new SemanticScholarTool().policy(p(policy).fetchPolicy())), () -> true, () -> ""),
            new Spec("exa", "The web searched by meaning through Exa, each page with the passages that match"
                    + " · when a web search's words find pages about the words but not the idea, or for pages like a description · paid, about 0.7 cents a search, counted against the month's limit. "
                    + "Example: {\"where\": \"exa\", \"query\": \"essays on why small teams ship faster\"}",
                    List.of(ExaSearchTool.KEY), (focus, policy) -> ExaSearchTool.open() ? List.of(new ExaSearchTool().policy(p(policy).fetchPolicy())) : List.of(),
                    ExaSearchTool::open, ExaSearchTool::closedBecause),
            new Spec("tavily", "The web through Tavily, each page with the passages that answer the query"
                    + " · when a web search's snippets are too short to judge the pages by · free for 1,000 searches a month, then paid, counted against the month's limit. "
                    + "Example: {\"where\": \"tavily\", \"query\": \"heat pump noise limits bylaw\"}",
                    List.of(TavilySearchTool.KEY), (focus, policy) -> TavilySearchTool.open() ? List.of(new TavilySearchTool().policy(p(policy).fetchPolicy())) : List.of(),
                    TavilySearchTool::open, TavilySearchTool::closedBecause),
            new Spec("records", "Collections of records searched one at a time: newspapers, patents, directories, local histories, archive catalogues, the registers of companies and people, in the languages of the question. It pays for a name, a place or a date that a register holds.",
                    List.of("RESEARCHZOSHO_TROVE_KEY", "RESEARCHZOSHO_DIGITALNZ_KEY", "RESEARCHZOSHO_USAJOBS_KEY", "RESEARCHZOSHO_COMPANIES_HOUSE_KEY", "RESEARCHZOSHO_GBIZINFO_TOKEN"),
                    (focus, policy) -> {
                        List<String> languages = new ArrayList<>(List.of(Lanes.ownLanguage(focus == null ? "" : focus)));
                        for (Lanes.Lane lane : Lanes.detect(focus, null)) languages.add(lane.code());
                        RecordSearchTool records = new RecordSearchTool(languages, focus).policy(p(policy).fetchPolicy());
                        return Researcher.RECORDS && records.any() ? List.of(records) : List.of();
                    }, () -> Researcher.RECORDS, () -> "the record sources are turned off (RESEARCHZOSHO_RECORDS=off)"),
            new Spec("archives", "Heritage items in the archives: Europeana, DPLA, the national libraries' catalogues. It pays for an old thing, a picture, a manuscript.",
                    List.of("RESEARCHZOSHO_EUROPEANA_KEY", "RESEARCHZOSHO_DPLA_KEY"), (focus, policy) -> List.of(new ArchiveSearchTool().policy(p(policy).fetchPolicy())), () -> true, () -> ""),
            new Spec("old-newspapers", "Digitised newspapers by date and place: what was printed at the time. It pays for an event, a person or a business before the web.",
                    List.of("RESEARCHZOSHO_TROVE_KEY", "RESEARCHZOSHO_DIGITALNZ_KEY"), (focus, policy) -> List.of(new OldNewspapersTool().policy(p(policy).fetchPolicy())), () -> true, () -> ""),
            new Spec("japanese-fulltext", "The National Diet Library's full text: Japanese books and papers searched by their words. It pays for a Japanese question with a literature.",
                    List.of(), (focus, policy) -> List.of(new NdlFulltextTool()), () -> true, () -> ""),
            new Spec("forums", "What people say in the discussion threads: Reddit through the archive, the Stack Exchange sites. Owners' words and workarounds, never facts.",
                    List.of("RESEARCHZOSHO_REDDIT_CLIENT_ID", "RESEARCHZOSHO_REDDIT_CLIENT_SECRET", "RESEARCHZOSHO_STACKEXCHANGE_KEY"), (focus, policy) -> List.of(new ForumSearchTool()), () -> true, () -> ""),
            new Spec("videos", "YouTube: videos and channels found by fit, a video's page and transcript, a channel's uploads, channels like one. It pays when the thing was shown or said on video.",
                    List.of(), (focus, policy) -> Video.installed() ? List.of(new VideoSearchTool(), new ChannelUploadsTool(), new VideoDetailsTool().policy(p(policy)), new ChannelsLikeTool()) : List.of(),
                    Video::installed, () -> "the video helper is not installed; researchzosho video install puts it in"),
            new Spec("video-sites", "The other video sites, by their own search pages. It pays when YouTube does not have the thing.",
                    List.of(), (focus, policy) -> List.of(new VideoSitesTool()), () -> true, () -> ""),
            new Spec("podcasts", "The podcasts: shows by fit through the open directory, episodes by their own words with the time. It pays when the person spoke on a show.",
                    List.of("RESEARCHZOSHO_PODCASTINDEX_KEY", "RESEARCHZOSHO_PODCASTINDEX_SECRET"), (focus, policy) -> List.of(new PodcastSearchTool().policy(p(policy))), () -> true, () -> ""),
            new Spec("shops", "The shops and the catalogues: what is for sale now in the person's market, with prices and times, behind the person's keys.",
                    List.of("RESEARCHZOSHO_EBAY_CLIENT_ID", "RESEARCHZOSHO_EBAY_CLIENT_SECRET", "RESEARCHZOSHO_ETSY_KEY", "RESEARCHZOSHO_RAKUTEN_APP_ID", "RESEARCHZOSHO_RAKUTEN_ACCESS_KEY", "RESEARCHZOSHO_YAHOO_JP_CLIENT_ID"),
                    (focus, policy) -> List.of(new ProductSearchTool()), () -> true, () -> ""),
            new Spec("books", "The book catalogues: real books by subject, lists and syllabi, not sales; only a book a catalogue returns may be named.",
                    List.of("RESEARCHZOSHO_CINII_APPID"), (focus, policy) -> List.of(new BookSearchTool().policy(p(policy))), () -> true, () -> ""),
            new Spec("software", "The software archives: repositories and packages by best match and most recent push, judged by activity and licence.",
                    List.of(), (focus, policy) -> List.of(new SoftwareArchiveTool()), () -> true, () -> ""),
            new Spec("shelves", "What the library holds: the person's own documents and what earlier research established. Searched before the web; the run's own shelf_search serves it.",
                    List.of(), (focus, policy) -> List.of(), () -> true, () -> ""));

    static final class SpecSource implements Source {
        private final Spec spec; private final List<Profile> profiles;
        SpecSource(Spec spec, List<Profile> profiles) { this.spec = spec; this.profiles = List.copyOf(profiles); }
        @Override public String name() { return spec.name(); }
        @Override public String words() { return spec.words(); }
        @Override public List<Tool> tools(String focus, ContentPolicy policy) { return spec.tools().apply(focus, policy); }
        @Override public List<String> keys() { return spec.keys(); }
        @Override public boolean available() { return spec.available().getAsBoolean(); }
        @Override public String unavailableBecause() { return available() ? "" : spec.unavailable().get(); }
        @Override public List<Profile> profiles() { return profiles; }
    }

    /** The name the keeper's own search service has on the table: its setting made one word, "custom" when unset or when it would take another place's name. */
    public static String customName() {
        String n = CustomSearchTool.rawName();
        for (Spec s : SPECS) if (s.name().equals(n)) return "custom";
        return n;
    }

    /** Whether a name is a place of the build's own, which the keeper's search service cannot take. */
    public static boolean builtIn(String name) {
        String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        for (Spec s : SPECS) if (s.name().equals(n)) return true;
        return false;
    }

    /** The keeper's own search service as a place: its name is read live, since the keeper may rename it while the library runs. */
    static final class CustomPlace implements Source {
        @Override public String name() { return customName(); }
        @Override public String words() {
            String n = customName();
            return "The search service the keeper set up for this library, its hits with their text · when the question is about what that service holds"
                    + " · whatever that service charges; the month's limit when one is set. Example: {\"where\": \"" + n + "\", \"query\": \"onboarding checklist\"}";
        }
        @Override public List<Tool> tools(String focus, ContentPolicy policy) { return CustomSearchTool.open() ? List.of(new CustomSearchTool(customName()).policy(p(policy).fetchPolicy())) : List.of(); }
        @Override public List<String> keys() { return List.of(CustomSearchTool.KEY); }
        @Override public boolean available() { return CustomSearchTool.open(); }
        @Override public String unavailableBecause() {
            if (!CustomSearchTool.set()) return "No search service of the library's own is set: the library's keeper sets its address as custom_search_url in the settings.";
            return SearchTally.reached(CustomSearchTool.TALLY) ? SearchTally.usedUp(CustomSearchTool.TALLY, customName()) : "";
        }
    }

    private static volatile List<Source> known;

    /** One of each source this build knows, the core's places and the fields that are sources, and the keeper's own search service. */
    public static List<Source> known() {
        List<Source> k = known;
        if (k == null) {
            Map<String, List<Profile>> backing = new LinkedHashMap<>();
            for (Profile p : Profiles.known()) if (!p.sourceName().isBlank()) backing.computeIfAbsent(p.sourceName(), x -> new ArrayList<>()).add(p);
            List<Source> out = new ArrayList<>();
            for (Spec s : SPECS) out.add(new SpecSource(s, backing.getOrDefault(s.name(), List.of())));
            out.add(new CustomPlace());
            known = k = List.copyOf(out);
        }
        return k;
    }

    public static Source named(String name) {
        for (Source s : known()) if (s.name().equalsIgnoreCase(name == null ? "" : name.strip())) return s;
        return null;
    }

    /** The names of the places, for a search's choice. */
    public static List<String> names() { return known().stream().map(Source::name).toList(); }
}
