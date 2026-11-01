package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Discussion threads read in full, from the two places that still let a program read them. Reddit, through the Arctic Shift archive: a
 * third-party copy of Reddit minutes to hours behind, kept by one person with no promise of uptime; Reddit's own pages are never read,
 * its feeds close on 2026-11-13 and its public API in March 2027. The Stack Exchange sites, through their own API: free, 300 calls a day
 * without a key and 10,000 with one; the text is CC BY-SA, so a use cites the thread's address and the author. Nothing is stored here.
 */
public final class Forums {
    private Forums() { }

    static final ObjectMapper M = new ObjectMapper();
    /** Each service's base address; a test points them at its own server. */
    public static final Map<String, String> BASE = new HashMap<>();
    static { BASE.put("reddit_archive", "https://arctic-shift.photon-reddit.com"); BASE.put("stackexchange", "https://api.stackexchange.com/2.3"); }
    public static final String STACK_KEY = "RESEARCHZOSHO_STACKEXCHANGE_KEY", REDDIT_ARCHIVE = "RESEARCHZOSHO_REDDIT_ARCHIVE";
    public static final String ARCHIVE_NOTE = "read from the Arctic Shift archive, a copy of Reddit minutes to hours behind, with the votes as archived; Reddit's own pages are never read";
    public static final String STACK_NOTE = "read through the Stack Exchange API; the text is CC BY-SA, so cite the thread's address and the author";
    static final int PAGE = 100, MOST_REPLIES = 60;
    /** The Stack Exchange API's calls left today, from its last answer; -1 before any. */
    static volatile int stackQuotaLeft = -1;

    /** One thread: where it is (r/name, or the site's host), its id, title, the opening post, who wrote it, its score, how many replies, when, and its address. */
    public record Topic(String site, String id, String title, String text, String author, int score, int replies, String date, String url) { }
    /** One reply: who, score, when, the text, whether it is the accepted answer, how deep in the tree (0 = a direct reply), what it is (reply, answer, comment), its address. */
    public record Reply(String author, int score, String date, String text, boolean accepted, int depth, String kind, String url) { }
    /** A thread read in full. */
    public record Read(Topic topic, List<Reply> replies) { }

    static String setting(String key) { String v = Config.get(key); return v == null ? "" : v.strip(); }
    /** Whether Reddit is read through the archive (the default) or only pointed at. */
    public static boolean redditArchiveOn() { return !setting(REDDIT_ARCHIVE).equalsIgnoreCase("off"); }
    public static boolean stackKeyed() { return !setting(STACK_KEY).isEmpty(); }
    public static int stackQuotaLeft() { return stackQuotaLeft; }

    // ---- Reddit, through the archive ----

    /** The name of a subreddit from what a person writes: "selfhosted", "r/selfhosted", "https://www.reddit.com/r/selfhosted/". */
    public static String subredditName(String s) {
        String n = s == null ? "" : s.strip().replaceFirst("^(?i)(https?://)?(www\\.|old\\.)?reddit\\.com/?", "").replaceFirst("^/?(?i)r/", "").replaceAll("/.*$", "").strip();
        return n.equalsIgnoreCase("reddit") ? "" : n;   // "reddit" alone names the site, not a subreddit
    }

    /** Whether what a person wrote names Reddit or a subreddit. */
    public static boolean isReddit(String site) {
        String s = site == null ? "" : site.strip().toLowerCase(Locale.ROOT);
        return s.equals("reddit") || s.startsWith("r/") || s.startsWith("/r/") || s.contains("reddit.com");
    }

