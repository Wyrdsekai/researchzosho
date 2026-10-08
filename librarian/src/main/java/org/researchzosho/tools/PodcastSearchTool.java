package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.Acquisitions;
import org.researchzosho.librarian.Fence;
import org.researchzosho.librarian.LibraryStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code podcast_search}: shows, a show's episodes, an episode's transcript, the episodes a person appeared on, and shows like a given
 * one (by shared categories and guests) — one tool with a {@code kind}. The feed is the source of truth; Apple's search needs no key; the
 * Podcast Index adds the open directory, search by person and trending by category when the library has a key. Every lookup is one
 * request, capped per run; the index is never walked.
 */
public final class PodcastSearchTool implements Tool {

    static final int MOST = 20, MOST_CALLS = 24, TRANSCRIPT_CHARS = 7_000;
    private final AtomicInteger calls = new AtomicInteger();
    private final Podcasts.Budget budget = new Podcasts.Budget();
    private volatile ContentPolicy policy = ContentPolicy.defaults();

    public PodcastSearchTool policy(ContentPolicy p) { this.policy = p == null ? ContentPolicy.defaults() : p; return this; }

    @Override public String name() { return "podcast_search"; }

    @Override public String description() {
        return "Podcasts: what a person said in their own words on a show they host or visited, with the time they said it — the source to read for a host's "
                + "or guest's views, before articles about them. kind=shows: shows by words (Apple's directory, and the Podcast Index when the library has a key), each with its feed address. "
                + "kind=episodes: a show's episodes from its feed (feed = the feed address; query narrows by words), with whether a transcript is published. "
                + "kind=transcript: what is said in one episode, with times (feed + episode = its title words, guid or audio address; from/to = seconds): the "
                + "show's own transcript, or the library's transcription of the audio within this run's budget. kind=people: the episodes a person appeared "
                + "on (needs the key). kind=similar: shows like one (feed), by shared categories and guests. kind=browse: the directory by category and "
                + "language, most popular first (from the Podcast Index file on this machine, when downloaded). Cite an episode by its page and the time.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        ObjectNode kind = props.putObject("kind").put("type", "string").put("description", "shows | episodes | transcript | people | similar");
        for (String k : List.of("shows", "episodes", "transcript", "people", "similar", "browse")) kind.withArray("enum").add(k);
        props.putObject("language").put("type", "string").put("description", "shows, browse: a language code (ja, en) to narrow to; transcript: the language spoken");
        props.putObject("category").put("type", "string").put("description", "shows, browse: a category of the directory (history, technology, education…)");
        props.putObject("active").put("type", "boolean").put("description", "shows, browse: only shows with an episode in the last year");
        props.putObject("query").put("type", "string").put("description", "words (shows, episodes), a person's name (people)");
        props.putObject("feed").put("type", "string").put("description", "a show's feed address (episodes, transcript, similar)");
        props.putObject("episode").put("type", "string").put("description", "transcript: the episode, by words of its title, its guid, or its audio address");
        props.putObject("from").put("type", "integer").put("description", "transcript: from this second (default 0)");
        props.putObject("to").put("type", "integer").put("description", "transcript: to this second (default: the end, as far as fits)");
        props.putObject("limit").put("type", "integer").put("description", "how many rows, up to " + MOST + " (default 10)");
        p.putArray("required").add("kind");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String kind = args.path("kind").asText("").strip().toLowerCase(), query = args.path("query").asText("").strip(), feed = args.path("feed").asText("").strip();
        int limit = Math.max(1, Math.min(MOST, args.path("limit").asInt(10)));
        if (calls.incrementAndGet() > MOST_CALLS) return "ERROR: this run has made its " + MOST_CALLS + " podcast lookups; write with what was found.";
        StringBuilder sb = new StringBuilder();
        String keyNote = Podcasts.indexConfigured() ? "" : " The Podcast Index (the open directory, search by person, trending by category) answers when the library has a key: researchzosho settings set podcastindex_key … and podcastindex_secret … (api.podcastindex.org/signup, free).";
        switch (kind) {
            case "shows" -> {
                if (query.isEmpty()) return "ERROR: query is empty";
                String language = args.path("language").asText("").strip(), category = args.path("category").asText("").strip();
                boolean active = args.path("active").asBoolean(false);
                // the Podcast Index's file on this machine first, by word: the whole directory, no call, no limit; then the live directories
                List<Podcasts.Show> local = PodcastIndexLocal.search(query, language, category, active, limit);
                List<Podcasts.Show> apple = Podcasts.apple(query, limit), index = Podcasts.indexSearch(query, limit);
                Map<String, Podcasts.Show> all = Podcasts.byFeed(local); for (var e : Podcasts.byFeed(index).entrySet()) all.putIfAbsent(e.getKey(), e.getValue()); for (var e : Podcasts.byFeed(apple).entrySet()) all.putIfAbsent(e.getKey(), e.getValue());
                if (all.isEmpty()) return (apple == null && index == null && local == null ? "ERROR: no podcast directory answered. Try once more." : "no shows found for: " + query) + keyNote;
                sb.append("shows for \"").append(query).append("\"").append(language.isEmpty() ? "" : " in " + language).append(category.isEmpty() ? "" : " under " + category).append(":\n");
                int n = 0;
                for (Podcasts.Show s : all.values()) { if (n >= limit) break; show(sb, ++n, s); }
                if (!keyNote.isEmpty()) sb.append(keyNote.strip()).append('\n');
                sb.append("podcast_search kind=episodes with a feed address lists a show's episodes; kind=transcript reads one.");
            }
            case "episodes" -> {
                if (feed.isEmpty()) return "ERROR: episodes needs feed, the show's feed address (podcast_search kind=shows gives it)";
                var fe = Podcasts.feed(feed, Math.max(limit * 4, 40));
                if (fe == null) return "ERROR: the feed at " + feed + " did not answer or is not a podcast feed.";
                List<Podcasts.Episode> eps = new ArrayList<>();
                for (Podcasts.Episode e : fe.getValue()) if (query.isEmpty() || matches(e, query)) eps.add(e);
                sb.append("episodes of ").append(fe.getKey().title()).append(query.isEmpty() ? "" : " with \"" + query + "\"").append(" (").append(fe.getKey().episodes()).append(" in the feed, newest first):\n");
                int n = 0;
                LibraryStore store = store();
                for (Podcasts.Episode e : eps) { if (n >= limit) break; episode(sb, ++n, e, store); }
                if (n == 0) sb.append("no episode matches").append(query.isEmpty() ? "" : " \"" + query + "\"").append(" among the newest ").append(fe.getValue().size()).append(".\n");
                sb.append("podcast_search kind=transcript with this feed and an episode reads what is said.");
            }
            case "transcript" -> {
                String which = args.path("episode").asText("").strip();
                if (feed.isEmpty() || which.isEmpty()) return "ERROR: transcript needs feed and episode (its title words, guid, or audio address)";
                var fe = Podcasts.feed(feed, 150);
                if (fe == null) return "ERROR: the feed at " + feed + " did not answer or is not a podcast feed.";
                Podcasts.Episode ep = find(fe.getValue(), which);
                if (ep == null) { var whole = Podcasts.feed(feed, 5000); if (whole != null) { fe = whole; ep = find(fe.getValue(), which); } }   // an old episode: the whole feed (a show with years of episodes)
                if (ep == null) return "no episode of " + fe.getKey().title() + " matches \"" + which + "\" among its " + fe.getValue().size() + " episodes; podcast_search kind=episodes lists the newest, with query narrowing them.";
                boolean exact = which.equals(ep.guid()) || which.equals(ep.audioUrl()) || which.equals(ep.page()) || ep.title().equalsIgnoreCase(which) || matches(new Podcasts.Episode(ep.title(), "", "", "", "", "", 0, "", "", "", List.of(), List.of()), which);
                StringBuilder why = new StringBuilder();
                Podcasts.Transcript t = Podcasts.transcript(store(), ep, args.path("language").asText("").strip(), budget, why);
                if (t == null) return "no transcript for \"" + ep.title() + "\": " + why + ". Budget left this run: " + budget.left() + ".";
                int from = Math.max(0, args.path("from").asInt(0)), to = Math.max(0, args.path("to").asInt(0));
                sb.append(fe.getKey().title()).append(" — ").append(ep.title()).append(ep.date().isEmpty() ? "" : " (" + ep.date() + ")").append(ep.seconds() > 0 ? ", " + ep.seconds() / 60 + " min" : "")
                  .append(exact ? "" : "\n(the closest title to \"" + which + "\" among the newest " + fe.getValue().size() + "; podcast_search kind=episodes lists them if this is not the one)")
                  .append("\ntranscript: ").append(t.how()).append(t.how().startsWith("transcribed") ? "; machine-made, read it as such" : "")
                  .append("\ncite: ").append(ep.cite()).append(ep.page().isEmpty() && !fe.getKey().site().isEmpty() ? " (the audio itself; the show's site is " + fe.getKey().site() + ")" : "").append(" at the time shown\n");
                if (!ep.people().isEmpty()) sb.append("people: ").append(String.join(", ", ep.people())).append('\n');
                sb.append(VideoText.transcript(t.lines(), from, to, TRANSCRIPT_CHARS));
            }
            case "people" -> {
                if (query.isEmpty()) return "ERROR: people needs query, the person's name";
                List<Podcasts.Episode> eps = Podcasts.indexByPerson(query, limit);
                if (eps == null) return Podcasts.indexConfigured() ? "ERROR: the Podcast Index did not answer. Try once more." : "Search by person needs the Podcast Index key." + keyNote;
                if (eps.isEmpty()) return "no episodes name \"" + query + "\" in the Podcast Index.";
                sb.append("episodes with ").append(query).append(":\n");
                int n = 0;
                LibraryStore store = store();
                for (Podcasts.Episode e : eps) { if (n >= limit) break; episode(sb, ++n, e, store); }
            }
            case "similar" -> {
                if (feed.isEmpty()) return "ERROR: similar needs feed, the show's feed address";
                var fe = Podcasts.feed(feed, 12);
                if (fe == null) return "ERROR: the feed at " + feed + " did not answer or is not a podcast feed.";
                Podcasts.Show self = Podcasts.indexFeed(feed);
                List<String> cats = new ArrayList<>(self != null && !self.categories().isEmpty() ? self.categories() : fe.getKey().categories());
                // the show's people: its hosts (their other shows are kin) and its guests (the shows they also appeared on)
                Map<String, String> role = new LinkedHashMap<>();
                for (Podcasts.Episode e : fe.getValue()) for (String p : e.people()) {
                    String name = p.replaceAll(" \\(.*\\)$", "");
                    if (name.equalsIgnoreCase(fe.getKey().author())) continue;
                    role.putIfAbsent(name, p.toLowerCase(Locale.ROOT).contains("(host") ? "host" : "guest");
                }
                Map<String, Map<String, Integer>> why = new LinkedHashMap<>(); Map<String, Podcasts.Show> found = new LinkedHashMap<>();
                int budgetCalls = 6;
                for (String c : cats) {
                    if (budgetCalls-- <= 0) break;
                    List<Podcasts.Show> ss = Podcasts.indexTrending(c, 10); if (ss == null) ss = Podcasts.apple(c + " podcast", 10); if (ss == null) continue;
                    for (Podcasts.Show s : ss) { if (same(s, feed)) continue; found.putIfAbsent(s.feedUrl(), s); why.computeIfAbsent(s.feedUrl(), k -> new LinkedHashMap<>()).merge("category " + c, 1, Integer::sum); }
                }
                List<String> people = new ArrayList<>(role.keySet());
                for (int i = 0; i < people.size() && i < 3 && budgetCalls-- > 0; i++) {
                    String name = people.get(i);
                    List<Podcasts.Episode> eps = Podcasts.indexByPerson(name, 10); if (eps == null) break;
                    for (Podcasts.Episode e : eps) {
                        if (e.feedUrl().isEmpty() || same(e.feedUrl(), feed)) continue;
                        found.putIfAbsent(e.feedUrl(), new Podcasts.Show(e.feedTitle(), "", e.feedUrl(), "", "", List.of(), 0, e.date(), "Podcast Index"));
                        why.computeIfAbsent(e.feedUrl(), k -> new LinkedHashMap<>()).merge((role.get(name).equals("host") ? "same host " : "shared guest ") + name, 1, Integer::sum);
                    }
                }
                if (found.isEmpty()) return "nothing found like " + fe.getKey().title() + (cats.isEmpty() && role.isEmpty() ? ": the feed names no categories or people to go by" : "") + "." + keyNote;
                List<String> order = new ArrayList<>(found.keySet());
                order.sort((a, b) -> { int d = Integer.compare(why.get(b).size(), why.get(a).size()); return d != 0 ? d : Integer.compare(why.get(b).values().stream().mapToInt(Integer::intValue).sum(), why.get(a).values().stream().mapToInt(Integer::intValue).sum()); });
                sb.append("shows like ").append(fe.getKey().title()).append(cats.isEmpty() ? "" : " (categories: " + String.join(", ", cats) + ")").append(", by shared categories, hosts and guests (people by name: the same name may be another person):\n");
                int n = 0;
                for (String k : order) {
                    if (n >= limit) break;
                    show(sb, ++n, found.get(k));
                    List<String> reasons = new ArrayList<>();
                    for (var r : why.get(k).entrySet()) reasons.add(r.getKey() + (r.getValue() > 1 && !r.getKey().startsWith("category") ? " (" + r.getValue() + " episodes)" : ""));
                    sb.append("   why: ").append(String.join("; ", reasons)).append('\n');
                }
                if (!keyNote.isEmpty()) sb.append(keyNote.strip()).append('\n');
            }
            case "browse" -> {
                String language = args.path("language").asText("").strip(), category = args.path("category").asText("").strip();
                boolean active = args.path("active").asBoolean(false);
                if (language.isEmpty() && category.isEmpty()) return "ERROR: browse needs language, category, or both";
                List<Podcasts.Show> shows = PodcastIndexLocal.browse(language, category, active, limit);
                if (shows == null) return "Browsing by category and language needs the Podcast Index's file on this machine: researchzosho podcast index download fetches it (1.8 GB, refreshed weekly).";
                if (shows.isEmpty()) return "no shows" + (language.isEmpty() ? "" : " in " + language) + (category.isEmpty() ? "" : " under " + category) + (active ? " active this year" : "") + " in the Podcast Index's file. Categories are the directory's own words: history, technology, education, society, arts, business, comedy, news, sports, science, health…";
                sb.append("the directory").append(language.isEmpty() ? "" : " in " + language).append(category.isEmpty() ? "" : " under " + category).append(active ? ", active this year" : "").append(", the most popular and recent first (Podcast Index file of ").append(PodcastIndexLocal.fetchedAt() == null ? "?" : PodcastIndexLocal.fetchedAt().toString().replaceAll("T.*", "")).append("):\n");
                int n = 0;
                for (Podcasts.Show s : shows) show(sb, ++n, s);
            }
            default -> { return "ERROR: kind must be one of shows, episodes, transcript, people, similar, browse"; }
        }
        return Fence.wrap("PODCAST RESULTS", sb.toString().strip()) + "\n" + Fence.rule("PODCAST RESULTS");
    }

