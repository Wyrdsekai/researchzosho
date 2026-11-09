package org.researchzosho.records;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.researchzosho.tools.SearchTally;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The European Patent Office's Open Patent Services, as a prior-art enumeration reads them: one page of a search with its total, a
 * publication's claims, its family, and a class of the scheme. What the office's reference guide (version 1.3.19) and the service itself
 * say, and what this client is built around: a search gives at most a hundred records a request and never past the two-thousandth of a
 * query, so a class and a span of years over that is split by its caller; the {@code biblio} constituent already carries the title and
 * the abstract with the search, six to nine kilobytes a record as JSON, where XML is more than twice that; the free allowance is four
 * gigabytes of answers a week and about 450 megabytes an hour, each answer saying how much is used; and each answer's
 * {@code X-Throttling-Control} says how many requests a minute the office takes of each service just now (a search five when it is
 * overloaded, fifteen when busy, thirty when idle), which this client stays well under and never above ten searches a minute, the
 * office's own figure for fair use. Full text, the claims, is held for EP, WO and some thirty national offices, not for the United
 * States, China, Japan or Korea: a caller reads the family member whose text the office does hold. A key and a secret go only to the
 * office, over https; neither is ever written anywhere.
 */
public final class Ops {
    private Ops() { }
    private static final ObjectMapper J = new ObjectMapper();

    /** The service's address; a test points it at a server of its own. */
    public static volatile String base = "https://ops.epo.org/3.2";
    public static final String KEY_NAME = "RESEARCHZOSHO_EPO_KEY", SECRET_NAME = "RESEARCHZOSHO_EPO_SECRET", TALLY = "EPO Open Patent Services";
    /** The most records one request of a search gives, and the last record of a query the office gives at all. */
    public static final int PAGE = 100, CEILING = 2000;
    /** The free allowance, in bytes of answers a week, and the part of it past which an enumeration stops rather than use the week up. */
    public static final long WEEK_BYTES = 4L * 1024 * 1024 * 1024, WEEK_STOP = WEEK_BYTES / 10 * 9;
    /** The share of the office's requests a minute this client uses, and how the pace is scaled (a test sets it to nothing). */
    static final double SHARE = 0.6;
    public static volatile double pace = 1.0;
    /** The authorities whose full text the office holds, as its guide lists them: EP and WO first, the order a family member is tried in. */
    public static final List<String> FULL_TEXT = List.of("EP", "WO", "GB", "CA", "FR", "ES", "AT", "CH", "BE", "DK", "SE", "NO", "IE", "IT", "PT", "PL", "GR", "LU", "MC", "CZ", "SK", "BG", "RO", "RS", "HR", "EE", "LT", "CY", "MD", "ME");
    public static final String USER_AGENT = "ResearchZosho-research/1.0 (https://researchzosho.org)";

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    /** Whether a key and a secret are set. */
    public static boolean keyed() { return !setting(KEY_NAME).isEmpty() && !setting(SECRET_NAME).isEmpty(); }

    private static String setting(String name) { String v = Config.get(name); return v == null ? "" : v.strip(); }

    /** The one sentence that says what is missing and how to get it. */
    public static String missing() {
        return "The patent offices' records are read through the European Patent Office's Open Patent Services, which needs a free key and secret that are not set here: "
                + "register at https://developers.epo.org, add an app there, and give its consumer key and consumer secret with researchzosho records key epo-query <key> <secret>, or on the Sources page.";
    }

    /** The office would not answer: its allowance is used, it refused the key, or it failed. The message is a sentence and names no key. */
    public static final class Refused extends IOException {
        public Refused(String message) { super(message); }
    }

    // ---- what the office said of its allowance and its load, in its last answer

    private static volatile long weekUsed = -1, hourUsed = -1;
    private static volatile String state = "";
    /** Bytes of the week's allowance used, as the office's last answer said; -1 before any answer. */
    public static long weekUsed() { return weekUsed; }
    public static long hourUsed() { return hourUsed; }
    /** idle, busy or overloaded, as the office's last answer said; "" before any. */
    public static String state() { return state; }

