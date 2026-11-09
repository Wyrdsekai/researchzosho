package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.CareerSources.Feed;
import org.researchzosho.librarian.CareerSources.Found;
import org.researchzosho.librarian.CareerSources.Hit;
import org.researchzosho.librarian.CareerSources.Opening;
import org.researchzosho.tools.Fetch;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.function.Function;
/**
 * The hiring portals institutions use, read where they give a list: a health system's own careers site on Phenom (jobs.ascension.org,
 * jobs.bswhealth.com), Oracle's candidate experience (Texas Children's), iCIMS, Taleo's job board (Tenet), PeopleAdmin (universities),
 * beside Workday which the sources already read. And the way to them for an employer a register listed with no site of its own: a
 * hospital hires through its SYSTEM, so the name's leading phrase ("Ascension Seton Medical Center Austin" gives Ascension Seton, then
 * Ascension), confirmed by Wikidata's parent or owner when the hospital has an item, is searched for its careers page, whose links name
 * the portal; the portal's openings are then cut to the town, and the company keeps the feed like any board. Measured 2026-10-30.
 */
public final class CareerPortals {
    private CareerPortals() { }

    static final ObjectMapper M = new ObjectMapper();

    /** A POST with cookies and headers, for Taleo's job board, which answers only a session that saw its page; a test puts its own here. */
    public interface Poster { String post(String pageUrl, String postUrl, String json, Map<String, String> headers); }
    static volatile Poster POST_WITH_SESSION = CareerPortals::postWithSession;
    /** Wikidata's entity search and entity data, for a hospital's parent; a test puts its own here. */
    static volatile Function<String, String> WIKIDATA = CareerSources::get;

    static final Pattern ORACLE = Pattern.compile("(?i)https?://([a-z0-9-]+\\.fa\\.[a-z0-9]+\\.oraclecloud\\.com)/hcmUI/CandidateExperience/[a-z]{2}(?:-[A-Z]{2})?/sites/([A-Za-z0-9_]+)");
    static final Pattern ICIMS = Pattern.compile("(?i)https?://([a-z0-9-]+)\\.icims\\.com/");
    static final Pattern TALEO = Pattern.compile("(?i)https?://([a-z0-9-]+\\.taleo\\.net)/careersection/([a-z0-9]+)/");
    static final Pattern PEOPLEADMIN = Pattern.compile("(?i)https?://([a-z0-9-]+\\.peopleadmin\\.(?:com|ca))/");
    static final Pattern PHENOM_MARK = Pattern.compile("(?i)phenompeople\\.com|\"ddoKey\"|/widgets\\b");

    /** The portal a careers page leads to, from the page's own address and links; null when none. */
    public static Feed feedOfPage(String pageUrl, String html) {
        if (pageUrl == null) return null;
        String all = (html == null ? "" : html);
        Matcher m = ORACLE.matcher(all); if (m.find()) return new Feed("oracle", m.group(1).toLowerCase(Locale.ROOT) + "/" + m.group(2), "linked from its careers page, " + pageUrl);
        m = TALEO.matcher(pageUrl); if (m.find()) return new Feed("taleo", m.group(1).toLowerCase(Locale.ROOT) + "/" + m.group(2), "its careers page, " + pageUrl);
        m = TALEO.matcher(all); if (m.find()) return new Feed("taleo", m.group(1).toLowerCase(Locale.ROOT) + "/" + m.group(2), "linked from its careers page, " + pageUrl);
        m = ICIMS.matcher(pageUrl); if (m.find()) return new Feed("icims", m.group(1).toLowerCase(Locale.ROOT), "its careers page, " + pageUrl);
        m = ICIMS.matcher(all); if (m.find()) return new Feed("icims", m.group(1).toLowerCase(Locale.ROOT), "linked from its careers page, " + pageUrl);
        m = PEOPLEADMIN.matcher(pageUrl); if (m.find()) return new Feed("peopleadmin", m.group(1).toLowerCase(Locale.ROOT), "its careers page, " + pageUrl);
        m = PEOPLEADMIN.matcher(all); if (m.find()) return new Feed("peopleadmin", m.group(1).toLowerCase(Locale.ROOT), "linked from its careers page, " + pageUrl);
        if (PHENOM_MARK.matcher(all).find()) {
            String host = CareerSources.host(pageUrl);
            if (!host.isEmpty()) return new Feed("phenom", host, "its careers site, " + pageUrl);
        }
        return null;
    }

