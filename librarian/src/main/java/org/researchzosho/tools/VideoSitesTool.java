package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Stopping;
import org.researchzosho.librarian.Fence;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code video_sites_search}: the video sites beside YouTube that answer a search without a key — Niconico (Japan), Nebula (the
 * catalogue is open; watching needs a subscription), PeerTube (every instance, through the Sepia Search index), Odysee, Dailymotion
 * and the Internet Archive's films. Measured 2026-10-07: each answers in about a second. What comes back is each site's own text,
 * fenced as such; the run reads it as evidence, never as instruction.
 */
public final class VideoSitesTool implements Tool {

    static final int MOST = 20, MOST_CALLS = 16;
    static final List<String> SITES = List.of("niconico", "nebula", "peertube", "odysee", "dailymotion", "archive");
    static volatile String NICONICO = "https://snapshot.search.nicovideo.jp", NEBULA = "https://content.api.nebula.app", SEPIA = "https://sepiasearch.org",
            ODYSEE = "https://api.na-backend.odysee.com", DAILYMOTION = "https://api.dailymotion.com";
    private static final ObjectMapper M = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();

    public record Row(String site, String title, String who, String date, int seconds, long views, String url, String description) { }

    @Override public String name() { return "video_sites_search"; }

    @Override public String description() {
        return "Search the video sites other than YouTube: Niconico (Japanese), Nebula (independent creators; its catalogue is open, watching needs a "
                + "subscription; its search matches loosely, so read the titles), PeerTube (all instances), Odysee, Dailymotion, and the Internet Archive's films. site=all asks them all. Rows show "
                + "the channel, the day, the length and views where the site gives them; web_fetch a video's address for its page.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("query").put("type", "string").put("description", "the words, in the language the videos would be in");
        ObjectNode site = props.putObject("site").put("type", "string").put("description", "all (default) | niconico | nebula | peertube | odysee | dailymotion | archive");
        site.withArray("enum").add("all"); for (String s : SITES) site.withArray("enum").add(s);
        props.putObject("limit").put("type", "integer").put("description", "rows per site, up to " + MOST + " (default 6)");
        p.putArray("required").add("query");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String query = args.path("query").asText("").strip(), site = args.path("site").asText("all").strip().toLowerCase();
        if (query.isEmpty()) return "ERROR: query is empty";
        if (calls.incrementAndGet() > MOST_CALLS) return "ERROR: this run has made its " + MOST_CALLS + " video-site searches; write with what was found.";
        int limit = Math.max(1, Math.min(MOST, args.path("limit").asInt(6)));
        List<String> asked = site.equals("all") ? SITES : SITES.contains(site) ? List.of(site) : null;
        if (asked == null) return "ERROR: site must be all or one of " + String.join(", ", SITES);
        List<Row> rows = new ArrayList<>(); List<String> silent = new ArrayList<>(), answered = new ArrayList<>();
        for (String s : asked) {
            List<Row> got = switch (s) {
                case "niconico" -> niconico(query, limit);
                case "nebula" -> nebula(query, limit);
                case "peertube" -> peertube(query, limit);
                case "odysee" -> odysee(query, limit);
                case "dailymotion" -> dailymotion(query, limit);
                default -> archive(query, limit);
            };
            if (got == null) silent.add(s); else { answered.add(s); rows.addAll(got); }
        }
        if (answered.isEmpty()) return "ERROR: no video site answered (" + String.join(", ", silent) + "). Try once more.";
        StringBuilder sb = new StringBuilder("videos for \"" + query + "\" on " + String.join(", ", answered) + ":\n");
        int n = 0;
        for (Row r : rows) {
            sb.append(++n).append(". [").append(r.site()).append("] ").append(r.title()).append(r.who().isEmpty() ? "" : " — " + r.who()).append(r.date().isEmpty() ? "" : " (" + r.date() + ")")
              .append(r.seconds() > 0 ? "  " + r.seconds() / 60 + " min" : "").append(r.views() > 0 ? "  " + r.views() + " views" : "").append('\n').append("   ").append(r.url()).append('\n');
            if (!r.description().isEmpty()) sb.append("   ").append(ArchiveSearchTool.cut(r.description(), 160)).append('\n');
        }
        if (n == 0) sb.append("nothing found.\n");
        if (!silent.isEmpty()) sb.append("did not answer: ").append(String.join(", ", silent)).append('\n');
        if (answered.contains("nebula") && rows.stream().anyMatch(r -> r.site().equals("nebula"))) sb.append("Nebula videos need a subscription to watch; the catalogue lines above are what Nebula publishes openly.\n");
        return Fence.wrap("VIDEO SITES", sb.toString().strip()) + "\n" + Fence.rule("VIDEO SITES");
    }