    /** The requests a minute the office last said it takes of each service, and when each service was last asked. */
    private static final Map<String, Integer> PER_MINUTE = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    private static final Pattern THROTTLE = Pattern.compile("(\\w+)=(green|yellow|red|black):(\\d+)");

    static String serviceOf(String path) {
        if (path.startsWith("/published-data/search")) return "search";
        if (path.startsWith("/published-data/")) return "retrieval";
        if (path.startsWith("/family/") || path.startsWith("/legal/")) return "inpadoc";
        return "other";
    }

    /** The milliseconds between two requests of a service: well under what the office last said it takes, and never more than ten searches a minute. */
    static long interval(String service) {
        int limit = PER_MINUTE.getOrDefault(service, switch (service) { case "search" -> 5; case "inpadoc" -> 30; case "retrieval" -> 50; default -> 200; });
        long byLimit = limit <= 0 ? 60_000 : (long) (60_000 / (limit * SHARE));
        long floor = switch (service) { case "search" -> 6_000; case "inpadoc", "retrieval" -> 1_000; default -> 500; };
        return (long) (Math.max(floor, byLimit) * pace);
    }

    private static void paceBefore(String service) throws InterruptedException {
        long wait;
        synchronized (LAST) {
            long now = System.currentTimeMillis(), due = LAST.getOrDefault(service, 0L) + interval(service);
            wait = Math.max(0, due - now);
            LAST.put(service, now + wait);
        }
        if (wait > 0) Stopping.sleep(wait);
    }

    static void readHeaders(HttpResponse<?> r) {
        r.headers().firstValue("X-RegisteredQuotaPerWeek-Used").ifPresent(v -> { try { weekUsed = Long.parseLong(v.strip()); } catch (NumberFormatException ignored) { } });
        r.headers().firstValue("X-IndividualQuotaPerHour-Used").ifPresent(v -> { try { hourUsed = Long.parseLong(v.strip()); } catch (NumberFormatException ignored) { } });
        r.headers().firstValue("X-Throttling-Control").ifPresent(v -> {
            String s = v.strip();
            int paren = s.indexOf('(');
            if (paren > 0) state = s.substring(0, paren).strip().toLowerCase(Locale.ROOT);
            Matcher m = THROTTLE.matcher(s);
            while (m.find()) {
                int limit = Integer.parseInt(m.group(3));
                // red is three quarters of the allowance used: half the pace; black is the service suspended for this client
                PER_MINUTE.put(m.group(1), switch (m.group(2)) { case "red" -> Math.max(1, limit / 2); case "black" -> 0; default -> limit; });
            }
        });
    }

    // ---- the token

    private record Token(String value, Instant until, String forKey) { }
    private static volatile Token token;

    private static String token() throws IOException, InterruptedException {
        String key = setting(KEY_NAME), secret = setting(SECRET_NAME);
        if (key.isEmpty() || secret.isEmpty()) throw new Refused(missing());
        Token t = token;
        if (t != null && t.forKey().equals(key + "@" + base) && Instant.now().isBefore(t.until())) return t.value();
        URI at = URI.create(base + "/auth/accesstoken");
        boolean local = "127.0.0.1".equals(at.getHost()) || "localhost".equals(at.getHost());
        if (!"https".equals(at.getScheme()) && !local) throw new Refused("A key and a secret are only sent over https, and the patent office's address here is not.");
        String basic = Base64.getEncoder().encodeToString((key + ":" + secret).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(at).timeout(Duration.ofSeconds(30)).header("Authorization", "Basic " + basic).header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", USER_AGENT).POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build();
        HttpResponse<String> r = Stopping.send(HTTP, req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), Duration.ofSeconds(40), "the European Patent Office");
        if (r.statusCode() == 400 || r.statusCode() == 401) throw new Refused("The European Patent Office refused the key and the secret set for it (researchzosho records key epo-query <key> <secret> sets them again).");
        if (r.statusCode() >= 400) throw new Refused("The European Patent Office did not give a token (HTTP " + r.statusCode() + ").");
        JsonNode n = J.readTree(r.body());
        String value = n.path("access_token").asText("");
        if (value.isBlank()) throw new Refused("The European Patent Office gave no token for the key and the secret set for it.");
        token = new Token(value, Instant.now().plusSeconds(Math.max(60, n.path("expires_in").asLong(1200)) - 60), key + "@" + base);
        return value;
    }

