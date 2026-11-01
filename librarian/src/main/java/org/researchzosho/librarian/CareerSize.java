package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.librarian.CareerSources.Feed;
import org.researchzosho.librarian.CareerSources.Hit;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How many people work at a company (docs/DESIGN_CAREER.md, the company's state): a want such as "not bigger than 200" is answered by
 * the company's record, never by the posting's words. A number is taken only when it is tied to the company's own website, since by name
 * alone a search finds namesakes: the company's own pages first; then the LinkedIn page for it, which a search result shows with the
 * website it names; then Wikidata, when the website it records is the same. Each number keeps its source and the day it was read.
 */
public final class CareerSize {
    private CareerSize() { }

    private static final ObjectMapper M = new ObjectMapper();

    /** A company's size: the fewest and the most people it may have, where the number came from, and the day it was read. */
    public record People(int low, int high, String source, String at) {
        /** "about 51 to 200 people", "about 2,400 people". */
        public String words() { return low == high ? "about " + String.format("%,d", low) + " people" : "about " + String.format("%,d", low) + " to " + String.format("%,d", high) + " people"; }
    }

    static final Pattern RANGE = Pattern.compile("(?i)(\\d{1,3}(?:,\\d{3})+|\\d+)\\s*(?:-|–|to)\\s*(\\d{1,3}(?:,\\d{3})+|\\d+)\\s*(?:\\+\\s*)?(?:employees|people|staff)");
    static final Pattern COUNT = Pattern.compile("(?i)(?:team of|company of|employs|employees:|headcount(?: of)?|has|with|over|more than|about|approximately|around|~)\\s*(\\d{1,3}(?:,\\d{3})+|\\d+)\\s*\\+?\\s*(?:employees|people|staff|team members|engineers)");
    static final Pattern BARE = Pattern.compile("(?i)(\\d{1,3}(?:,\\d{3})+|\\d+)\\s*\\+?\\s*(?:employees|team members)");

    /** The company's website's host, its www dropped; "" when the company's site is not known. */
    public static String site(String url) { String h = CareerSources.host(url == null ? "" : url); return h.startsWith("www.") ? h.substring(4) : h; }

    /**
     * The company's website, from where its openings were read when that was its own careers page, else found by a search for its name
     * and confirmed by the page linking the same hiring board; "" when nothing confirms a site.
     */
    public static String findSite(String company, Feed feed) {
        if (feed == null) return "";
        if (feed.kind().equals("jobposting") || feed.kind().equals("links")) return CareerSources.registrable(site(feed.token()));
        Matcher m = Pattern.compile("careers page, (https?://\\S+)").matcher(feed.how());
        if (m.find()) return CareerSources.registrable(site(m.group(1)));
        int tried = 0;
        List<Hit> hits = new ArrayList<>(CareerSources.SEARCH.apply(company + " careers jobs"));   // its careers page links its board; its front page seldom does
        hits.addAll(CareerSources.SEARCH.apply(company));
        for (Hit h : hits) {
            if (CareerSources.aggregator(h.url()) || CareerSources.host(h.url()).matches(".*(wikipedia|linkedin|crunchbase|pitchbook|zoominfo|x\\.com|twitter|facebook|youtube|github|glassdoor|indeed)\\..*") || tried >= 4) continue;
            if (!CareerSources.boardsIn(h.url(), "").isEmpty()) continue;
            tried++;
            String page = CareerSources.get(h.url());
            if (page == null) continue;
            for (Feed f : CareerSources.boardsIn(page, "")) if (f.label().equals(feed.label())) return CareerSources.registrable(site(h.url()));   // the page links the same board: the company's own
        }
        return "";
    }

    /** The company's size, tied to its website {@code site}; null when no source gives one. */
    public static People lookup(String company, String site) {
        if (site == null || site.isBlank()) return null;
        String today = LocalDate.now().toString();
        for (String path : List.of("/", "/about", "/company", "/about-us")) {   // the company's own pages: the job data for search engines, or the words
            String page = CareerSources.get("https://" + site + path);
            if (page == null) continue;
            People p = fromOrganizationData(page, site, today);
            if (p == null) p = fromWords(CareerSources.plain(page.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")), "its own site, " + site, today, true);
            if (p != null) return p;
        }
        for (Hit h : CareerSources.SEARCH.apply(company + " employees")) {   // the LinkedIn page for it, which a result shows with the website it names
            String host = CareerSources.host(h.url());
            if (host.endsWith("linkedin.com") && h.url().contains("/company/") && h.snippet().toLowerCase(Locale.ROOT).contains(site.toLowerCase(Locale.ROOT))) {
                People p = fromWords(h.snippet(), "its LinkedIn page", today, false);
                if (p != null) return p;
            }
            if (host.equals(site) || host.endsWith("." + site)) { People p = fromWords(h.snippet(), "its own site, " + site, today, false); if (p != null) return p; }
        }
        return fromWikidata(company, site, today);
    }

    /** schema.org Organization data on a page: numberOfEmployees as a number or a range. */
    static People fromOrganizationData(String html, String site, String today) {
        Matcher m = Pattern.compile("(?is)<script[^>]+application/ld\\+json[^>]*>(.*?)</script>").matcher(html);
        while (m.find()) {
            try {
                JsonNode n = M.readTree(m.group(1).strip());
                JsonNode org = n.path("@type").asText("").equalsIgnoreCase("Organization") ? n : n.path("@graph").isArray() ? first(n.path("@graph")) : null;
                if (org == null || !org.has("numberOfEmployees")) continue;
                JsonNode e = org.get("numberOfEmployees");
                if (e.isNumber() || e.isTextual()) { int v = number(e.asText()); if (v > 0) return new People(v, v, "its own site, " + site, today); }
                int lo = number(e.path("minValue").asText("")), hi = number(e.path("maxValue").asText("")), v = number(e.path("value").asText(""));
                if (lo > 0 && hi >= lo) return new People(lo, hi, "its own site, " + site, today);
                if (v > 0) return new People(v, v, "its own site, " + site, today);
            } catch (Exception ignored) { }
        }
        return null;
    }

    private static JsonNode first(JsonNode graph) { for (JsonNode x : graph) if (x.path("@type").asText("").equalsIgnoreCase("Organization")) return x; return null; }

    /** A size in a page's or a snippet's words: a range "51-200 employees", or a count "team of 40", "about 150 people"; on a whole page only a phrase that says it. */
    static People fromWords(String text, String source, String today, boolean wholePage) {
        if (text == null) return null;
        Matcher r = RANGE.matcher(text);
        if (r.find()) { int lo = number(r.group(1)), hi = number(r.group(2)); if (lo > 0 && hi >= lo && hi < 10_000_000) return new People(lo, hi, source, today); }
        Matcher c = COUNT.matcher(text);
        if (c.find()) { int v = number(c.group(1)); if (v > 0 && v < 10_000_000) return new People(v, v, source, today); }
        if (!wholePage) { Matcher b = BARE.matcher(text); if (b.find()) { int v = number(b.group(1)); if (v > 0 && v < 10_000_000) return new People(v, v, source, today); } }
        return null;
    }

    /** Wikidata's count of employees (P1128) for the item whose official website (P856) is the company's site. */
    static People fromWikidata(String company, String site, String today) {
        try {
            String s = CareerSources.get("https://www.wikidata.org/w/api.php?action=wbsearchentities&format=json&language=en&type=item&limit=5&search=" + URLEncoder.encode(company, StandardCharsets.UTF_8));
            if (s == null) return null;
            for (JsonNode hit : M.readTree(s).path("search")) {
                String id = hit.path("id").asText("");
                if (id.isEmpty()) continue;
                String claims = CareerSources.get("https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&entity=" + id + "&property=P856");
                if (claims == null) continue;
                boolean same = false;
                for (JsonNode c : M.readTree(claims).path("claims").path("P856")) if (site(c.path("mainsnak").path("datavalue").path("value").asText("")).equalsIgnoreCase(site)) same = true;
                if (!same) continue;
                String counts = CareerSources.get("https://www.wikidata.org/w/api.php?action=wbgetclaims&format=json&entity=" + id + "&property=P1128");
                if (counts == null) return null;
                int best = 0; String when = "";
                for (JsonNode c : M.readTree(counts).path("claims").path("P1128")) {
                    int v = number(c.path("mainsnak").path("datavalue").path("value").path("amount").asText("").replace("+", ""));
                    String t = c.path("qualifiers").path("P585").path(0).path("datavalue").path("value").path("time").asText("");
                    if (v > 0 && t.compareTo(when) >= 0) { best = v; when = t; }
                }
                if (best > 0) return new People(best, best, "Wikidata" + (when.length() >= 5 ? ", as of " + when.substring(1, 5) : ""), today);
                return null;
            }
        } catch (Exception ignored) { }
        return null;
    }

    static int number(String s) { try { return Integer.parseInt((s == null ? "" : s).replace(",", "").replaceAll("\\..*$", "").strip()); } catch (NumberFormatException e) { return 0; } }
}