    /** Whether a feed kind is one of these portals. */
    public static boolean isPortal(String kind) { return kind != null && Set.of("phenom", "oracle", "icims", "taleo", "peopleadmin").contains(kind); }

    /** The portal's openings for the words, in the existing shape; null when the portal does not answer. */
    public static Found read(Feed f, String company, List<String> words) {
        try {
            List<Opening> out = new ArrayList<>();
            List<String> terms = words == null || words.isEmpty() ? List.of("") : words.subList(0, Math.min(3, words.size()));
            String note = "";
            switch (f.kind()) {
                case "phenom" -> {
                    String host = f.token();
                    Map<String, JsonNode> got = new LinkedHashMap<>();
                    int total = -1;
                    for (String term : terms) {
                        ObjectNode body = M.createObjectNode().put("lang", "en_us").put("deviceType", "desktop").put("country", "us").put("pageName", "search-results").put("ddoKey", "refineSearch")
                                .put("sortBy", "").put("subsearch", "").put("from", 0).put("jobs", true).put("counts", true).put("size", 100).put("clearAll", false).put("jdsource", "facets")
                                .put("isSliderEnable", false).put("pageId", "page12").put("siteType", "external").put("keywords", term).put("global", true);
                        body.putArray("all_fields").add("category").add("city").add("state").add("country");
                        body.putObject("selected_fields"); body.putObject("locationData");
                        String b = CareerSources.postJson("https://" + host + "/widgets", body.toString());
                        if (b == null) { if (got.isEmpty()) return null; break; }
                        JsonNode rs = M.readTree(b).path("refineSearch");
                        if (total < 0) total = rs.path("totalHits").asInt(-1);
                        for (JsonNode j : rs.path("data").path("jobs")) got.putIfAbsent(j.path("jobSeqNo").asText(""), j);
                    }
                    for (JsonNode j : got.values()) {
                        String loc = j.path("cityState").asText("").isEmpty() ? (j.path("city").asText("") + (j.path("state").asText("").isEmpty() ? "" : ", " + j.path("state").asText(""))) : j.path("cityState").asText("");
                        StringBuilder org = new StringBuilder(j.path("department").asText("")).append(' ').append(j.path("category").asText(""));
                        for (JsonNode l : j.path("multi_location_array")) org.append(' ').append(l.path("location").asText(""));
                        out.add(CareerSources.opening(company, j.path("title").asText(""), loc.strip(), CareerSources.remote(loc) || j.path("isRemote").asBoolean(false), "https://" + host + "/us/en/job/" + j.path("jobSeqNo").asText(""),
                                CareerSources.day(j.path("postedDate").asText(j.path("dateCreated").asText(""))), "", f.label(), (org.toString().isBlank() ? "" : org.toString().strip() + ". ") + CareerSources.plain(CareerSources.unescape(j.path("descriptionTeaser").asText(""))), "", ""));
                    }
                    if (total > got.size()) note = "Its careers site lists " + total + " openings; the library read the " + got.size() + " nearest the titles you want.";
                }
                case "oracle" -> {
                    String[] t = f.token().split("/");
                    if (t.length != 2) return null;
                    String host = t[0], site = t[1];
                    Map<String, JsonNode> got = new LinkedHashMap<>();
                    int total = -1;
                    for (String term : terms) {
                        String b = CareerSources.get("https://" + host + "/hcmRestApi/resources/latest/recruitingCEJobRequisitions?onlyData=true&expand=requisitionList.secondaryLocations&finder=findReqs;siteNumber=" + site
                                + ",limit=200,sortBy=POSTING_DATES_DESC" + (term.isEmpty() ? "" : ",keyword=" + URLEncoder.encode(term, StandardCharsets.UTF_8)));
                        if (b == null) { if (got.isEmpty()) return null; break; }
                        JsonNode it = M.readTree(b).path("items");
                        if (!it.isArray() || it.isEmpty()) continue;
                        if (total < 0) total = it.get(0).path("TotalJobsCount").asInt(-1);
                        for (JsonNode j : it.get(0).path("requisitionList")) got.putIfAbsent(j.path("Id").asText(""), j);
                    }
                    for (JsonNode j : got.values()) {
                        StringBuilder loc = new StringBuilder(j.path("PrimaryLocation").asText("").strip());
                        for (JsonNode s : j.path("secondaryLocations")) if (!s.path("Name").asText("").isBlank()) loc.append("; ").append(s.path("Name").asText());
                        String org = String.join(" ", List.of(j.path("LegalEmployer").asText(""), j.path("Organization").asText(""), j.path("Department").asText(""), j.path("BusinessUnit").asText(""))).strip();
                        out.add(CareerSources.opening(company, j.path("Title").asText(""), loc.toString(), CareerSources.remote(loc.toString()), "https://" + host + "/hcmUI/CandidateExperience/en/sites/" + site + "/job/" + j.path("Id").asText(""),
                                CareerSources.day(j.path("PostedDate").asText("")), "", f.label(), (org.isEmpty() ? "" : org + ". ") + CareerSources.plain(CareerSources.unescape(j.path("ShortDescriptionStr").asText(""))), "", ""));
                    }
                    if (total > got.size()) note = "Its hiring site lists " + total + " openings; the library read the " + got.size() + " newest for the titles you want.";
                }
                case "icims" -> {
                    String tenant = f.token();
                    Map<String, Opening> got = new LinkedHashMap<>();
                    for (String term : terms) {
                        for (int page = 0; page < 3; page++) {
                            String url = "https://" + tenant + ".icims.com/jobs/search?ss=1&in_iframe=1" + (term.isEmpty() ? "" : "&searchKeyword=" + URLEncoder.encode(term, StandardCharsets.UTF_8)) + (page > 0 ? "&pr=" + page : "");
                            String html = CareerSources.get(url);
                            if (html == null) { if (got.isEmpty() && page == 0 && term.equals(terms.get(0))) return null; break; }
                            int before = got.size();
                            for (Opening o : icimsRows(html, tenant, company, f.label())) got.putIfAbsent(o.url(), o);
                            if (got.size() == before) break;
                        }
                    }
                    out.addAll(got.values());
                }
                case "taleo" -> {
                    String[] t = f.token().split("/");
                    if (t.length < 2) return null;
                    String host = t[0], section = t[1];
                    String pageUrl = "https://" + host + "/careersection/" + section + "/jobsearch.ftl?lang=en";
                    String page = CareerSources.get(pageUrl);
                    if (page == null) return null;
                    Matcher pm = Pattern.compile("portal=(\\d{6,})").matcher(page);
                    String portal = t.length > 2 ? t[2] : pm.find() ? pm.group(1) : "";
                    if (portal.isEmpty()) return null;
                    Map<String, Opening> got = new LinkedHashMap<>();
                    for (String term : terms) {
                        ObjectNode body = M.createObjectNode().put("multilineEnabled", false).put("pageNo", 1);
                        body.putObject("sortingSelection").put("sortBySelectionParam", "3").put("ascendingSortingOrder", "false");
                        ObjectNode fd = body.putObject("fieldData"); fd.putObject("fields").put("KEYWORD", term).put("LOCATION", ""); fd.put("valid", true);
                        var fs = body.putObject("filterSelectionParam").putArray("searchFilterSelections");
                        for (String id : List.of("POSTING_DATE", "LOCATION", "JOB_FIELD")) fs.addObject().put("id", id).putArray("selectedValues");
                        var afs = body.putObject("advancedSearchFiltersSelectionParam").putArray("searchFilterSelections");
                        for (String id : List.of("ORGANIZATION", "LOCATION", "JOB_FIELD", "URGENT_JOB", "EMPLOYEE_STATUS", "STUDY_LEVEL", "WILL_TRAVEL", "JOB_SHIFT", "JOB_NUMBER")) afs.addObject().put("id", id).putArray("selectedValues");
                        String b = POST_WITH_SESSION.post(pageUrl, "https://" + host + "/careersection/rest/jobboard/searchjobs?lang=en&portal=" + portal, body.toString(), Map.of("tz", "GMT+00:00", "tzname", "UTC"));
                        if (b == null) { if (got.isEmpty()) return null; break; }
                        for (JsonNode j : M.readTree(b).path("requisitionList")) {
                            JsonNode col = j.path("column");
                            String title = col.path(0).asText(""), locs = col.path(1).asText(""), date = col.path(2).asText("");
                            String loc = locs.replaceAll("[\\[\\]\"]", "").replace(",", "; ").strip();
                            String url = "https://" + host + "/careersection/" + section + "/jobdetail.ftl?job=" + j.path("contestNo").asText("") + "&lang=en";
                            got.putIfAbsent(url, CareerSources.opening(company, title, loc, CareerSources.remote(loc), url, "", date.isEmpty() ? "" : "posted " + date, f.label(), "", "", ""));
                        }
                    }
                    out.addAll(got.values());
                }
                case "peopleadmin" -> {
                    String host = f.token();
                    Map<String, Opening> got = new LinkedHashMap<>();
                    for (String term : terms) {
                        String html = CareerSources.get("https://" + host + "/postings/search?query=" + URLEncoder.encode(term, StandardCharsets.UTF_8));
                        if (html == null) { if (got.isEmpty()) return null; break; }
                        Matcher m = Pattern.compile("(?is)<a[^>]+href=\"(/postings/(\\d+))\"[^>]*>(.*?)</a>").matcher(html);
                        while (m.find()) {
                            String title = CareerSources.plain(m.group(3)).strip();
                            if (title.isEmpty() || title.equalsIgnoreCase("View Details") || title.length() > 160) continue;
                            got.putIfAbsent("https://" + host + m.group(1), CareerSources.opening(company, title, "", false, "https://" + host + m.group(1), "", "", f.label(), "", "", ""));
                        }
                    }
                    out.addAll(got.values());
                }
                default -> { return null; }
            }
            return new Found(company, f, out, note);
        } catch (Exception e) { return null; }
    }