    /** Whether the office takes the key and the secret: a token is asked for, which costs nothing of the allowance; what its last answer said of the week's use, "" when it has said nothing yet. */
    public static String accepted() throws IOException, InterruptedException { token = null; token(); return allowanceSaid(); }

    /** One answer: its status and its body. */
    record Answer(int status, String body) { }

    /** Bytes of answers this process has fetched, for the callers that say what a step cost. */
    private static volatile long fetched = 0, calls = 0;
    public static long fetchedBytes() { return fetched; }
    public static long callsMade() { return calls; }

    /**
     * One request, paced by its service: the answer whole on 200 and on 404, which is the office's "nothing here"; a token that ran out is
     * taken again once; a suspended service is waited for once, up to ninety seconds; a used-up allowance and every other refusal is said
     * as a sentence.
     */
    static Answer get(String path, String accept) throws IOException, InterruptedException {
        String service = serviceOf(path);
        for (int attempt = 1; ; attempt++) {
            String bearer = token();
            paceBefore(service);
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/rest-services" + path)).timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + bearer).header("Accept", accept).header("User-Agent", USER_AGENT).GET().build();
            HttpResponse<byte[]> r = Stopping.send(HTTP, req, HttpResponse.BodyHandlers.ofByteArray(), Duration.ofSeconds(120), "the European Patent Office");
            readHeaders(r);
            calls++;
            fetched += r.body() == null ? 0 : r.body().length;
            SearchTally.count(TALLY, 1);
            String body = r.body() == null ? "" : new String(r.body(), StandardCharsets.UTF_8);
            int status = r.statusCode();
            if (status == 200 || status == 404) return new Answer(status, body);
            if ((status == 401 || (status == 400 && body.toLowerCase(Locale.ROOT).contains("access"))) && attempt == 1) { token = null; continue; }   // a token that ran out early: one new token, one more try
            String reason = r.headers().firstValue("X-Rejection-Reason").orElse("");
            if (status == 403 || status == 429) {
                if (reason.contains("Week")) throw new Refused("The European Patent Office's free allowance for this key, four gigabytes of answers a week, is used up; it is given again at midnight UTC on Sunday.");
                if (reason.contains("Hour")) throw new Refused("The European Patent Office's hourly allowance for this key, about 450 megabytes, is used up; it comes back within the hour.");
                long retry = r.headers().firstValue("Retry-After").map(v -> { try { long x = Long.parseLong(v.strip()); return x > 600 ? x : x * 1000; } catch (NumberFormatException e) { return 30_000L; } }).orElse(30_000L);
                if (attempt == 1 && retry <= 90_000) { Stopping.sleep((long) (retry * Math.min(1.0, pace))); continue; }
                throw new Refused("The European Patent Office is taking no more requests from this key just now" + (reason.isEmpty() ? "" : " (" + reason + ")") + "; the same call a few minutes later goes on from where this one stopped.");
            }
            if (status == 400) return new Answer(status, body);   // a query the office does not take: the caller says which
            if (status >= 500 && attempt == 1) { Stopping.sleep((long) (5_000 * Math.min(1.0, pace))); continue; }
            throw new Refused("The European Patent Office answered with an error (HTTP " + status + ").");
        }
    }

    // ---- the search