    private LibraryStore store() {
        LibraryStore s = policy.store();
        if (s != null) return s;
        try { return Acquisitions.libraryExists() ? LibraryStore.open() : null; } catch (Exception e) { return null; }
    }

    static boolean same(Podcasts.Show s, String feed) { return same(s.feedUrl(), feed); }
    static boolean same(String a, String b) { return a.replaceFirst("^https?://", "").replaceAll("/+$", "").equalsIgnoreCase(b.replaceFirst("^https?://", "").replaceAll("/+$", "")); }

    static boolean matches(Podcasts.Episode e, String query) {
        String hay = (e.title() + " " + e.description()).toLowerCase(Locale.ROOT);
        for (String w : query.toLowerCase(Locale.ROOT).split("\\s+")) if (!w.isEmpty() && !hay.contains(w)) return false;
        return true;
    }

    /** The episode meant: by guid, audio or page address, the exact title, every word of the title, else the title holding most of the words (at least half). */
    static Podcasts.Episode find(List<Podcasts.Episode> eps, String which) {
        for (Podcasts.Episode e : eps) if (which.equals(e.guid()) || which.equals(e.audioUrl()) || which.equals(e.page())) return e;
        for (Podcasts.Episode e : eps) if (e.title().equalsIgnoreCase(which)) return e;
        for (Podcasts.Episode e : eps) if (matches(new Podcasts.Episode(e.title(), "", "", "", "", "", 0, "", "", "", List.of(), List.of()), which)) return e;
        String[] words = which.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s]", " ").split("\\s+");
        Podcasts.Episode best = null; int bestHits = 0;
        for (Podcasts.Episode e : eps) {
            String t = " " + e.title().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s]", " ") + " ";
            int hits = 0; for (String w : words) if (!w.isEmpty() && t.contains(" " + w + " ")) hits++;
            if (hits > bestHits) { bestHits = hits; best = e; }
        }
        int asked = 0; for (String w : words) if (!w.isEmpty()) asked++;
        return best != null && bestHits * 2 >= asked ? best : null;
    }

    static void show(StringBuilder sb, int n, Podcasts.Show s) {
        sb.append(n).append(". ").append(s.title()).append(s.author().isEmpty() ? "" : " — " + s.author()).append(s.episodes() > 0 ? " (" + s.episodes() + " episodes" + (s.lastEpisode().isEmpty() ? "" : ", last " + s.lastEpisode()) + ")" : s.lastEpisode().isEmpty() ? "" : " (last " + s.lastEpisode() + ")")
          .append(s.language().isEmpty() ? "" : "  " + s.language()).append(s.categories().isEmpty() ? "" : "  [" + String.join(", ", s.categories()) + "]").append("  [").append(s.source()).append("]\n")
          .append("   feed: ").append(s.feedUrl()).append(s.site().isEmpty() ? "" : "   site: " + s.site()).append('\n');
    }

    static void episode(StringBuilder sb, int n, Podcasts.Episode e, LibraryStore store) {
        boolean published = !e.transcripts().isEmpty(), saved = Podcasts.saved(store, e);
        sb.append(n).append(". ").append(e.date().isEmpty() ? "" : e.date() + "  ").append(e.title()).append(e.feedTitle().isEmpty() ? "" : "  — " + e.feedTitle()).append(e.seconds() > 0 ? "  " + e.seconds() / 60 + " min" : "")
          .append("  transcript: ").append(saved ? "in the library" : published ? "published" : "none published").append('\n');
        if (!e.people().isEmpty()) sb.append("   people: ").append(String.join(", ", e.people())).append('\n');
        if (!e.description().isEmpty()) sb.append("   ").append(ArchiveSearchTool.cut(e.description(), 200)).append('\n');
        sb.append("   ").append(e.cite()).append(e.guid().isEmpty() ? "" : "   guid: " + e.guid()).append('\n');
    }
}