    /**
     * The threads of a subreddit for the words, in the title or the opening post, from the last {@code days} days, best by score first;
     * with no words, the newest hundred, best first. The archive searches one subreddit at a time.
     */
    public static List<Topic> redditThreads(String subreddit, String words, int days, int limit) throws IOException {
        String sub = subredditName(subreddit);
        if (sub.isEmpty()) throw new IllegalArgumentException("a subreddit is needed: the archive searches one subreddit at a time");
        long after = Instant.now().getEpochSecond() - (long) Math.max(1, days) * 86400L;
        Map<String, Topic> seen = new LinkedHashMap<>();
        // the title search is quick; the search of the posts' text is the archive's slow one, so it runs only when the titles gave fewer than asked
        for (String field : words == null || words.isBlank() ? List.of("") : List.of("title", "selftext")) {
            if (field.equals("selftext") && seen.size() >= limit) break;
            String url = BASE.get("reddit_archive") + "/api/posts/search?subreddit=" + enc(sub) + (field.isEmpty() ? "" : "&" + field + "=" + enc(words.strip())) + "&after=" + after + "&limit=" + PAGE + "&sort=desc";
            JsonNode got;
            try { got = getJsonPatiently(url); } catch (IOException e) { if (field.equals("selftext") && !seen.isEmpty()) break; throw e; }   // the text search timing out loses nothing the titles found
            for (JsonNode p : got.path("data")) { Topic t = post(p); if (!t.id().isEmpty()) seen.putIfAbsent(t.id(), t); }
        }
        List<Topic> out = new ArrayList<>(seen.values());
        out.sort(Comparator.comparingInt(Topic::score).reversed().thenComparing(Topic::date, Comparator.reverseOrder()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, Math.max(1, limit))) : out;
    }