    /**
     * One publication as the search gives it: who published it under which number and kind, the office's family, the days it was
     * published, filed and first claimed priority, its title and abstract in English and in another language where the office has them,
     * its applicants, and the classes it sits in. Nothing of how often it is cited is read.
     */
    public record Publication(String country, String number, String kind, String family, String published, String filed, String priority,
                              String titleEn, String titleOther, String abstractEn, String abstractOther, List<String> applicants, List<String> classes) {
        /** The number as the office's own search and its full text take it: the country and the number, EP3991548. */
        public String epodoc() { return country + number; }
        /** The number with its kind, as a person writes it: EP3991548A1. */
        public String id() { return country + number + kind; }
        public String title() { return !titleEn.isEmpty() ? titleEn : titleOther; }
        public String summary() { return !abstractEn.isEmpty() ? abstractEn : abstractOther; }
        /** The office's own page for the publication, by its number. */
        public String link() { return Ops.link(id()); }
    }

    /** The office's own page for a publication, by its number with its kind. */
    public static String link(String id) { return "https://worldwide.espacenet.com/patent/search?q=pn%3D" + URLEncoder.encode(id, StandardCharsets.UTF_8); }

    /** One page of a search: how many the office counts for the query, the range this page holds, its publications, and the bytes it cost. */
    public record Page(int total, int first, int last, List<Publication> publications, long bytes) { }

    /**
     * One page of a search in the office's own query language, records {@code first} to {@code last} counted from 1, at most a hundred
     * and never past the two-thousandth. A query that finds nothing is a page with a total of nothing.
     */
    public static Page search(String cql, int first, int last) throws IOException, InterruptedException {
        if (last > CEILING || last - first + 1 > PAGE || first < 1) throw new IllegalArgumentException("a search page is at most " + PAGE + " records and ends at or before record " + CEILING);
        Answer a = get("/published-data/search/biblio?q=" + URLEncoder.encode(cql, StandardCharsets.UTF_8).replace("+", "%20") + "&Range=" + first + "-" + last, "application/json");
        if (a.status() == 404) return new Page(0, first, first - 1, List.of(), a.body().length());
        if (a.status() == 400) throw new Refused("The European Patent Office did not take the query " + cql + " (it answered that the request was invalid).");
        return page(a.body(), first);
    }

    static Page page(String body, int first) throws IOException {
        JsonNode s = J.readTree(body).path("ops:world-patent-data").path("ops:biblio-search");
        int total = s.path("@total-result-count").asInt(0);
        List<Publication> out = new ArrayList<>();
        for (JsonNode x : each(s.path("ops:search-result").path("exchange-documents"))) for (JsonNode d : each(x.path("exchange-document"))) { Publication p = publication(d); if (p != null) out.add(p); }
        int begin = s.path("ops:range").path("@begin").asInt(first), end = s.path("ops:range").path("@end").asInt(first + out.size() - 1);
        return new Page(total, begin, end, out, body.getBytes(StandardCharsets.UTF_8).length);
    }

    /** A part of an answer made from XML as a list: a list as it is, one thing as a list of one, nothing as an empty list. */
    static List<JsonNode> each(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        if (n == null || n.isMissingNode() || n.isNull()) return out;
        if (n.isArray()) n.forEach(out::add); else out.add(n);
        return out;
    }

    static String text(JsonNode n) { return n == null || n.isMissingNode() ? "" : n.isValueNode() ? n.asText("") : n.path("$").asText(""); }

    static String day(String yyyymmdd) {
        String d = yyyymmdd == null ? "" : yyyymmdd.strip();
        return d.matches("\\d{8}") ? d.substring(0, 4) + "-" + d.substring(4, 6) + "-" + d.substring(6) : d.matches("\\d{4}-\\d{2}-\\d{2}") ? d : "";
    }

