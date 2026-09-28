package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import java.util.concurrent.atomic.AtomicInteger;
import org.researchzosho.Stopping;
import org.researchzosho.Version;
import org.researchzosho.librarian.Fence;
import org.researchzosho.librarian.SourceRules;
import org.researchzosho.librarian.SourceTier;
/**
 * The scholarly literature, with no key and no install: Crossref (the DOI registry) and OpenAlex (a scholarly index).
 * Measured 2026-09-09 from a home box on the same queries as the web engines: both answered in under a second with
 * the primary papers (the 1978 "Ban on Lead-Containing Paint" paper, the Lancet's MMR retraction notices,
 * melatonin randomized trials) that a web engine ranks low or not at all. Every Crossref hit is a DOI, which the
 * library resolves for its edition and treats as a primary source. This backs {@code scholar_search}, offered to
 * every research worker, and joins Wikipedia in {@code web_search}'s built-in fallback.
 */
public final class ScholarSearch {

    private ScholarSearch() { }

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    public static final AtomicInteger SCHOLAR_USED = new AtomicInteger();
    static final String UA = "ResearchZosho/" + Version.string() + " (a research library; https://researchzosho.org; mailto:support@researchzosho.org)";

    /** One work: the title, where to read it (a DOI URL when there is one), and a line of context. */
    public record Row(String title, String url, String snippet, String doi) { }

    /** Both sources, merged, one row per work (by DOI, else by URL); Crossref first. Never throws; empty when nothing answers. */
    public static List<Row> merged(String query, int limit) { return answer(query, limit).rows(); }

    /**
     * The merged rows, how many of the two sources answered at all (none answering is not a search that found nothing), and which:
     * "Crossref and OpenAlex", "Crossref", "OpenAlex", or "".
     */
    public record Answer(List<Row> rows, int answered, String by) { }

    public static Answer answer(String query, int limit) {
        List<Row> c = fromCrossref(query, limit), o = fromOpenAlex(query, limit);
        Map<String, Row> byKey = new LinkedHashMap<>();
        if (c != null) for (Row r : c) byKey.putIfAbsent(key(r), r);
        if (o != null) for (Row r : o) byKey.putIfAbsent(key(r), r);
        List<Row> out = new ArrayList<>(byKey.values());
        String by = c != null && o != null ? "Crossref and OpenAlex" : c != null ? "Crossref" : o != null ? "OpenAlex" : "";
        return new Answer(out.size() > limit ? out.subList(0, limit) : out, (c == null ? 0 : 1) + (o == null ? 0 : 1), by);
    }

    /** What {@code researchzosho search papers} says to a person, and whether either source answered (false: the command failed). */
    public record ForAPerson(String text, boolean answered) { }

    public static ForAPerson forAPerson(String query, int limit) {
        Answer a = answer(query, limit);
        if (a.answered() == 0) return new ForAPerson("Neither Crossref nor OpenAlex answered, so nothing was searched. Check that this computer can reach "
                + "api.crossref.org and api.openalex.org, then give the command again.", false);
        String one = a.answered() == 1 ? "Only " + a.by() + " answered; " + (a.by().equals("Crossref") ? "OpenAlex" : "Crossref") + " did not, so the list may be short.\n" : "";
        if (a.rows().isEmpty()) return new ForAPerson(one + "No papers or books were found for \"" + query + "\". Try other words, such as the words a title would use.", true);
        return new ForAPerson(one + render(query, " (scholarly literature: " + a.by() + ")", a.rows(), limit), true);
    }

    static String key(Row r) {
        if (r.doi() != null && !r.doi().isBlank()) return "doi:" + r.doi().toLowerCase(Locale.ROOT);
        return "url:" + r.url().replaceFirst("^https?://(www\\.)?", "").replaceAll("/+$", "").toLowerCase(Locale.ROOT);
    }

    public static List<Row> crossref(String query, int limit) { List<Row> r = fromCrossref(query, limit); return r == null ? List.of() : r; }

    public static List<Row> openalex(String query, int limit) { List<Row> r = fromOpenAlex(query, limit); return r == null ? List.of() : r; }

    /** Where a test sends the two sources instead; null for the real ones. */
    static volatile Function<String, String> reader = null;