    static List<Row> niconico(String q, int limit) {
        String body = Archives.text(NICONICO + "/api/v2/snapshot/video/contents/search?q=" + Archives.enc(q) + "&targets=title,description,tags&fields=contentId,title,userId,channelId,viewCounter,startTime,lengthSeconds,description&_sort=-viewCounter&_limit=" + limit, Duration.ofSeconds(20), "Niconico");
        if (body == null) return null;
        List<Row> out = new ArrayList<>();
        try {
            for (JsonNode v : M.readTree(body).path("data"))
                out.add(new Row("niconico", v.path("title").asText(""), v.hasNonNull("channelId") ? "channel " + v.path("channelId").asText() : v.hasNonNull("userId") ? "user " + v.path("userId").asText() : "", v.path("startTime").asText("").replaceAll("T.*", ""),
                        v.path("lengthSeconds").asInt(0), v.path("viewCounter").asLong(0), "https://www.nicovideo.jp/watch/" + v.path("contentId").asText(""), v.path("description").asText("")));
        } catch (Exception e) { return null; }
        return out;
    }

    static List<Row> nebula(String q, int limit) {
        String body = Archives.text(NEBULA + "/video_episodes/?search=" + Archives.enc(q) + "&page_size=" + limit, Duration.ofSeconds(20), "Nebula");
        if (body == null) return null;
        List<Row> out = new ArrayList<>();
        try {
            for (JsonNode v : M.readTree(body).path("results"))
                out.add(new Row("nebula", v.path("title").asText(""), v.path("channel_title").asText(""), v.path("published_at").asText("").replaceAll("T.*", ""), v.path("duration").asInt(0), 0,
                        v.path("share_url").asText("https://nebula.tv/" + v.path("app_path").asText("")), v.path("short_description").asText(v.path("description").asText(""))));
        } catch (Exception e) { return null; }
        return out;
    }

    static List<Row> peertube(String q, int limit) {
        String body = Archives.text(SEPIA + "/api/v1/search/videos?search=" + Archives.enc(q) + "&count=" + limit, Duration.ofSeconds(20), "Sepia Search (PeerTube)");
        if (body == null) return null;
        List<Row> out = new ArrayList<>();
        try {
            for (JsonNode v : M.readTree(body).path("data"))
                out.add(new Row("peertube", v.path("name").asText(""), v.path("channel").path("displayName").asText(v.path("account").path("displayName").asText("")), v.path("publishedAt").asText("").replaceAll("T.*", ""),
                        v.path("duration").asInt(0), v.path("views").asLong(0), v.path("url").asText(""), v.path("description").asText("")));
        } catch (Exception e) { return null; }
        return out;
    }

    static List<Row> odysee(String q, int limit) {
        try {
            String req = "{\"jsonrpc\":\"2.0\",\"method\":\"claim_search\",\"params\":{\"text\":" + M.writeValueAsString(q) + ",\"claim_type\":[\"stream\"],\"stream_types\":[\"video\"],\"page_size\":" + limit + "},\"id\":1}";
            HttpResponse<String> r = Stopping.send(Archives.HTTP, HttpRequest.newBuilder(URI.create(ODYSEE + "/api/v1/proxy")).timeout(Duration.ofSeconds(20)).header("User-Agent", Archives.UA)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(req)).build(), HttpResponse.BodyHandlers.ofString(), Duration.ofSeconds(20), "Odysee");
            if (r.statusCode() != 200) return null;
            List<Row> out = new ArrayList<>();
            for (JsonNode it : M.readTree(r.body()).path("result").path("items")) {
                JsonNode v = it.path("value");
                String canonical = it.path("canonical_url").asText("").replaceFirst("^lbry://", "").replace("#", ":");
                long rel = v.path("release_time").asLong(0);
                out.add(new Row("odysee", v.path("title").asText(it.path("name").asText("")), it.path("signing_channel").path("name").asText(""), rel > 0 ? Instant.ofEpochSecond(rel).toString().replaceAll("T.*", "") : "",
                        v.path("video").path("duration").asInt(0), 0, "https://odysee.com/" + canonical, v.path("description").asText("")));
            }
            return out;
        } catch (Stopping.Requested stop) { throw stop; }
        catch (Exception e) { return null; }
    }

    static List<Row> dailymotion(String q, int limit) {
        String body = Archives.text(DAILYMOTION + "/videos?search=" + Archives.enc(q) + "&fields=id,title,owner.screenname,created_time,duration,views_total,url,description&limit=" + limit, Duration.ofSeconds(20), "Dailymotion");
        if (body == null) return null;
        List<Row> out = new ArrayList<>();
        try {
            for (JsonNode v : M.readTree(body).path("list")) {
                long t = v.path("created_time").asLong(0);
                out.add(new Row("dailymotion", v.path("title").asText(""), v.path("owner.screenname").asText(""), t > 0 ? Instant.ofEpochSecond(t).toString().replaceAll("T.*", "") : "", v.path("duration").asInt(0), v.path("views_total").asLong(0), v.path("url").asText(""), v.path("description").asText("")));
            }
        } catch (Exception e) { return null; }
        return out;
    }

    static List<Row> archive(String q, int limit) {
        List<Archives.Item> items = Archives.search(q, "video", limit);
        List<Row> out = new ArrayList<>();
        for (Archives.Item it : items) out.add(new Row("archive", it.title(), it.creator(), it.date(), 0, 0, it.link(), it.description()));
        return out;
    }
}