    static Publication publication(JsonNode d) {
        String country = d.path("@country").asText(""), number = d.path("@doc-number").asText(""), kind = d.path("@kind").asText("");
        if (country.isEmpty() || number.isEmpty()) return null;   // "This record is not available through OPS"
        JsonNode b = d.path("bibliographic-data");
        String published = "";
        for (JsonNode id : each(b.path("publication-reference").path("document-id"))) if (published.isEmpty()) published = day(text(id.path("date")));
        String filed = "";
        for (JsonNode id : each(b.path("application-reference").path("document-id"))) if (filed.isEmpty()) filed = day(text(id.path("date")));
        String priority = "";
        for (JsonNode c : each(b.path("priority-claims").path("priority-claim"))) for (JsonNode id : each(c.path("document-id"))) { String p = day(text(id.path("date"))); if (!p.isEmpty() && (priority.isEmpty() || p.compareTo(priority) < 0)) priority = p; }
        String titleEn = "", titleOther = "";
        for (JsonNode t : each(b.path("invention-title"))) { String v = clean(text(t)); if (v.isEmpty()) continue; if (t.path("@lang").asText("").equalsIgnoreCase("en")) { if (titleEn.isEmpty()) titleEn = v; } else if (titleOther.isEmpty()) titleOther = v; }
        String abstractEn = "", abstractOther = "";
        for (JsonNode a : each(d.path("abstract"))) {
            StringBuilder v = new StringBuilder();
            for (JsonNode p : each(a.path("p"))) { String t = clean(text(p)); if (!t.isEmpty()) v.append(v.isEmpty() ? "" : " ").append(t); }
            if (v.isEmpty()) continue;
            if (a.path("@lang").asText("").equalsIgnoreCase("en")) { if (abstractEn.isEmpty()) abstractEn = v.toString(); } else if (abstractOther.isEmpty()) abstractOther = v.toString();
        }
        Set<String> applicants = new LinkedHashSet<>();
        for (JsonNode a : each(b.path("parties").path("applicants").path("applicant"))) if (a.path("@data-format").asText("epodoc").equals("epodoc")) { String name = clean(text(a.path("applicant-name").path("name"))).replaceFirst("\\s*\\[[A-Z]{2}]$", ""); if (!name.isEmpty() && applicants.size() < 3) applicants.add(name); }
        Set<String> classes = new LinkedHashSet<>();
        for (JsonNode c : each(b.path("patent-classifications").path("patent-classification"))) {
            if (!c.path("classification-scheme").path("@scheme").asText("").startsWith("CPC")) continue;
            String symbol = text(c.path("section")) + text(c.path("class")) + text(c.path("subclass")) + text(c.path("main-group")) + "/" + text(c.path("subgroup"));
            if (symbol.length() > 5 && classes.size() < 12) classes.add(symbol);
        }
        return new Publication(country, number, kind, d.path("@family-id").asText(""), published, filed, priority, titleEn, titleOther, abstractEn, abstractOther, List.copyOf(applicants), List.copyOf(classes));
    }

    /** Markup out and whitespace collapsed; the office's paragraph numbers ("[0001]") off the front. */
    static String clean(String s) { return s == null ? "" : s.replaceAll("<[^>]{1,40}>", " ").replaceAll("\\s+", " ").replaceFirst("^\\[\\d{3,4}]\\s*", "").strip(); }

