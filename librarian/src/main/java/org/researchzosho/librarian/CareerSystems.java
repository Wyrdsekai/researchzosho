package org.researchzosho.librarian;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.librarian.CareerSources.Feed;
import org.researchzosho.librarian.CareerSources.Found;
import org.researchzosho.librarian.CareerSources.Opening;
import org.researchzosho.librarian.CareerSources.Page;
import org.researchzosho.librarian.CareerSources.Posting;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The hiring systems the career search reads since 0.8.4, beside Greenhouse, Lever, Ashby, Workable, Recruitee and Personio, each read
 * the way it offers its postings to anyone (measured on their real answers, 2026-11-26): SmartRecruiters (the employer's careers page
 * and each posting's own address, which answers JSON when JSON is asked), Teamtailor (the career site's job feed and each posting's
 * JobPosting data), Rippling (its job board service), Paylocity (the job list page's own data and each posting's page), Pinpoint (its
 * postings feed), BambooHR (the careers page's list and each opening's detail, both the JSON that page loads), JazzHR (its apply page
 * and each posting's JobPosting data), Lever's European service; and one Workday posting read from Workday's own record of it.
 *
 * <p>For each: an employer's list ({@link #read}), one posting read from its address ({@link #postingAt}), and, where the posting's
 * own answer says it, whether the system still lists it ({@link #listedAt}). Every request goes through {@link CareerSources#get} or
 * {@link CareerSources#page}, so each site's robots rules are kept and its pace is the library's. SmartRecruiters' data service
 * (api.smartrecruiters.com) lets only LinkedIn's reader in by its robots rules, so the library never asks it.
 */
final class CareerSystems {
    private CareerSystems() { }

    static final ObjectMapper M = new ObjectMapper();
    static final Map<String, String> JSON = Map.of("Accept", "application/json");
    static final Map<String, String> HTML = Map.of("Accept", "text/html");
    /** The most further pages of a SmartRecruiters careers page the library reads, after its first. */
    static final int SMARTRECRUITERS_PAGES = 9;
    /**
     * The most postings of one SmartRecruiters board read at their own address for their place, day and words, and the most Workday
     * records of one board read for the places of a row that lists several: each one request, and only for an opening whose title
     * holds a word wanted.
     */
    static final int SMARTRECRUITERS_POSTINGS_MAX = 30, WORKDAY_PLACES_MAX = 30;

    /** A Workday record read, kept ten minutes by its address: a posting found by a search is read once for its employer's name and its still-listed check. */
    private record Kept(long at, Page page) { }
    private static final Map<String, Kept> RECORDS = new ConcurrentHashMap<>();
    static final long RECORD_KEEP_MS = 600_000;

    /** Forgets the records kept; for tests. */
    static void forget() { RECORDS.clear(); }

    static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    static final Pattern WORKDAY = Pattern.compile("(?i)https?://([a-z0-9-]+)\\.(wd\\d+)\\.myworkdayjobs\\.com/(?:[a-z]{2}-[a-z]{2}/)?([^/?#]+)/(?:job|details)/([^?#]+)");
    static final Pattern SMARTRECRUITERS = Pattern.compile("(?i)https?://jobs\\.smartrecruiters\\.com/([^/?#]+)/(\\d{6,})[^/?#]*");
    static final Pattern TEAMTAILOR = Pattern.compile("(?i)https?://([a-z0-9-]+)\\.teamtailor\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?jobs/(\\d+)");
    static final Pattern RIPPLING = Pattern.compile("(?i)https?://ats\\.rippling\\.com/(?:[a-z]{2}-[a-z]{2}/)?([^/?#]+)/jobs/(" + UUID + ")");
    static final Pattern PAYLOCITY = Pattern.compile("(?i)https?://recruiting\\.paylocity\\.com/recruiting/jobs/details/(\\d+)");
    static final Pattern PAYLOCITY_LIST = Pattern.compile("(?i)/recruiting/jobs/all/(" + UUID + ")");
    static final Pattern PINPOINT = Pattern.compile("(?i)https?://([a-z0-9-]+)\\.pinpointhq\\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?postings/(" + UUID + ")");
    static final Pattern BAMBOOHR = Pattern.compile("(?i)https?://([a-z0-9-]+)\\.bamboohr\\.com/careers/(\\d+)");
    static final Pattern JAZZHR = Pattern.compile("(?i)https?://([a-z0-9-]+)\\.applytojob\\.com/apply/([a-z0-9]{6,})");
    static final Pattern LEVER_EU = Pattern.compile("(?i)https?://jobs\\.eu\\.lever\\.co/([^/?#]+)/(" + UUID + ")");
    static final Pattern WORKABLE_SHORT = Pattern.compile("(?i)https?://apply\\.workable\\.com/j/([0-9a-z]{6,})");
    static final Pattern WORKABLE_LONG = Pattern.compile("(?i)https?://apply\\.workable\\.com/([^/?#]+)/j/([0-9a-z]{6,})");

    /** The kinds this class reads an employer's list for. */
    static boolean reads(String kind) {
        return kind != null && List.of("smartrecruiters", "teamtailor", "rippling", "paylocity", "pinpoint", "bamboohr", "jazzhr").contains(kind);
    }

    // ---------------------------------------------------------------- an employer's list

    /**
     * The note on an employer whose SmartRecruiters careers page is not there: SmartRecruiters sends a reader to its own front page
     * (Publicis Groupe, Docplanner, Cint, 2026-11-27), while the postings themselves may still answer at their own addresses.
     */
    static final String NO_CAREERS_PAGE = "SmartRecruiters has no careers page under this name, so the library knows its postings only from a search.";

    /** An employer's openings on one of these systems; null when the system does not answer. */
    static Found read(Feed f, String company, List<String> words) {
        try {
            List<Opening> out = new ArrayList<>();
            String note = "", t = f.token();
            switch (f.kind()) {
                case "smartrecruiters" -> {
                    Page fp = CareerSources.page("https://careers.smartrecruiters.com/" + t, HTML);
                    if (fp == null || fp.status() != 200) return null;
                    if (!fp.url().toLowerCase(Locale.ROOT).startsWith(("https://careers.smartrecruiters.com/" + t).toLowerCase(Locale.ROOT)))
                        return new Found(company, f, List.of(), NO_CAREERS_PAGE);   // sent to SmartRecruiters' front page: there is no list, which says nothing of its postings (0.8.4)
                    String first = fp.body();
                    String name = name(first, "(?is)<title>\\s*Careers at (.+?)\\s*</title>");
                    String who = name.isEmpty() ? company : name;
                    Map<String, Opening> got = new LinkedHashMap<>();
                    String by = groupedBy(first);
                    int listed = smartRecruitersRows(first, who, f.label(), got, by);
                    Matcher more = Pattern.compile("data-groups-pages=\"(\\d+)\"[^>]*data-more=\"(/[^\"]+)\"").matcher(first);
                    if (more.find()) {
                        int pages = Integer.parseInt(more.group(1));
                        for (int p = 1; p < pages && p <= SMARTRECRUITERS_PAGES; p++) {
                            String b = CareerSources.body("https://careers.smartrecruiters.com" + CareerSources.unescape(more.group(2)) + "?page=" + p, HTML);
                            if (b == null) break;
                            listed += smartRecruitersRows(b, who, f.label(), got, by);
                        }
                        if (pages > SMARTRECRUITERS_PAGES + 1) note = "Its SmartRecruiters careers page has " + pages + " pages of openings; the library read the first " + (SMARTRECRUITERS_PAGES + 1) + ".";
                    }
                    if (note.isEmpty() && listed > got.size()) note = "Its SmartRecruiters careers page counts " + listed + " openings and shows " + got.size() + "; the library read those shown.";
                    int asked = 0;
                    for (Opening o : got.values()) {   // the page shows no day, and its place only as the board chose to group it: each posting says both
                        if (asked < SMARTRECRUITERS_POSTINGS_MAX && CareerSources.worthReading(o.title(), o.location(), words)) {
                            asked++;
                            Page at = CareerSources.page(o.url(), JSON);
                            if (at != null && (at.status() == 404 || at.status() == 410)) continue;   // the page still lists it; its own address says it is gone
                            JsonNode j = at == null || at.status() != 200 ? null : json(at.body());
                            if (j != null && !j.path("jobTitle").asText("").isBlank()) {
                                if (!j.path("isApplyAvailable").asBoolean(true)) continue;   // expired: the page still shows it, the posting takes no applications
                                String loc = j.path("jobAdLocation").asText("").strip();
                                out.add(CareerSources.opening(j.path("company").path("name").asText(o.company()), j.path("jobTitle").asText(o.title()), loc.isEmpty() ? o.location() : loc,
                                        o.remote() || CareerSources.remote(loc), o.url(), CareerSources.day(j.path("postedDate").asText("")), "", f.label(), smartRecruitersText(j), "", ""));
                                continue;
                            }
                        }
                        out.add(o);
                    }
                }
                case "teamtailor" -> {
                    String b = CareerSources.body("https://" + t + ".teamtailor.com/jobs.rss", Map.of("Accept", "application/rss+xml"));
                    if (b == null || !b.contains("<rss")) return null;
                    String head = b.contains("<item>") ? b.substring(0, b.indexOf("<item>")) : b;
                    String who = CareerSources.unescape(CareerSources.tag(head, "title"));
                    Matcher m = Pattern.compile("(?s)<item>(.*?)</item>").matcher(b);
                    while (m.find()) {
                        String it = m.group(1);
                        List<String> places = new ArrayList<>();
                        Matcher l = Pattern.compile("(?s)<tt:location>(.*?)</tt:location>").matcher(it);
                        while (l.find()) {
                            String one = CareerSources.joined(CareerSources.tag(l.group(1), "tt:city"), CareerSources.tag(l.group(1), "tt:country"));
                            places.add(CareerSources.unescape(one.isEmpty() ? CareerSources.tag(l.group(1), "tt:name") : one));
                        }
                        String loc = String.join("; ", places), status = CareerSources.tag(it, "remoteStatus");
                        out.add(CareerSources.opening(who.isBlank() ? company : who, CareerSources.unescape(CareerSources.tag(it, "title")), loc, status.equalsIgnoreCase("fully") || CareerSources.remote(loc),
                                CareerSources.tag(it, "link"), rfc1123(CareerSources.tag(it, "pubDate")), "", f.label(), CareerSources.plain(CareerSources.unescape(CareerSources.tag(it, "description"))), "", ""));
                    }
                }
                case "rippling" -> {
                    String b = CareerSources.body("https://api.rippling.com/platform/api/ats/v1/board/" + t + "/jobs", JSON);
                    if (b == null || !b.strip().startsWith("[")) return null;
                    Map<String, String[]> rows = new LinkedHashMap<>();   // one row a place: the same posting once, its places joined
                    for (JsonNode j : M.readTree(b)) {
                        String id = j.path("uuid").asText(""), place = j.path("workLocation").path("label").asText("").strip();
                        if (id.isEmpty()) continue;
                        String[] r = rows.computeIfAbsent(id, k -> new String[]{j.path("name").asText(""), "", j.path("url").asText(""), j.path("department").path("label").asText("")});
                        if (!place.isEmpty() && !List.of(r[1].split("; ")).contains(place)) r[1] = r[1].isEmpty() ? place : r[1] + "; " + place;
                    }
                    for (Map.Entry<String, String[]> e : rows.entrySet()) {
                        String[] r = e.getValue();
                        out.add(CareerSources.opening(company, r[0], r[1], CareerSources.remote(r[1]), r[2], "", "", f.label(), "",
                                "https://api.rippling.com/platform/api/ats/v1/board/" + t + "/jobs/" + e.getKey(), ""));
                    }
                }
                case "paylocity" -> {
                    String b = CareerSources.body("https://recruiting.paylocity.com/recruiting/jobs/All/" + t, HTML);
                    JsonNode d = pageData(b);
                    if (d == null) return null;
                    String who = d.path("ModuleTitle").asText("").strip();
                    for (JsonNode j : d.path("Jobs")) {
                        if (j.path("IsInternal").asBoolean(false)) continue;   // a posting for its own staff only
                        JsonNode at = j.path("JobLocation");
                        String loc = j.path("ShouldDisplayLocation").asBoolean(true) && !j.path("LocationName").asText("").isBlank() ? j.path("LocationName").asText("")
                                : CareerSources.joined(at.path("City").asText(""), at.path("State").asText(""), at.path("Country").asText(""));
                        String url = "https://recruiting.paylocity.com/Recruiting/Jobs/Details/" + j.path("JobId").asText("");
                        out.add(CareerSources.opening(who.isEmpty() ? company : who, j.path("JobTitle").asText(""), loc, j.path("IsRemote").asBoolean(false) || CareerSources.remote(loc), url,
                                CareerSources.day(j.path("PublishedDate").asText("")), "", f.label(), "", url, ""));
                    }
                }
                case "pinpoint" -> {
                    String b = CareerSources.body("https://" + t + ".pinpointhq.com/postings.json", JSON);
                    if (b == null) return null;
                    JsonNode d = M.readTree(b);
                    if (!d.path("data").isArray()) return null;
                    for (JsonNode j : d.path("data")) {
                        JsonNode at = j.path("location");
                        String loc = CareerSources.joined(at.path("city").asText(""), at.path("province").asText("").equals(at.path("city").asText("")) ? "" : at.path("province").asText(""));
                        if (loc.isEmpty()) loc = at.path("name").asText("").strip();   // the location's own name ("Remote" on a hybrid London post) only when it gives no city
                        StringBuilder text = new StringBuilder();
                        for (String k : List.of("description", "key_responsibilities", "skills_knowledge_expertise", "benefits")) {
                            String part = CareerSources.plain(j.path(k).asText(""));
                            if (part.isBlank()) continue;
                            String header = j.path(k + "_header").asText("");
                            text.append(header.isBlank() ? "" : header + "\n").append(part).append('\n');
                        }
                        String pay = j.path("compensation_visible").asBoolean(false) && !j.path("compensation_minimum").asText("").isBlank()
                                ? (j.path("compensation_currency").asText("") + " " + j.path("compensation_minimum").asText("") + " to " + j.path("compensation_maximum").asText("")
                                   + (j.path("compensation_frequency").asText("").isBlank() ? "" : " a " + j.path("compensation_frequency").asText(""))).strip() : "";
                        out.add(CareerSources.opening(company, j.path("title").asText(""), loc, j.path("workplace_type").asText("").equalsIgnoreCase("remote"), j.path("url").asText(""), "", "",
                                f.label(), text.toString(), j.path("url").asText(""), pay));
                    }
                }
                case "bamboohr" -> {
                    String b = CareerSources.body("https://" + t + ".bamboohr.com/careers/list", JSON);
                    if (b == null) return null;
                    JsonNode d = M.readTree(b);
                    if (!d.path("result").isArray()) return null;
                    for (JsonNode j : d.path("result")) {
                        JsonNode at = j.path("location"), ats = j.path("atsLocation");
                        String loc = CareerSources.joined(at.path("city").asText(""), at.path("state").asText(""));
                        if (loc.isEmpty()) loc = CareerSources.joined(ats.path("city").asText(""), ats.path("state").asText(ats.path("province").asText("")), ats.path("country").asText(""));
                        String id = j.path("id").asText("");
                        out.add(CareerSources.opening(company, j.path("jobOpeningName").asText(""), loc, bambooRemote(j) || CareerSources.remote(loc), "https://" + t + ".bamboohr.com/careers/" + id, "", "",
                                f.label(), "", "https://" + t + ".bamboohr.com/careers/" + id + "/detail", ""));
                    }
                }
                case "jazzhr" -> {
                    String b = CareerSources.body("https://" + t + ".applytojob.com/apply", HTML);
                    if (b == null) return null;
                    String name = name(b, "(?is)<title>\\s*(.+?)\\s+-\\s+Career Page\\s*</title>");
                    String who = name.isEmpty() ? company : CareerSources.unescape(name);
                    Matcher m = Pattern.compile("(?is)<a\\s+href=\"(https?://" + Pattern.quote(t) + "\\.applytojob\\.com/apply/[A-Za-z0-9]{6,}/[^\"]*)\"[^>]*>(.*?)</a>").matcher(b);
                    List<int[]> at = new ArrayList<>();
                    List<String[]> rows = new ArrayList<>();
                    while (m.find()) { at.add(new int[]{m.start(), m.end()}); rows.add(new String[]{m.group(1), CareerSources.plain(m.group(2)).strip()}); }
                    Map<String, Opening> got = new LinkedHashMap<>();
                    for (int i = 0; i < rows.size(); i++) {
                        String url = rows.get(i)[0], title = rows.get(i)[1];
                        if (title.isEmpty() || title.length() > 160 || got.containsKey(url)) continue;
                        String row = b.substring(at.get(i)[1], i + 1 < at.size() ? at.get(i + 1)[0] : Math.min(b.length(), at.get(i)[1] + 2000));
                        Matcher pm = Pattern.compile("(?is)fa-map-marker[^>]*></i>\\s*([^<]+)<").matcher(row);
                        String loc = pm.find() ? CareerSources.unescape(pm.group(1)).strip() : "";
                        got.put(url, CareerSources.opening(who, title, loc, CareerSources.remote(loc), url, "", "", f.label(), "", url, ""));
                    }
                    out.addAll(got.values());
                }
                default -> { return null; }
            }
            return new Found(company, f, out, note);
        } catch (Exception e) { return null; }
    }

    /** What a SmartRecruiters careers page groups its openings by ("Location", "Department"), as the button it shows pressed says; "" when it says none. */
    static String groupedBy(String html) {
        Matcher g = Pattern.compile("(?is)class=\"browse button[^\"]*is-active[^\"]*\">(.*?)</span>").matcher(html == null ? "" : html);
        return g.find() ? CareerSources.plain(g.group(1)).strip() : "";
    }

    /**
     * The rows of a SmartRecruiters careers page, or of one of its further pages, into {@code got}; the number of openings its groups say
     * they hold. The place: a group's heading when the page groups by location; else the place a row gives under its title, which a page
     * grouped by location fills with the kind of work ("Full-time", "Contract") instead, so it is taken only on a page grouped otherwise.
     */
    static int smartRecruitersRows(String html, String company, String source, Map<String, Opening> got, String by) {
        int counted = 0;
        boolean byPlace = by.equalsIgnoreCase("Location");
        Matcher sec = Pattern.compile("(?is)<section data-qty=\"(\\d+)\"[^>]*>(.*?)</section>").matcher(html);
        while (sec.find()) {
            counted += Integer.parseInt(sec.group(1));
            String section = sec.group(2);
            Matcher head = Pattern.compile("(?is)<h3[^>]*opening-title[^>]*>(.*?)</h3>").matcher(section);
            String heading = head.find() ? CareerSources.plain(head.group(1)).strip() : "";
            Matcher m = Pattern.compile("(?is)<li class=\"opening-job[^\"]*\">(.*?)</li>").matcher(section);
            while (m.find()) {
                String li = m.group(1);
                Matcher a = Pattern.compile("(?i)href=\"(https://jobs\\.smartrecruiters\\.com/[^\"]+)\"").matcher(li);
                Matcher h = Pattern.compile("(?is)<h4[^>]*>(.*?)</h4>").matcher(li);
                if (!a.find() || !h.find()) continue;
                Matcher p = Pattern.compile("(?is)<span class=\"spl-me-1\">(.*?)</span>").matcher(li);
                String under = p.find() ? CareerSources.plain(p.group(1)).strip() : "";
                String url = CareerSources.unescape(a.group(1)), loc = byPlace ? heading : under;
                boolean remote = li.contains("Employees can work remotely") || CareerSources.remote(loc);
                got.putIfAbsent(url, CareerSources.opening(company, CareerSources.plain(h.group(1)), loc, remote, url, "", "", source, "", url, ""));
            }
        }
        return counted;
    }

    /** BambooHR's own word for a remote opening: its remote flag, or the location type it gives remote openings. */
    static boolean bambooRemote(JsonNode j) { return j.path("isRemote").asBoolean(false) || j.path("locationType").asText("").equals("1"); }

    /** The data a Paylocity job list page carries for its own script ({@code window.pageData}); null when the page has none. */
    static JsonNode pageData(String html) {
        if (html == null) return null;
        int at = html.indexOf("window.pageData");
        if (at < 0) return null;
        int open = html.indexOf('{', at);
        if (open < 0) return null;
        try (JsonParser p = M.getFactory().createParser(html.substring(open))) { return M.readTree(p); }
        catch (Exception e) { return null; }
    }

    static String name(String html, String pattern) {
        Matcher m = Pattern.compile(pattern).matcher(html == null ? "" : html);
        return m.find() ? CareerSources.plain(m.group(1)).strip() : "";
    }

    /** A feed's day as an RSS feed writes it ("Wed, 18 Nov 2026 15:07:49 +0000"); "" when it is not one. */
    static String rfc1123(String s) {
        try { return ZonedDateTime.parse(s.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate().toString(); } catch (Exception e) { return ""; }
    }

    // ---------------------------------------------------------------- one posting, from its own address

    /**
     * One posting read from its own address: the board it belongs to, the opening, and whether the system still lists it (null when its
     * answer did not say). Null when the address is no posting of a system read this way, or the site's robots rules close it.
     */
    static Posting postingAt(String url) {
        if (url == null) return null;
        try {
            Matcher m;
            if ((m = WORKDAY.matcher(workdayPostingUrl(url))).find()) {
                String tenant = m.group(1).toLowerCase(Locale.ROOT), wd = m.group(2).toLowerCase(Locale.ROOT), site = m.group(3), rest = m.group(4);
                Feed f = new Feed("workday", tenant + "/" + wd + "/" + site, "its posting's address, " + url);
                Page p = workdayRecord("https://" + tenant + "." + wd + ".myworkdayjobs.com/wday/cxs/" + tenant + "/" + site + "/job/" + rest);
                if (p == null) return null;
                if (workdayGone(p)) return new Posting(f, null, false);   // Workday's own answer for a posting taken down
                if (p.status() != 200) return new Posting(f, null, null);   // a refusal (a 403 without S22, a tenant that does not let its records be read) says nothing of the posting (0.8.4)
                JsonNode j = json(p.body()), info = j == null ? null : j.path("jobPostingInfo");
                if (info == null || info.path("title").asText("").isBlank()) return new Posting(f, null, null);
                String loc = workdayPlace(info), where = info.path("remoteType").asText(""), org = organisation(j.path("hiringOrganization").path("name").asText(""));
                Opening o = CareerSources.opening(org.isEmpty() ? nameOf(tenant) : org, info.path("title").asText(""), loc, CareerSources.remote(loc) || workdayRemote(info),
                        url, CareerSources.day(info.path("startDate").asText("")), "", f.label(),
                        CareerSources.plain(info.path("jobDescription").asText("")) + (where.isBlank() ? "" : "\nWorkplace: " + where), "", "");
                return new Posting(f, o, info.path("posted").asBoolean(true) && info.path("canApply").asBoolean(true));
            }
            if ((m = SMARTRECRUITERS.matcher(url)).find()) {
                Feed f = new Feed("smartrecruiters", m.group(1), "its posting's address, " + url);
                Page p = CareerSources.page(url, JSON);
                if (p == null) return null;
                if (p.status() == 404 || p.status() == 410) return new Posting(f, null, false);
                JsonNode j = p.status() == 200 ? json(p.body()) : null;
                if (j == null || j.path("jobTitle").asText("").isBlank()) return new Posting(f, null, null);
                String loc = j.path("jobAdLocation").asText("").strip();
                Opening o = CareerSources.opening(j.path("company").path("name").asText(m.group(1)), j.path("jobTitle").asText(""), loc, CareerSources.remote(loc),
                        j.path("applyUrl").asText(url), CareerSources.day(j.path("postedDate").asText("")), "", f.label(), smartRecruitersText(j), "", "");
                return new Posting(f, o, j.path("isApplyAvailable").asBoolean(true));
            }
            if ((m = RIPPLING.matcher(url)).find()) {
                Feed f = new Feed("rippling", m.group(1).toLowerCase(Locale.ROOT), "its posting's address, " + url);
                Page p = CareerSources.page("https://api.rippling.com/platform/api/ats/v1/board/" + f.token() + "/jobs/" + m.group(2).toLowerCase(Locale.ROOT), JSON);
                if (p == null) return null;
                if (p.status() == 404) return new Posting(f, null, false);
                JsonNode j = p.status() == 200 ? json(p.body()) : null;
                if (j == null || j.path("name").asText("").isBlank()) return new Posting(f, null, null);
                return new Posting(f, ripplingOpening(j, f, url), true);
            }
            if ((m = BAMBOOHR.matcher(url)).find()) {
                Feed f = new Feed("bamboohr", m.group(1).toLowerCase(Locale.ROOT), "its posting's address, " + url);
                Page p = CareerSources.page("https://" + f.token() + ".bamboohr.com/careers/" + m.group(2) + "/detail", JSON);
                if (p == null) return null;
                if (p.status() == 404) return new Posting(f, null, false);
                JsonNode j = p.status() == 200 ? json(p.body()) : null, jo = j == null ? null : j.path("result").path("jobOpening");
                if (jo == null || jo.path("jobOpeningName").asText("").isBlank()) return new Posting(f, null, null);
                JsonNode at = jo.path("location");
                String loc = CareerSources.joined(at.path("city").asText(""), at.path("state").asText(""), at.path("addressCountry").asText(""));
                Opening o = CareerSources.opening(nameOf(f.token()), jo.path("jobOpeningName").asText(""), loc, bambooRemote(jo) || CareerSources.remote(loc),
                        jo.path("jobOpeningShareUrl").asText(url), CareerSources.day(jo.path("datePosted").asText("")), "", f.label(), CareerSources.plain(jo.path("description").asText("")), "",
                        jo.path("compensation").asText(""));
                return new Posting(f, o, jo.path("jobOpeningStatus").asText("Open").equalsIgnoreCase("Open"));
            }
            if ((m = LEVER_EU.matcher(url)).find()) {
                Feed f = new Feed("levereu", m.group(1).toLowerCase(Locale.ROOT), "its posting's address, " + url);
                Page p = CareerSources.page("https://api.eu.lever.co/v0/postings/" + f.token() + "/" + m.group(2).toLowerCase(Locale.ROOT), JSON);
                if (p == null) return null;
                if (p.status() == 404) return new Posting(f, null, false);
                JsonNode j = p.status() == 200 ? json(p.body()) : null;
                if (j == null || j.path("text").asText("").isBlank()) return new Posting(f, null, null);
                return new Posting(f, CareerSources.lever(j, nameOf(f.token()), f.label()), true);
            }
            if ((m = WORKABLE_SHORT.matcher(url)).find() || (m = WORKABLE_LONG.matcher(url)).find()) {   // the short address names no account: where it leads names it
                String code = m.group(m.groupCount()).toUpperCase(Locale.ROOT), account = m.groupCount() == 2 ? m.group(1).toLowerCase(Locale.ROOT) : "";
                if (account.isEmpty()) {
                    Page p = CareerSources.page(url, HTML);
                    if (p == null) return null;
                    Matcher led = WORKABLE_LONG.matcher(p.url() == null ? "" : p.url());
                    if (!led.find()) return new Posting(null, null, p.status() == 404 || p.status() == 410 ? Boolean.FALSE : null);
                    account = led.group(1).toLowerCase(Locale.ROOT);
                }
                Feed f = new Feed("workable", account, "its posting's address, " + url);
                return inList(f, code, url);
            }
            String kind = (m = TEAMTAILOR.matcher(url)).find() ? "teamtailor" : (m = PINPOINT.matcher(url)).find() ? "pinpoint" : (m = JAZZHR.matcher(url)).find() ? "jazzhr"
                    : (m = PAYLOCITY.matcher(url)).find() ? "paylocity" : "";
            if (kind.isEmpty()) return null;
            Page p = CareerSources.page(url, HTML);   // the posting's own page and the JobPosting data it carries for search engines
            if (p == null) return null;
            Feed f = kind.equals("paylocity") ? paylocityFeed(p.body(), url) : new Feed(kind, m.group(1).toLowerCase(Locale.ROOT), "its posting's address, " + url);
            if (p.status() == 404 || p.status() == 410) return new Posting(f, null, false);
            if (p.status() != 200) return new Posting(f, null, null);
            List<Opening> jp = CareerSources.jobPostings(p.body(), url, "");
            Opening o = jp.size() == 1 && !jp.get(0).title().isBlank() ? jp.get(0) : null;
            if (o != null && f != null) o = new Opening(o.id(), o.company().isBlank() ? nameOf(f.token()) : o.company(), o.title(), o.location(), o.remote(), url, o.posted(), o.postedNote(), f.label(), o.text(), "", o.pay());
            if (kind.equals("paylocity")) {   // a Paylocity posting's page does not say whether it is still listed: its employer's list does
                if (f == null) return new Posting(null, o, null);
                Posting listed = inList(f, m.group(1), url);
                return new Posting(f, o != null ? o : listed.opening(), listed.listed());
            }
            if (o == null) return new Posting(f, null, null);
            boolean expired = expired(p.body());
            return new Posting(f, o, !expired);
        } catch (Exception e) { return null; }
    }

    /** The posting with this id on the board's list, read now: listed or not; null for the answer when the board does not answer. */
    static Posting inList(Feed f, String id, String url) {
        Found all = CareerSources.read(f, nameOf(f.token()), List.of());
        if (all == null) return new Posting(f, null, null);
        for (Opening o : all.openings()) if (id.equalsIgnoreCase(CareerSources.postingId(o.url()))) return new Posting(f, o, true);
        return new Posting(f, null, false);
    }

    /** Whether a page's JobPosting data says the posting closed: a validThrough day before today. */
    static boolean expired(String html) {
        Matcher v = Pattern.compile("\"validThrough\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})").matcher(html == null ? "" : html);
        return v.find() && LocalDate.parse(v.group(1)).isBefore(LocalDate.now());
    }

    /** A Paylocity posting's board: the employer's job list its page links to; null when the page names none. */
    static Feed paylocityFeed(String html, String url) {
        Matcher m = PAYLOCITY_LIST.matcher(html == null ? "" : html);
        return m.find() ? new Feed("paylocity", m.group(1).toLowerCase(Locale.ROOT), "its posting's page, " + url) : null;
    }

    /**
     * Whether the system still lists a posting, by the posting's own answer, where that answer says it and the employer's list is long or
     * read only by search: Workday's record, SmartRecruiters' posting, Rippling's, BambooHR's and Lever's posting services. Null for any
     * other address, or when the answer did not say: the employer's list, read now, decides then.
     */
    static Boolean listedAt(String url) {
        if (url == null) return null;
        if (!(WORKDAY.matcher(url).find() || SMARTRECRUITERS.matcher(url).find() || RIPPLING.matcher(url).find() || BAMBOOHR.matcher(url).find() || LEVER_EU.matcher(url).find())) return null;
        Posting p = postingAt(url);
        return p == null ? null : p.listed();
    }

    /** A board's feed for a posting address that names none: Workable's short address, by where it leads; a Paylocity posting, by the list its page links. */
    static Feed feedOfPosting(String url) {
        if (url == null || !(WORKABLE_SHORT.matcher(url).find() || PAYLOCITY.matcher(url).find())) return null;
        try {
            Page p = CareerSources.page(url, HTML);
            if (p == null || p.status() != 200) return null;
            if (PAYLOCITY.matcher(url).find()) return paylocityFeed(p.body(), url);
            Matcher led = WORKABLE_LONG.matcher(p.url() == null ? "" : p.url());
            return led.find() ? new Feed("workable", led.group(1).toLowerCase(Locale.ROOT), "where its posting's address leads, " + p.url()) : null;
        } catch (Exception e) { return null; }
    }

    /** The name a board gives itself where its list says it: a Teamtailor feed, a Paylocity list, a SmartRecruiters or JazzHR careers page; "" otherwise. */
    static String boardName(Feed f) {
        try {
            return switch (f.kind()) {
                case "teamtailor" -> { String b = CareerSources.body("https://" + f.token() + ".teamtailor.com/jobs.rss", Map.of("Accept", "application/rss+xml")); yield b == null ? "" : CareerSources.unescape(CareerSources.tag(b.contains("<item>") ? b.substring(0, b.indexOf("<item>")) : b, "title")); }
                case "paylocity" -> { JsonNode d = pageData(CareerSources.body("https://recruiting.paylocity.com/recruiting/jobs/All/" + f.token(), HTML)); yield d == null ? "" : d.path("ModuleTitle").asText("").strip(); }
                case "smartrecruiters" -> name(CareerSources.body("https://careers.smartrecruiters.com/" + f.token(), HTML), "(?is)<title>\\s*Careers at (.+?)\\s*</title>");
                case "jazzhr" -> CareerSources.unescape(name(CareerSources.body("https://" + f.token() + ".applytojob.com/apply", HTML), "(?is)<title>\\s*(.+?)\\s+-\\s+Career Page\\s*</title>"));
                default -> "";
            };
        } catch (Exception e) { return ""; }
    }

    /** An opening's words where the list gave none: SmartRecruiters', Rippling's and BambooHR's posting answers; null for any other source. */
    static String detail(Opening o) {
        String src = o.source();
        try {
            if (src.startsWith("smartrecruiters:")) { String b = CareerSources.body(o.detail(), JSON); return b == null ? "" : smartRecruitersText(M.readTree(b)); }
            if (src.startsWith("rippling:")) { String b = CareerSources.body(o.detail(), JSON); return b == null ? "" : ripplingText(M.readTree(b)); }
            if (src.startsWith("bamboohr:")) { String b = CareerSources.body(o.detail(), JSON); return b == null ? "" : CareerSources.plain(M.readTree(b).path("result").path("jobOpening").path("description").asText("")); }
        } catch (Exception e) { return ""; }
        return null;
    }

    static String smartRecruitersText(JsonNode j) {
        StringBuilder b = new StringBuilder();
        for (String k : List.of("jobDescription", "qualifications", "additionalInformation", "companyDescription")) {
            JsonNode s = j.path("content").path("sections").path(k);
            String text = CareerSources.plain(s.path("text").asText(""));
            if (!text.isBlank()) b.append(s.path("title").asText("")).append('\n').append(text).append('\n');
        }
        return b.toString().strip();
    }

    static Opening ripplingOpening(JsonNode j, Feed f, String url) {
        List<String> places = new ArrayList<>();
        for (JsonNode w : j.path("workLocations")) if (!w.asText("").isBlank()) places.add(w.asText().strip());
        String loc = String.join("; ", places), pay = "";
        JsonNode range = j.path("payRangeDetails").path(0);
        if (range.isObject() && !range.path("rangeStart").asText("").isBlank())
            pay = (range.path("currency").asText("") + " " + range.path("rangeStart").asText("") + " to " + range.path("rangeEnd").asText("") + (range.path("frequency").asText("").isBlank() ? "" : " " + range.path("frequency").asText("").toLowerCase(Locale.ROOT))).strip();
        return CareerSources.opening(j.path("companyName").asText(nameOf(f.token())), j.path("name").asText(""), loc, CareerSources.remote(loc), j.path("url").asText(url),
                CareerSources.day(j.path("createdOn").asText("")), "", f.label(), ripplingText(j), "", pay);
    }

    static String ripplingText(JsonNode j) {
        JsonNode d = j.path("description");
        return (CareerSources.plain(d.path("role").asText("")) + "\n" + CareerSources.plain(d.path("company").asText(""))).strip();
    }

    // ---------------------------------------------------------------- Workday's records

    /**
     * A Workday posting's own address from any page of it (0.8.4): the part before its apply flow (/apply, /apply/applyManually,
     * /apply/useMyLastApplication), with no slash at the end. A record asked by an apply page's address answered 406 (HTTP_406) or 404
     * (S21, "not found: Job_Posting_Anchor_ID=apply..."), which says nothing of the posting.
     */
    static String workdayPostingUrl(String url) {
        if (url == null) return null;
        return url.replaceFirst("(?i)/apply(?:/[^?#]*)?(?=[?#]|$)", "").replaceFirst("/+(?=[?#]|$)", "");
    }

    /** A Workday record, read once in ten minutes for one address. */
    static Page workdayRecord(String recordUrl) {
        Kept k = RECORDS.get(recordUrl);
        if (k != null && System.currentTimeMillis() - k.at() < RECORD_KEEP_MS) return k.page();
        Page p = CareerSources.page(recordUrl, JSON);
        if (p != null) { if (RECORDS.size() > 500) RECORDS.clear(); RECORDS.put(recordUrl, new Kept(System.currentTimeMillis(), p)); }
        return p;
    }

    /**
     * Whether a Workday record's answer is Workday's own word that the posting was taken down (0.8.4): 403 with errorCode S22, or 404
     * with HTTP_404 or S21. A 403 without it (a tenant or a burst refused, a proxy's page), a "later", an answer the library cannot read
     * say nothing of the posting: it is then checked on its board's list, and a row past a list keeps what the list gave.
     */
    static boolean workdayGone(Page p) {
        if (p == null || p.status() != 403 && p.status() != 404) return false;
        JsonNode j = json(p.body());
        String code = j == null ? "" : j.path("errorCode").asText("");
        return p.status() == 403 ? code.equals("S22") : code.equals("HTTP_404") || code.equals("S21");
    }

    /** Every place a Workday record names: its first, then the others it lists, joined by "; ". */
    static String workdayPlace(JsonNode info) {
        List<String> places = new ArrayList<>();
        String first = info.path("location").asText("").strip();
        if (!first.isEmpty()) places.add(first);
        for (JsonNode a : info.path("additionalLocations")) { String x = a.asText("").strip(); if (!x.isEmpty() && !places.contains(x)) places.add(x); }
        return String.join("; ", places);
    }

    /** Whether a Workday record calls the work remote; its remoteType holds other words at some employers ("Engineering"), which say nothing. */
    static boolean workdayRemote(JsonNode info) {
        String where = info.path("remoteType").asText("").toLowerCase(Locale.ROOT);
        return where.equals("remote") || where.contains("fully remote");
    }

    /**
     * The places of a Workday list row that lists several, from its record: {places, "remote" or ""}; {"gone", ""} when the record says the
     * posting was taken down; null when the record could not be read.
     */
    static String[] workdayPlaces(String recordUrl) {
        Page p = workdayRecord(recordUrl);
        if (p == null) return null;
        if (workdayGone(p)) return new String[]{"gone", ""};
        if (p.status() != 200) return null;   // refused: the row keeps what the list gave, its first place and how many more
        JsonNode j = json(p.body()), info = j == null ? null : j.path("jobPostingInfo");
        if (info == null || info.path("title").asText("").isBlank()) return null;
        if (!info.path("posted").asBoolean(true)) return new String[]{"gone", ""};
        String places = workdayPlace(info);
        return places.isEmpty() ? null : new String[]{places, workdayRemote(info) ? "remote" : ""};
    }

    /**
     * A row in several places whose record was not read: its first place as its address spells it (US-VA-Ashburn-1-DC1 is "US VA Ashburn 1
     * DC1") and how many more, "(+3)"; "" when its address names none.
     */
    static String workdayFirstPlace(String externalPath, String locationsText) {
        Matcher m = Pattern.compile("^/job/([^/]+)/[^/]+$").matcher(externalPath == null ? "" : externalPath);
        if (!m.find()) return "";
        Matcher n = Pattern.compile("(\\d+)").matcher(locationsText == null ? "" : locationsText);
        int more = n.find() ? Integer.parseInt(n.group(1)) - 1 : 0;
        return m.group(1).replace('-', ' ').replaceAll("\\s+", " ").strip() + (more > 0 ? " (+" + more + ")" : "");
    }

    /** The hiring organisation a Workday posting's own record names, without the code some employers put before it; "" when the record says none or was not read. */
    static String workdayEmployer(String postingUrl) {
        Matcher m = WORKDAY.matcher(postingUrl == null ? "" : workdayPostingUrl(postingUrl));
        if (!m.find()) return "";
        String tenant = m.group(1).toLowerCase(Locale.ROOT), wd = m.group(2).toLowerCase(Locale.ROOT);
        Page p = workdayRecord("https://" + tenant + "." + wd + ".myworkdayjobs.com/wday/cxs/" + tenant + "/" + m.group(3) + "/job/" + m.group(4));
        JsonNode j = p == null || p.status() != 200 ? null : json(p.body());
        return j == null ? "" : organisation(j.path("hiringOrganization").path("name").asText(""));
    }

    /** An organisation's name as a record gives it, without the internal code some employers put first: "0101 The Huntington National Bank", "LE043 Quality Technology Services, LLC". */
    static String organisation(String name) {
        return (name == null ? "" : name).strip().replaceFirst("^(?:[A-Za-z]{1,4})?\\d{2,6}\\s+", "").strip();
    }

    // ---------------------------------------------------------------- a system's own boards

    /**
     * Whether a board is its hiring system's own demonstration or training board rather than an employer's: the name the board goes by holds
     * the system's name and more words, and its name in the system's addresses begins with the system's name and goes on (Lever's "Lever
     * Implementation Training Environment", at jobs.lever.co/leverdemo-8, whose postings go back to 2019). The system's own careers board,
     * named as the system itself (jobs.lever.co/lever), is an employer's, and so is a company whose name only begins with the system's
     * ("Leverage").
     */
    static boolean ownBoard(Feed f, String name) {
        String system = f.system();
        if (system == null || system.isBlank() || system.contains(" ") || name == null || name.isBlank()) return false;   // the careers pages' own data and links name no system
        String sk = CareerSearch.key(system), nk = CareerSearch.key(name), tk = CareerSearch.key(f.token().split("[./]")[0]);
        if (sk.length() < 4 || nk.equals(sk) || CareerSources.bare(name).equals(sk) || !CareerCut.has(name, system)) return false;
        return tk.startsWith(sk) && !tk.equals(sk);
    }

    static String ownBoardNote(Feed f) { return "This board is " + f.system() + "'s own demonstration board, not an employer's, so its openings are not read."; }

    /** An employer's name from its name in a system's addresses, when nothing better is said: proximo is Proximo; a numbered tail dropped. */
    static String nameOf(String token) {
        String t = (token == null ? "" : token).replaceFirst("-\\d{6,}$", "").replace('-', ' ').replace('_', ' ').strip();
        return t.isEmpty() ? "" : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    static JsonNode json(String body) {
        try { return body == null || !body.strip().startsWith("{") ? null : M.readTree(body); } catch (Exception e) { return null; }
    }
}