    /** Crossref's rows; null when it did not answer. */
    static List<Row> fromCrossref(String query, int limit) {
        try {
            String body = get("https://api.crossref.org/works?rows=" + Math.min(limit, 20) + "&select=DOI,URL,title,container-title,issued,author,type&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
            return body == null ? null : parseCrossref(body);
        } catch (Stopping.Requested stop) { throw stop; }
        catch (Exception e) { return null; }
    }

    /** OpenAlex's rows; null when it did not answer. */
    static List<Row> fromOpenAlex(String query, int limit) {
        try {
            String body = get("https://api.openalex.org/works?per-page=" + Math.min(limit, 20) + "&mailto=support@researchzosho.org&search=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
            return body == null ? null : parseOpenAlex(body);
        } catch (Stopping.Requested stop) { throw stop; }
        catch (Exception e) { return null; }
    }

    private static String get(String url) throws Exception {
        Function<String, String> test = reader;
        if (test != null) return test.apply(url);
        HttpResponse<String> r = Stopping.send(HTTP, HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", UA).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString(), Duration.ofSeconds(20), "the scholarly index at " + URI.create(url).getHost());
        return r.statusCode() == 200 ? r.body() : null;
    }

    /** Crossref's {@code message.items[]}: title[], URL, DOI, container-title[], issued.date-parts, author[]. */
    static List<Row> parseCrossref(String json) {
        List<Row> out = new ArrayList<>();
        try {
            for (JsonNode w : M.readTree(json).path("message").path("items")) {
                String title = w.path("title").path(0).asText("").replaceAll("\\s+", " ").strip();
                String doi = w.path("DOI").asText("");
                String url = w.path("URL").asText(doi.isEmpty() ? "" : "https://doi.org/" + doi);
                if (title.isEmpty() || url.isEmpty()) continue;
                StringBuilder s = new StringBuilder();
                String venue = w.path("container-title").path(0).asText("");
                if (!venue.isEmpty()) s.append(venue);
                JsonNode y = w.path("issued").path("date-parts").path(0).path(0);
                if (y.isNumber()) s.append(s.length() > 0 ? ", " : "").append(y.asInt());
                List<String> authors = new ArrayList<>();
                for (JsonNode a : w.path("author")) { if (authors.size() >= 3) break; String f = a.path("family").asText(""); if (!f.isEmpty()) authors.add(f); }
                if (!authors.isEmpty()) s.append(s.length() > 0 ? ": " : "").append(String.join(", ", authors)).append(w.path("author").size() > 3 ? " et al." : "");
                String type = w.path("type").asText("");
                if (!type.isEmpty()) s.append(s.length() > 0 ? " (" : "(").append(type.replace('-', ' ')).append(')');
                out.add(new Row(title, url, s.toString(), doi));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** OpenAlex's {@code results[]}: display_name, doi (a URL), primary_location.landing_page_url/source.display_name, publication_year, authorships[]. */
    static List<Row> parseOpenAlex(String json) {
        List<Row> out = new ArrayList<>();
        try {
            for (JsonNode w : M.readTree(json).path("results")) {
                String title = w.path("display_name").asText("").replaceAll("\\s+", " ").strip();
                String doiUrl = w.path("doi").asText("");
                String doi = doiUrl.replaceFirst("^https?://doi\\.org/", "");
                String url = w.path("primary_location").path("landing_page_url").asText("");
                if (url.isEmpty()) url = doiUrl;
                if (title.isEmpty() || url.isEmpty()) continue;
                StringBuilder s = new StringBuilder();
                String venue = w.path("primary_location").path("source").path("display_name").asText("");
                if (!venue.isEmpty()) s.append(venue);
                if (w.path("publication_year").isNumber()) s.append(s.length() > 0 ? ", " : "").append(w.path("publication_year").asInt());
                List<String> authors = new ArrayList<>();
                for (JsonNode a : w.path("authorships")) { if (authors.size() >= 3) break; String n = a.path("author").path("display_name").asText(""); if (!n.isEmpty()) authors.add(n); }
                if (!authors.isEmpty()) s.append(s.length() > 0 ? ": " : "").append(String.join(", ", authors)).append(w.path("authorships").size() > 3 ? " et al." : "");
                out.add(new Row(title, url, s.toString(), doi));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** The rows as the model sees them, in the search-results fence, each with its source tier. */
    public static String render(String query, String heading, List<Row> rows, int limit) { return render(query, heading, rows, limit, Fetch.Policy.DEFAULT); }

    /** The same, with the run's lists: a row on the person's refused list, or on the site list unless the run let it in, is left out. */
    public static String render(String query, String heading, List<Row> rows, int limit, Fetch.Policy policy) {
        Fetch.Policy p = policy == null ? Fetch.Policy.DEFAULT : policy;
        StringBuilder sb = new StringBuilder("results for \"" + query + "\"" + heading + ":\n" + Fence.open("SEARCH RESULTS") + "\n");
        int shown = 0, refused = 0, listed = 0;
        var rules = SourceRules.live();
        for (Row r : rows) {
            if (shown >= limit) break;
            if (p.refused(r.url())) { refused++; continue; }
            if (p.onSiteList(r.url())) { listed++; continue; }
            shown++;
            sb.append(shown).append(". ").append(r.title()).append('\n')
              .append("   ").append(r.url()).append("  [").append(SourceTier.of(r.url())).append(rules.trusted(r.url()) ? ", trusted by the person" : "").append("]\n");
            if (!r.snippet().isEmpty()) sb.append("   ").append(r.snippet()).append('\n');
        }
        sb.append(WebSearchTool.leftOutLines(refused, listed));
        sb.append(Fence.close("SEARCH RESULTS")).append('\n').append(Fence.rule("SEARCH RESULTS")).append('\n');
        return sb.toString();
    }
}