    /** One publication's record by its number (EP3991548, or with its kind): the same facts a search gives; null when the office has no such number. */
    public static Publication biblio(String epodoc) throws IOException, InterruptedException {
        Answer a = get("/published-data/publication/epodoc/" + enc(epodoc) + "/biblio", "application/json");
        if (a.status() != 200) return null;
        for (JsonNode x : each(J.readTree(a.body()).path("ops:world-patent-data").path("exchange-documents"))) for (JsonNode d : each(x.path("exchange-document"))) { Publication p = publication(d); if (p != null) return p; }
        return null;
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    // ---- the claims

    /** A publication's claims as the office holds them: the number they were read from, their language, and each claim's text in order. */
    public record Claims(String publication, String lang, List<String> claims) { }

    /** The claims of a publication by its number (EP3991548); null when the office holds no text for it, which is so for every US, CN, JP and KR number. */
    public static Claims claims(String epodoc) throws IOException, InterruptedException {
        Answer a = get("/published-data/publication/epodoc/" + enc(epodoc) + "/claims", "application/json");
        if (a.status() != 200) return null;
        return claimsOf(a.body(), epodoc);
    }

    static Claims claimsOf(String body, String asked) throws IOException {
        JsonNode best = null; String lang = "", number = asked;
        for (JsonNode doc : each(J.readTree(body).path("ops:world-patent-data").path("ftxt:fulltext-documents").path("ftxt:fulltext-document"))) {
            JsonNode id = doc.path("bibliographic-data").path("publication-reference").path("document-id");
            String n = text(id.path("country")) + text(id.path("doc-number")) + text(id.path("kind"));
            for (JsonNode c : each(doc.path("claims"))) {
                String l = c.path("@lang").asText("").toUpperCase(Locale.ROOT);
                if (best == null || (l.equals("EN") && !lang.equals("EN"))) { best = c; lang = l; if (n.length() > 2) number = n; }
            }
        }
        if (best == null) return null;
        StringBuilder all = new StringBuilder();
        List<String> parts = new ArrayList<>();
        for (JsonNode c : each(best.path("claim"))) for (JsonNode t : each(c.path("claim-text"))) { String v = text(t).strip(); if (!v.isEmpty()) { parts.add(v); all.append(v).append('\n'); } }
        if (parts.isEmpty()) return null;
        List<String> claims = numbered(all.toString());
        if (claims.size() < 2 && parts.size() > 1) { claims = new ArrayList<>(); for (String p : parts) claims.add(clean(plainSpaces(p)).replaceFirst("^\\d{1,3}\\s*[.)]\\s*", "")); }
        return new Claims(number, lang.isEmpty() ? "" : lang.toLowerCase(Locale.ROOT), claims);
    }

    private static final Pattern CLAIM_START = Pattern.compile("(?m)^\\s*(\\d{1,3})\\s*[.)]\\s*(?=\\S)");
    /** The words a claims text opens with before claim 1, which a scan often runs into the number: "CLAIMS1. A bowl". */
    private static final Pattern CLAIMS_HEAD = Pattern.compile("(?i)^\\s*(?:claims|what is claimed is|what we claim is|we claim|i claim|patentanspr(?:u|ü)che|revendications)\\s*:?\\s*(?=\\d{1,3}\\s*[.)])");

    /** A claims text with the spaces a scan leaves made plain ones, and the words before claim 1 taken off. */
    static String plainSpaces(String text) { return CLAIMS_HEAD.matcher(text.replaceAll("[\\u00a0\\u2000-\\u200b\\u202f\\u3000\\ufeff]", " ")).replaceFirst(""); }

    /**
     * A claims text cut into its claims by their numbers: a line that begins with the next number in order starts the next claim, so a
     * figure inside a claim ("2. 5 mm") starts none. The words before claim 1 ("CLAIMS", "What is claimed is:") are left out, also
     * where a scan ran them into the number.
     */
    public static List<String> numbered(String text) {
        text = plainSpaces(text);
        List<String> out = new ArrayList<>();
        Matcher m = CLAIM_START.matcher(text);
        int expected = 1, from = -1;
        while (m.find()) {
            if (Integer.parseInt(m.group(1)) != expected) continue;
            if (from >= 0) out.add(clean(text.substring(from, m.start())));
            from = m.end(); expected++;
        }
        if (from >= 0) out.add(clean(text.substring(from)));
        return out;
    }

    // ---- the family

    /** One member of a publication's family: its number, kind and day, and the office's family it belongs to. */
    public record Member(String country, String number, String kind, String published, String family) {
        public String epodoc() { return country + number; }
        public String id() { return country + number + kind; }
    }

    /** The members of a publication's family as the office's family service lists them; empty when it lists none. */
    public static List<Member> family(String country, String number, String kind) throws IOException, InterruptedException {
        Answer a = get("/family/publication/docdb/" + enc(country) + "." + enc(number) + (kind.isEmpty() ? "" : "." + enc(kind)), "application/json");
        if (a.status() != 200) return List.of();
        return members(a.body());
    }

    static List<Member> members(String body) throws IOException {
        List<Member> out = new ArrayList<>();
        for (JsonNode m : each(J.readTree(body).path("ops:world-patent-data").path("ops:patent-family").path("ops:family-member"))) {
            for (JsonNode id : each(m.path("publication-reference").path("document-id"))) {
                if (!id.path("@document-id-type").asText("").equals("docdb")) continue;
                String c = text(id.path("country")), n = text(id.path("doc-number"));
                if (!c.isEmpty() && !n.isEmpty()) out.add(new Member(c, n, text(id.path("kind")), day(text(id.path("date"))), m.path("@family-id").asText("")));
            }
        }
        return out;
    }

    // ---- the scheme

    /** A class of the scheme: its symbol, its title, and the classes directly under it, each [symbol, title]. */
    public record Scheme(String symbol, String title, List<String[]> under) { }

    /** A class's title and the classes directly under it, from the office's scheme; null when the office knows no such class. */
    public static Scheme scheme(String symbol) throws IOException, InterruptedException {
        Answer a = get("/classification/cpc/" + symbol + "?depth=1", "application/cpc+xml");
        if (a.status() != 200 || !a.body().contains("classification-item")) return null;
        try { return schemeOf(a.body(), symbol); } catch (IOException e) { throw e; } catch (Exception e) { return null; }
    }

    static Scheme schemeOf(String xml, String symbol) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setXIncludeAware(false); f.setExpandEntityReferences(false);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        var items = doc.getElementsByTagName("cpc:classification-item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            if (!symbol.equalsIgnoreCase(child(item, "cpc:classification-symbol"))) continue;
            List<String[]> under = new ArrayList<>();
            for (Node n = item.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e && e.getTagName().equals("cpc:classification-item")) under.add(new String[]{child(e, "cpc:classification-symbol"), title(e)});
            return new Scheme(symbol, title(item), under);
        }
        return null;
    }