    /** iCIMS's search page rows: the job link, its title, and the "Job Locations" line of the row. */
    static List<Opening> icimsRows(String html, String tenant, String company, String source) {
        List<Opening> out = new ArrayList<>();
        Matcher m = Pattern.compile("(?is)<a[^>]+href=\"(https?://" + Pattern.quote(tenant) + "\\.icims\\.com/jobs/(\\d+)/[^\"]*?/job)[^\"]*\"[^>]*>(.*?)</a>").matcher(html);
        List<int[]> spans = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        while (m.find()) { spans.add(new int[]{m.start(), m.end()}); rows.add(new String[]{m.group(1), m.group(2), CareerSources.plain(m.group(3)).replaceFirst("(?i)^job title\\s*", "").strip()}); }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            String url = rows.get(i)[0], title = rows.get(i)[2];
            if (title.isEmpty() || !seen.add(url)) continue;
            int from = spans.get(i)[1], to = i + 1 < spans.size() ? spans.get(i + 1)[0] : Math.min(html.length(), from + 4000);
            String row = html.substring(from, to);
            Matcher lm = Pattern.compile("(?is)Job Locations?\\s*</[^>]+>\\s*<dd[^>]*>(.*?)</dd>").matcher(row);
            String loc = lm.find() ? CareerSources.plain(lm.group(1)).strip() : "";
            Matcher pm = Pattern.compile("(?is)Posted Date\\s*</[^>]+>\\s*<dd[^>]*>(.*?)</dd>").matcher(row);
            String posted = pm.find() ? CareerSources.plain(pm.group(1)).strip() : "";
            out.add(CareerSources.opening(company, title, loc, CareerSources.remote(loc), url, "", posted.isEmpty() ? "" : "posted " + posted, source, "", "", ""));
        }
        return out;
    }

    // ---------------------------------------------------------------- the way to a portal for an employer with no site

    /**
     * The words an institution's name carries that say what kind of place it is, widened to the words such a place's openings use; a
     * board found through another name must carry one of them, in an opening or on the page, or it is a namesake's.
     */
    static final List<List<String>> KIND_FAMILIES = List.of(
            List.of("medical", "med", "hospital", "hospitals", "health", "healthcare", "clinic", "clinics", "surgical", "surgery", "patient", "patients", "nurse", "nurses", "nursing", "clinical", "physician", "physicians", "care", "children's", "childrens"),
            List.of("university", "college", "school", "schools", "campus", "faculty", "student", "students", "academic", "professor", "lecturer", "teacher", "teachers"),
            List.of("research", "laboratory", "laboratories", "lab", "institute", "scientist", "scientists", "postdoctoral", "fellow", "fellowship"));

    static List<String> kindWords(String name) {
        List<String> out = new ArrayList<>();
        String n = " " + (name == null ? "" : name.toLowerCase(Locale.ROOT).replace("'", "")) + " ";
        for (List<String> family : KIND_FAMILIES) {
            boolean named = false;
            for (String w : family) if (n.contains(" " + w.replace("'", "") + " ")) { named = true; break; }
            if (named) for (String w : family) if (!out.contains(w)) out.add(w);
        }
        return out;
    }

    /** Whether what a board gave carries one of the institution's kind words, in an opening or on the page it was found on: the anchor for a board found through another name. */
    static boolean ofTheKind(Found f, String page, List<String> kinds) {
        if (kinds.isEmpty()) return true;
        StringBuilder all = new StringBuilder(page == null ? "" : page);
        for (Opening o : f.openings()) all.append(' ').append(o.title()).append(' ').append(o.location()).append(' ').append(o.text() == null ? "" : o.text());
        String t = " " + all.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9']+", " ").replace("'", "") + " ";
        for (String k : kinds) if (t.contains(" " + k.replace("'", "") + " ")) return true;
        return false;
    }

    static final Pattern GENERIC = Pattern.compile("(?i)\\b(medical|med|center|centre|hospital|hospitals|health|healthcare|regional|memorial|surgical|surgery|clinic|clinics|institute|university|college|school|campus|general|community|county|state|city|nursing|rehabilitation|rehab|of|at|the)\\b.*$");   // "Children's" stays: it is part of a name (Texas Children's, Dell Children's)

    /**
     * The organisations an institution's name may belong to, most specific first: the words before the first generic one ("Ascension Seton
     * Medical Center Austin" gives "Ascension Seton", then "Ascension"); a name with no such phrase gives nothing.
     */
    public static List<String> systems(String name) {
        List<String> out = new ArrayList<>();
        if (name == null) return out;
        String lead = GENERIC.matcher(name.strip()).replaceFirst("").strip().replaceAll("[,;:]+$", "");
        if (lead.isEmpty() || lead.equalsIgnoreCase(name.strip())) return out;
        String[] w = lead.split("\\s+");
        for (int n = w.length; n >= 1; n--) {
            String s = String.join(" ", List.of(w).subList(0, n));
            if (s.endsWith("&") || s.endsWith(" and") || s.endsWith("-")) continue;   // "Baylor Scott &" is no name
            if (s.length() >= 3 && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /** Wikidata's parent organisation (P749) or owner (P127) of an institution, by its name; "" when it has no item or no such claim. */
    public static String wikidataParent(String name) {
        try {
            String s = WIKIDATA.apply("https://www.wikidata.org/w/api.php?action=wbsearchentities&language=en&format=json&limit=1&search=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
            if (s == null) return "";
            JsonNode hit = M.readTree(s).path("search");
            if (!hit.isArray() || hit.isEmpty()) return "";
            String id = hit.get(0).path("id").asText("");
            String e = WIKIDATA.apply("https://www.wikidata.org/wiki/Special:EntityData/" + id + ".json");
            if (e == null) return "";
            JsonNode claims = M.readTree(e).path("entities").path(id).path("claims");
            for (String p : List.of("P749", "P127")) {
                for (JsonNode c : claims.path(p)) {
                    String q = c.path("mainsnak").path("datavalue").path("value").path("id").asText("");
                    if (q.isEmpty()) continue;
                    String pe = WIKIDATA.apply("https://www.wikidata.org/wiki/Special:EntityData/" + q + ".json");
                    if (pe == null) continue;
                    String label = M.readTree(pe).path("entities").path(q).path("labels").path("en").path("value").asText("");
                    if (!label.isEmpty()) return label;
                }
            }
        } catch (Exception ignored) { }
        return "";
    }

    /**
     * An institution's openings when it has no site of its own: first its own name, as any company; then the organisations it belongs to,
     * Wikidata's parent when it names one and the name's leading phrases, each searched for its careers page and the portal that page
     * leads to; a portal's openings are cut to the towns, which is the anchor for a board found through another name. The note says
     * whose board the openings came from and how many are in the towns.
     */
    /** A board's openings cut to the towns named, however the board writes them; remote ones stay; no towns, no cut. */
    public static Found inTowns(Found f, List<String> towns) {
        if (f == null || towns == null || towns.isEmpty()) return f;
        List<CareerSources.Opening> kept = new ArrayList<>();
        for (CareerSources.Opening o : f.openings()) if (o.remote() || towns.stream().anyMatch(t -> townIn(o.location() == null ? "" : o.location(), t))) kept.add(o);
        return new Found(f.company(), f.feed(), kept, f.note());
    }

    /** An employer's board and its openings in sentences, for the board op, the command, the page's form and the MCP tool. */
    public static String boardText(String employer, Found f, List<String> towns) {
        if (f == null || f.feed() == null) return "No careers board of " + employer + "'s could be found" + (f == null || f.note() == null || f.note().isBlank() ? "." : ": " + f.note());
        StringBuilder b = new StringBuilder(employer).append("'s openings come from ").append(f.feed().how() == null || f.feed().how().isBlank() ? "its board" : f.feed().how()).append(" (").append(f.feed().kind()).append("): ")
                .append(f.openings().size()).append(f.openings().size() == 1 ? " opening" : " openings").append(towns == null || towns.isEmpty() ? "" : " in " + String.join(", ", towns)).append('.');
        int shown = 0;
        for (CareerSources.Opening o : f.openings()) {
            if (shown++ >= 12) { b.append("\nAnd ").append(f.openings().size() - 12).append(" more."); break; }
            b.append("\n- ").append(o.title()).append(o.location() == null || o.location().isBlank() ? "" : " (" + o.location() + ")").append(o.remote() ? ", remote" : "").append(o.url() == null || o.url().isBlank() ? "" : " — " + o.url());
        }
        if (f.note() != null && !f.note().isBlank()) b.append("\n").append(f.note());
        return b.toString();
    }

    public static Found find(String company, List<String> towns, List<String> words, List<String> anchors) {
        Found own = CareerSources.openings(company, words, anchors);
        if (own != null && own.feed() != null && (!own.openings().isEmpty() || !own.note().isEmpty())) return own;
        List<String> tries = new ArrayList<>();
        String parent = wikidataParent(company);
        if (!parent.isEmpty()) tries.add(parent);
        for (String s : systems(company)) if (!tries.contains(s) && !s.equalsIgnoreCase(company)) tries.add(s);
        List<String> t = towns == null ? List.of() : towns;
        List<String> kinds = kindWords(company);
        List<String> namesakes = new ArrayList<>();
        Set<String> placeWords = new LinkedHashSet<>();   // a state or a town in the name is where the institution is, not whom it belongs to
        for (String p : t) for (String w : p.split("[\\s,;]+")) if (w.length() >= 2) placeWords.add(w.toLowerCase(Locale.ROOT));
        if (anchors != null) for (String a : anchors) for (String w : a.split("[\\s,;]+")) if (w.length() >= 2) placeWords.add(w.toLowerCase(Locale.ROOT));
        String fullLead = tries.isEmpty() ? "" : tries.get(tries.size() - 1);
        for (String s : systems(company)) { fullLead = s; break; }
        for (String org : tries) {
            boolean isPlace = true;
            for (String w : org.split("\\s+")) if (!placeWords.contains(w.toLowerCase(Locale.ROOT))) { isPlace = false; break; }
            if (isPlace && !org.equals(parent)) continue;
            Found f = CareerSources.openingsByName(org, words);
            if (f == null || f.feed() == null) continue;
            String page = CareerSources.lastCareersPage();
            if (!ofTheKind(f, page, kinds)) { namesakes.add(org + "'s board (" + f.feed().system() + ")"); continue; }   // a namesake's board: nothing of a hospital, a school or a lab in what it gave
            if (!org.equals(parent) && !org.equalsIgnoreCase(fullLead)) {   // a shorter name than the institution's own lead: what it gave must carry one of the lead's other words, or Dell's board is taken for Dell Seton's
                List<String> others = new ArrayList<>();
                for (String w : fullLead.split("\\s+")) if (w.length() >= 3 && !org.toLowerCase(Locale.ROOT).contains(w.toLowerCase(Locale.ROOT))) others.add(w.toLowerCase(Locale.ROOT).replace("'", ""));
                if (!others.isEmpty() && !ofTheKind(f, page, others)) { namesakes.add(org + "'s board (" + f.feed().system() + ")"); continue; }
            }
            List<Opening> near = new ArrayList<>();
            for (Opening o : f.openings()) if (t.isEmpty() || inTowns(o, t)) near.add(o);
            String campuses = "";
            if (near.isEmpty() && !f.openings().isEmpty() && !t.isEmpty()) {
                if (anyTownNamed(f.openings())) return new Found(company, null, List.of(), org + "'s board (" + f.feed().system() + ", " + f.openings().size() + " openings) lists none in " + String.join(", ", t) + ", so the search does not take it as this employer's");
                near.addAll(f.openings());   // the board names its campuses and buildings, never a town: nothing to cut by, so the openings stand with the note
                campuses = " Its board names campuses, not towns, so the openings are not cut to " + String.join(", ", t) + ".";
            }
            String where = t.isEmpty() || !campuses.isEmpty() ? "" : " in " + String.join(", ", t);
            Feed via = new Feed(f.feed().kind(), f.feed().token(), f.feed().how() + "; found through " + org + ", the organisation it belongs to");
            boolean ownPage = f.feed().kind().equals("jobposting") || f.feed().kind().equals("links");
            return new Found(company, via, near, "Its openings come from " + org + "'s " + (ownPage ? "careers page" : f.feed().system() + " board") + ", " + near.size() + where + "." + campuses + (f.note().isEmpty() ? "" : " " + f.note()));
        }
        String note = "no hiring page turned up for it or for the organisation it belongs to" + (namesakes.isEmpty() ? "" : "; " + String.join(" and ", namesakes) + (namesakes.size() == 1 ? " says" : " say") + " nothing of a place of its kind, so it is not taken as this employer's");
        return new Found(company, null, List.of(), note);
    }

    /** Whether an opening is in one of the towns, by its location line; the text is read only when the board gives no location, since an employer's name carries its state. */
    static boolean inTowns(Opening o, List<String> towns) {
        String loc = o.location() == null ? "" : o.location().strip();
        if (loc.isEmpty()) loc = o.text() == null ? "" : o.text().substring(0, Math.min(400, o.text().length()));
        for (String t : towns) if (!t.isBlank() && townIn(loc, t)) return true;
        return false;
    }

    /** The US states by code, for a town written one way and a board's location another: "Boston, MA" is "Boston-MA" and "Boston, Massachusetts". */
    static final Map<String, String> US_STATES = Map.ofEntries(Map.entry("AL", "Alabama"), Map.entry("AK", "Alaska"), Map.entry("AZ", "Arizona"), Map.entry("AR", "Arkansas"), Map.entry("CA", "California"),
            Map.entry("CO", "Colorado"), Map.entry("CT", "Connecticut"), Map.entry("DE", "Delaware"), Map.entry("DC", "District of Columbia"), Map.entry("FL", "Florida"), Map.entry("GA", "Georgia"),
            Map.entry("HI", "Hawaii"), Map.entry("ID", "Idaho"), Map.entry("IL", "Illinois"), Map.entry("IN", "Indiana"), Map.entry("IA", "Iowa"), Map.entry("KS", "Kansas"), Map.entry("KY", "Kentucky"),
            Map.entry("LA", "Louisiana"), Map.entry("ME", "Maine"), Map.entry("MD", "Maryland"), Map.entry("MA", "Massachusetts"), Map.entry("MI", "Michigan"), Map.entry("MN", "Minnesota"),
            Map.entry("MS", "Mississippi"), Map.entry("MO", "Missouri"), Map.entry("MT", "Montana"), Map.entry("NE", "Nebraska"), Map.entry("NV", "Nevada"), Map.entry("NH", "New Hampshire"),
            Map.entry("NJ", "New Jersey"), Map.entry("NM", "New Mexico"), Map.entry("NY", "New York"), Map.entry("NC", "North Carolina"), Map.entry("ND", "North Dakota"), Map.entry("OH", "Ohio"),
            Map.entry("OK", "Oklahoma"), Map.entry("OR", "Oregon"), Map.entry("PA", "Pennsylvania"), Map.entry("RI", "Rhode Island"), Map.entry("SC", "South Carolina"), Map.entry("SD", "South Dakota"),
            Map.entry("TN", "Tennessee"), Map.entry("TX", "Texas"), Map.entry("UT", "Utah"), Map.entry("VT", "Vermont"), Map.entry("VA", "Virginia"), Map.entry("WA", "Washington"),
            Map.entry("WV", "West Virginia"), Map.entry("WI", "Wisconsin"), Map.entry("WY", "Wyoming"));

    /**
     * Whether a board's location line names the town: the town's city as a word, and when both name a state, the same one by its code or
     * its name. A board writes "Boston, MA", "Boston-MA", "Boston, Massachusetts" or "Massachusetts - Boston"; the town is written as the
     * person did. A location that names the state alone, or the city alone, counts when that is all it says.
     */
    static boolean townIn(String location, String town) {
        if (location == null || location.isBlank() || town == null || town.isBlank()) return false;
        String loc = location.replaceAll("[-–—/|()]+", ", ");
        if (CareerCut.has(loc, town)) return true;
        String[] parts = town.split("\\s*,\\s*");
        String city = parts[0].strip(), state = parts.length > 1 ? parts[parts.length - 1].strip() : "";
        String code = "", name = "";
        if (!state.isEmpty()) {
            String up = state.toUpperCase(Locale.ROOT);
            if (US_STATES.containsKey(up)) { code = up; name = US_STATES.get(up); }
            else for (Map.Entry<String, String> e : US_STATES.entrySet()) if (e.getValue().equalsIgnoreCase(state)) { code = e.getKey(); name = e.getValue(); }
        }
        boolean cityThere = !city.isEmpty() && CareerCut.has(loc, city);
        if (code.isEmpty()) return cityThere;   // a town with no state the library knows: the city as a word is the match
        boolean stateThere = CareerCut.has(loc, name) || hasCode(loc, code);
        String otherState = otherStateIn(loc, code);
        if (cityThere) return otherState.isEmpty();   // the city, and no other state named beside it
        return stateThere && city.isEmpty();
    }

    static boolean hasCode(String loc, String code) { return Pattern.compile("(?<![\\p{L}])" + code + "(?![\\p{L}])").matcher(loc).find(); }

    /** A state the location names that is not the town's: "" when none. */
    static String otherStateIn(String loc, String code) {
        for (Map.Entry<String, String> e : US_STATES.entrySet()) {
            if (e.getKey().equals(code)) continue;
            if (CareerCut.has(loc, e.getValue()) || hasCode(loc, e.getKey())) return e.getKey();
        }
        return "";
    }

    /** Whether any of a board's openings names a town at all: a board whose lines are campuses and buildings names none, and cannot be cut by town. */
    static boolean anyTownNamed(List<Opening> os) {
        for (Opening o : os) {
            String loc = o.location() == null ? "" : o.location();
            if (loc.isBlank()) continue;
            for (String part : loc.replaceAll("[-–—/|()]+", ", ").split("\\s*,\\s*")) {   // a part that is a state or a country on its own: "Boston, MA", "Boston-MA", "Manchester, England"; "Texas Medical Center" is a building
                String p = part.strip();
                if (p.isEmpty()) continue;
                if (US_STATES.containsKey(p.toUpperCase(Locale.ROOT)) || US_STATES.containsValue(p) || COUNTRIES.contains(p.toLowerCase(Locale.ROOT))) return true;
                for (Map.Entry<String, String> e : US_STATES.entrySet()) if (e.getValue().equalsIgnoreCase(p)) return true;
            }
        }
        return false;
    }

    static final Set<String> COUNTRIES = Set.of("united states", "usa", "us", "united kingdom", "uk", "england", "scotland", "wales", "canada", "france", "germany", "japan", "australia", "ireland", "netherlands", "spain", "italy", "switzerland", "sweden", "india", "singapore", "remote");

    // ---------------------------------------------------------------- a POST that needs a session

    private static final HttpClient SESSION = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NORMAL).build();

    static String postWithSession(String pageUrl, String postUrl, String json, Map<String, String> headers) {
        try {
            if (Fetch.refusal(URI.create(pageUrl)) != null || Fetch.refusal(URI.create(postUrl)) != null) return null;
            SESSION.send(HttpRequest.newBuilder(URI.create(pageUrl)).timeout(Duration.ofSeconds(20)).header("User-Agent", Fetch.USER_AGENT).GET().build(), HttpResponse.BodyHandlers.discarding());
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(postUrl)).timeout(Duration.ofSeconds(25)).header("User-Agent", Fetch.USER_AGENT).header("Content-Type", "application/json").header("Accept", "application/json");
            for (var h : headers.entrySet()) b.header(h.getKey(), h.getValue());
            HttpResponse<String> r = SESSION.send(b.POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return r.statusCode() == 200 ? r.body() : null;
        } catch (Exception e) { return null; }
    }
}