    /** Threads by their Reddit ids, as the archive holds them, in the order given; one the archive lacks is left out. */
    public static List<Topic> redditByIds(List<String> ids) throws IOException {
        List<Topic> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 25) {
            List<String> slice = ids.subList(i, Math.min(ids.size(), i + 25));
            Map<String, Topic> got = new HashMap<>();
            for (JsonNode p : getJson(BASE.get("reddit_archive") + "/api/posts/ids?ids=" + enc(String.join(",", slice))).path("data")) { Topic t = post(p); got.put(t.id(), t); }
            for (String id : slice) if (got.containsKey(id)) out.add(got.get(id));
        }
        return out;
    }

    /** The ids of the Reddit threads a text names by their addresses (reddit.com/r/…/comments/id/…), each once, in order. */
    public static List<String> redditIdsIn(String text) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher m = Pattern.compile("reddit\\.com/r/[^/\\s]+/comments/([a-z0-9]{4,10})").matcher(text == null ? "" : text);
        while (m.find()) ids.add(m.group(1));
        return new ArrayList<>(ids);
    }

    /** One thread with its comments: the direct replies by score, each followed by the replies to it in the tree's order, up to {@link #MOST_REPLIES}. */
    public static Read redditThread(String idOrUrl) throws IOException {
        List<String> ids = redditIdsIn(idOrUrl);
        String id = ids.isEmpty() ? idOrUrl.strip().replaceFirst("^t3_", "") : ids.get(0);
        if (!id.matches("[a-z0-9]{4,10}")) throw new IllegalArgumentException("a thread's id is needed, the letters after /comments/ in its address");
        List<Topic> ts = redditByIds(List.of(id));
        if (ts.isEmpty()) throw new IOException("the archive holds no thread " + id);
        JsonNode tree = getJson(BASE.get("reddit_archive") + "/api/comments/tree?link_id=t3_" + enc(id) + "&limit=" + PAGE).path("data");
        List<JsonNode> top = new ArrayList<>();
        tree.forEach(top::add);
        top.sort(Comparator.comparingInt((JsonNode n) -> n.path("data").path("score").asInt(0)).reversed());
        List<Reply> replies = new ArrayList<>();
        for (JsonNode n : top) { if (replies.size() >= MOST_REPLIES) break; walk(n, 0, replies, ts.get(0).url()); }
        return new Read(ts.get(0), replies);
    }

    static void walk(JsonNode node, int depth, List<Reply> out, String threadUrl) {
        if (out.size() >= MOST_REPLIES) return;
        JsonNode d = node.path("data");
        String body = d.path("body").asText("");
        if (!body.isEmpty() && !body.equals("[removed]") && !body.equals("[deleted]"))
            out.add(new Reply(d.path("author").asText(""), d.path("score").asInt(0), date(d.path("created_utc").asLong(0)), body, false, depth, "reply", threadUrl + d.path("id").asText("") + "/"));
        JsonNode reps = d.path("replies");
        for (JsonNode k : reps.isArray() ? reps : reps.path("data").path("children")) walk(k, depth + 1, out, threadUrl);
    }

    static Topic post(JsonNode p) {
        String text = p.path("selftext").asText("");
        if (text.equals("[removed]") || text.equals("[deleted]")) text = "";
        String permalink = p.path("permalink").asText("");
        return new Topic("r/" + p.path("subreddit").asText(""), p.path("id").asText(""), p.path("title").asText(""), text, p.path("author").asText(""), p.path("score").asInt(0),
                p.path("num_comments").asInt(0), date(p.path("created_utc").asLong(0)), permalink.isEmpty() ? p.path("url").asText("") : "https://www.reddit.com" + permalink);
    }

    // ---- the Stack Exchange sites, through their API ----

    static final Map<String, String> STACK_NAMES = Map.ofEntries(Map.entry("the workplace", "workplace"), Map.entry("software engineering", "softwareengineering"), Map.entry("stack overflow", "stackoverflow"),
            Map.entry("server fault", "serverfault"), Map.entry("super user", "superuser"), Map.entry("ask ubuntu", "askubuntu"), Map.entry("math overflow", "mathoverflow"), Map.entry("ask different", "apple"),
            Map.entry("unix & linux", "unix"), Map.entry("cross validated", "stats"), Map.entry("information security", "security"), Map.entry("database administrators", "dba"), Map.entry("user experience", "ux"),
            Map.entry("personal finance & money", "money"), Map.entry("english language & usage", "english"), Map.entry("mathematics", "math"), Map.entry("physics", "physics"), Map.entry("academia", "academia"));

    /** The API's name of a Stack Exchange site from what a person writes: "workplace", "The Workplace", "workplace.stackexchange.com", "stackoverflow.com". */
    public static String stackSite(String site) {
        String s = site == null ? "" : site.strip().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("^www\\.", "").replaceAll("/.*$", "");
        if (STACK_NAMES.containsKey(s)) return STACK_NAMES.get(s);
        if (s.endsWith(".stackexchange.com")) return s.substring(0, s.length() - ".stackexchange.com".length());
        for (String own : List.of("stackoverflow.com", "superuser.com", "serverfault.com", "askubuntu.com", "mathoverflow.net", "stackapps.com")) if (s.equals(own)) return s.substring(0, s.indexOf('.'));
        return s.replaceAll("[\\s_-]", "");
    }

    /** The threads of a Stack Exchange site for the words, best by votes first. */
    public static List<Topic> stackThreads(String site, String words, int limit) throws IOException {
        String s = stackSite(site);
        if (s.isEmpty()) throw new IllegalArgumentException("a site is needed: workplace, softwareengineering, stackoverflow, or any Stack Exchange site's name");
        if (words == null || words.isBlank()) throw new IllegalArgumentException("words are needed: a Stack Exchange site is searched by words");
        JsonNode d = getJson(BASE.get("stackexchange") + "/search/advanced?order=desc&sort=votes&q=" + enc(words.strip()) + "&site=" + enc(s) + "&filter=withbody&pagesize=" + Math.max(1, Math.min(limit, 50)) + key());
        quota(d);
        List<Topic> out = new ArrayList<>();
        for (JsonNode q : d.path("items")) out.add(question(s, q));
        return out;
    }

    /** One question with its answers by votes, the accepted one marked, then the comments on the question. */
    public static Read stackThread(String site, String idOrUrl) throws IOException {
        String s = stackSite(site), id = idOrUrl == null ? "" : idOrUrl.strip();
        Matcher m = Pattern.compile("/(?:questions|q)/(\\d+)").matcher(id);
        if (m.find()) { if (id.contains("://")) s = stackSite(id); id = m.group(1); }
        if (!id.matches("\\d+")) throw new IllegalArgumentException("a question's number is needed, the number after /questions/ in its address");
        if (s.isEmpty()) throw new IllegalArgumentException("a site is needed: workplace, softwareengineering, stackoverflow, or any Stack Exchange site's name");
        JsonNode q = getJson(BASE.get("stackexchange") + "/questions/" + id + "?site=" + enc(s) + "&filter=withbody" + key());
        quota(q);
        if (q.path("items").isEmpty()) throw new IOException("no question " + id + " on " + s);
        Topic t = question(s, q.path("items").get(0));
        List<Reply> replies = new ArrayList<>();
        JsonNode a = getJson(BASE.get("stackexchange") + "/questions/" + id + "/answers?site=" + enc(s) + "&filter=withbody&order=desc&sort=votes&pagesize=30" + key());
        quota(a);
        for (JsonNode x : a.path("items"))
            replies.add(new Reply(x.path("owner").path("display_name").asText(""), x.path("score").asInt(0), date(x.path("creation_date").asLong(0)), plain(x.path("body").asText("")), x.path("is_accepted").asBoolean(false), 0, "answer", t.url() + "#" + x.path("answer_id").asText("")));
        JsonNode c = getJson(BASE.get("stackexchange") + "/questions/" + id + "/comments?site=" + enc(s) + "&filter=withbody&order=desc&sort=votes&pagesize=10" + key());
        quota(c);
        for (JsonNode x : c.path("items"))
            replies.add(new Reply(x.path("owner").path("display_name").asText(""), x.path("score").asInt(0), date(x.path("creation_date").asLong(0)), plain(x.path("body").asText("")), false, 0, "comment", t.url()));
        return new Read(t, replies);
    }

    static Topic question(String site, JsonNode q) {
        String link = q.path("link").asText("");
        String host = link.replaceFirst("^https?://", "").replaceAll("/.*$", "");
        return new Topic(host.isEmpty() ? site : host, q.path("question_id").asText(""), unescape(q.path("title").asText("")), plain(q.path("body").asText("")), unescape(q.path("owner").path("display_name").asText("")),
                q.path("score").asInt(0), q.path("answer_count").asInt(0), date(q.path("creation_date").asLong(0)), link);
    }

    static String key() { return stackKeyed() ? "&key=" + enc(setting(STACK_KEY)) : ""; }
    static void quota(JsonNode d) { if (d.has("quota_remaining")) stackQuotaLeft = d.path("quota_remaining").asInt(-1); }

    // ---- shared ----

    static String date(long epoch) { return epoch <= 0 ? "" : Instant.ofEpochSecond(epoch).atZone(ZoneOffset.UTC).toLocalDate().toString(); }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** HTML entities as the Stack Exchange API writes them, back to text. */
    public static String unescape(String s) {
        if (s == null || s.indexOf('&') < 0) return s == null ? "" : s;
        Matcher m = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);").matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String e = m.group(1), r;
            try {
                r = switch (e) { case "amp" -> "&"; case "lt" -> "<"; case "gt" -> ">"; case "quot" -> "\""; case "apos" -> "'"; case "nbsp" -> " ";
                    default -> new String(Character.toChars(e.startsWith("#x") ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1)))); };
            } catch (RuntimeException bad) { r = m.group(0); }
            m.appendReplacement(b, Matcher.quoteReplacement(r));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** A post's HTML as text: paragraphs and list items on their own lines, code kept, tags gone. */
    public static String plain(String html) {
        if (html == null || html.isEmpty()) return "";
        String t = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</(p|div|li|h\\d|pre|blockquote|tr)>", "\n").replaceAll("(?i)<li[^>]*>", "- ")
                .replaceAll("<[^>]+>", "");
        return unescape(t).replaceAll("[ \\t]+", " ").replaceAll(" *\n *", "\n").replaceAll("\n{3,}", "\n\n").strip();
    }

    /** The archive answers a search it is too busy for with 422 "Timeout. Maybe slow down a bit": two more tries after pauses of 4 and 8 seconds, then the failure is the answer. */
    static JsonNode getJsonPatiently(String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) { try { Thread.sleep(4000L * attempt); } catch (InterruptedException i) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); } }
            try { return getJson(url); }
            catch (IOException e) {
                String m = e.getMessage() == null ? "" : e.getMessage();
                if (!m.contains("Timeout") && !m.contains("slow down") && !m.contains("HTTP 429")) throw e;
                last = e;
            }
        }
        throw last;
    }

    static JsonNode getJson(String url) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", Shops.UA).header("Accept", "application/json").header("Accept-Encoding", "gzip").GET().build();
            HttpResponse<byte[]> r = Shops.HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = r.body();
            if (r.headers().firstValue("Content-Encoding").orElse("").toLowerCase(Locale.ROOT).contains("gzip") && body.length > 1) {
                try (GZIPInputStream g = new GZIPInputStream(new ByteArrayInputStream(body))) { body = g.readAllBytes(); }
            }
            String text = new String(body, StandardCharsets.UTF_8);
            JsonNode j = null;
            try { j = M.readTree(text); } catch (IOException notJson) { /* the status says what happened */ }
            boolean failed = r.statusCode() != 200 || j == null || (j.has("error") && j.path("error").isTextual()) || j.has("error_message");
            if (failed) {
                String said = j == null ? "" : j.path("error_message").asText(j.path("error").asText(""));
                throw new IOException("HTTP " + r.statusCode() + (said.isBlank() ? (j == null ? ": not JSON" : "") : ": " + Shops.clean(said.length() > 200 ? said.substring(0, 200) : said)));
            }
            return j;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }
}