    /** The collection that searches the classification by words: its answer is the main groups, and {@link #subgroupsSaid} adds what stands under them. */
    public static final String CLASSIFICATION = "epo-classification";
    /** How many of the groups a classification search found are shown with their subgroups, how many lines of each, and how wide a title. */
    static final int GROUPS_SHOWN = 2, LINES_A_GROUP = 32, TITLE_WIDTH = 64;

    /** A group of the scheme with everything under it, in the scheme's order: each [depth below the group, symbol, title]; empty when the office does not say. */
    public static List<String[]> tree(String symbol) throws IOException, InterruptedException {
        Answer a = get("/classification/cpc/" + symbol + "?depth=all", "application/cpc+xml");
        if (a.status() != 200 || !a.body().contains("classification-item")) return List.of();
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false); f.setExpandEntityReferences(false);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(a.body().getBytes(StandardCharsets.UTF_8)));
            var items = doc.getElementsByTagName("cpc:classification-item");
            List<String[]> out = new ArrayList<>();
            int top = -1;
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                int level; try { level = Integer.parseInt(item.getAttribute("level")); } catch (NumberFormatException e) { continue; }
                String sym = child(item, "cpc:classification-symbol");
                if (top < 0) { if (!sym.equalsIgnoreCase(symbol)) continue; top = level; }
                if (level < top) break;
                out.add(new String[]{String.valueOf(level - top), sym, title(item)});
            }
            return out;
        } catch (IOException e) { throw e; } catch (Exception e) { return List.of(); }
    }

    /**
     * What stands under the first groups a classification search found, from the office's scheme, as lines a model reads: the search
     * gives main groups, and the class a pool is enumerated by is most often a subgroup. "" for any other collection, or when the
     * office does not say.
     */
    public static String subgroupsSaid(String collection, List<String> symbols) {
        if (!CLASSIFICATION.equals(collection) || symbols.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        int groups = 0;
        for (String symbol : symbols) {
            if (groups >= GROUPS_SHOWN) break;
            if (!symbol.matches("[A-HY]\\d{2}[A-Z]\\d{1,4}/\\d{2,6}")) continue;
            List<String[]> tree;
            try { tree = tree(symbol); } catch (IOException e) { break; } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            if (tree.size() < 2) continue;
            groups++;
            for (int i = 0; i < Math.min(tree.size(), LINES_A_GROUP); i++) {
                String[] t = tree.get(i);
                String title = t[2].length() > TITLE_WIDTH ? t[2].substring(0, TITLE_WIDTH - 1) + "…" : t[2];
                b.append("  ".repeat(Integer.parseInt(t[0]))).append(t[1]).append(' ').append(title).append('\n');
            }
            if (tree.size() > LINES_A_GROUP) b.append("  and ").append(tree.size() - LINES_A_GROUP).append(" more under ").append(symbol).append('\n');
        }
        return b.isEmpty() ? "" : "\n\nWhat stands under the first " + (groups == 1 ? "group" : "groups") + " in the office's scheme, each line a class of its own; give patents the one the invention sits in, with its slash:\n" + b.toString().stripTrailing();
    }

    private static String child(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element c && c.getTagName().equals(tag)) return c.getTextContent().strip();
        return "";
    }

    /** A class's title: its parts joined, each part's own words without the references and notes inside it. */
    private static String title(Element item) {
        List<String> parts = new ArrayList<>();
        for (Node n = item.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element t) || !t.getTagName().equals("cpc:class-title")) continue;
            for (Node p = t.getFirstChild(); p != null; p = p.getNextSibling()) {
                if (!(p instanceof Element part) || !part.getTagName().equals("cpc:title-part")) continue;
                StringBuilder b = new StringBuilder();
                var texts = part.getElementsByTagName("cpc:text");
                for (int k = 0; k < texts.getLength(); k++) { Node x = texts.item(k); if (x.getParentNode() == part || (x.getParentNode() instanceof Element pe && pe.getTagName().equals("cpc:comment") && pe.getParentNode() == part)) b.append(b.isEmpty() ? "" : " ").append(own(x)); }
                String s = b.toString().replaceAll("\\s+", " ").strip();
                if (!s.isEmpty()) parts.add(s);
            }
        }
        return String.join("; ", parts);
    }

    /** An element's own words, the text directly in it, without what its child elements hold. */
    private static String own(Node x) {
        StringBuilder b = new StringBuilder();
        for (Node n = x.getFirstChild(); n != null; n = n.getNextSibling()) if (n.getNodeType() == Node.TEXT_NODE) b.append(n.getNodeValue());
        return b.toString().strip();
    }

    /** The headers and counters back to nothing, for a test that points the client at another server. */
    public static void forget() { token = null; weekUsed = -1; hourUsed = -1; state = ""; PER_MINUTE.clear(); LAST.clear(); fetched = 0; calls = 0; }

    /** For the pages kept under a library: a publication as JSON, and back. */
    public static JsonNode json(Publication p) {
        var o = J.createObjectNode().put("country", p.country()).put("number", p.number()).put("kind", p.kind()).put("family", p.family()).put("published", p.published()).put("filed", p.filed()).put("priority", p.priority())
                .put("title_en", p.titleEn()).put("title_other", p.titleOther()).put("abstract_en", p.abstractEn()).put("abstract_other", p.abstractOther());
        var a = o.putArray("applicants"); p.applicants().forEach(a::add);
        var c = o.putArray("classes"); p.classes().forEach(c::add);
        return o;
    }

    public static Publication publicationOf(JsonNode o) {
        List<String> applicants = new ArrayList<>(), classes = new ArrayList<>();
        o.path("applicants").forEach(x -> applicants.add(x.asText()));
        o.path("classes").forEach(x -> classes.add(x.asText()));
        return new Publication(o.path("country").asText(""), o.path("number").asText(""), o.path("kind").asText(""), o.path("family").asText(""), o.path("published").asText(""), o.path("filed").asText(""), o.path("priority").asText(""),
                o.path("title_en").asText(""), o.path("title_other").asText(""), o.path("abstract_en").asText(""), o.path("abstract_other").asText(""), applicants, classes);
    }

    /** A number as megabytes with one decimal, for the sentences that say what a step cost. */
    public static String megabytes(long bytes) { return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0)); }

    /** What the last answer said of the week's allowance, as a clause; "" before any answer said it. */
    public static String allowanceSaid() {
        long w = weekUsed;
        return w < 0 ? "" : megabytes(w) + " MB of the office's free 4,096 MB a week are used";
    }

    static Map<String, Integer> perMinute() { return new LinkedHashMap<>(PER_MINUTE); }
}
