package org.researchzosho.tools;

import org.researchzosho.librarian.Fence;
import org.researchzosho.librarian.Video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
/**
 * What a channel posted, from the feed YouTube publishes for every channel: its newest fifteen uploads with their days, which is how a
 * run judges whether a channel is still active. A channel given by handle or address is resolved to its id first. The feed is one small
 * fetch through the tunnel; yt-dlp's listing is used when more than fifteen are asked for, and for the whole list ({@code all}) or the
 * uploads about a subject ({@code match}: the titles that carry every word, or match a pattern) — one call lists a channel of a thousand
 * videos in seconds, and a run can then read the matching ones with video_details, the way the wind harvest did (0.6.1).
 */
public final class ChannelUploadsTool implements Tool {

    private static final ObjectMapper J = new ObjectMapper();

    /** The most rows a whole list or a match shows; the plain listing stops at fifty. */
    static final int MOST = 500, MOST_PLAIN = 50;

    @Override public String name() { return "channel_uploads"; }

    @Override public String description() {
        return "A YouTube channel's uploads with their days, so that you can see whether it still posts and what it makes. "
                + "Takes the channel's address, its id (UC…) or its handle (@name). The newest 15 from the channel's feed; limit for more; all=true for every upload; "
                + "match=<words> keeps only the titles that carry every word (or /a pattern/), read over the whole list — the way to find every video a channel made about a subject.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("channel").put("type", "string").put("description", "the channel's address, id (UC…) or handle (@name)");
        props.putObject("limit").put("type", "integer").put("description", "how many uploads, newest first (default 15, up to 50; with all or match up to 500)");
        props.putObject("all").put("type", "boolean").put("description", "every upload the channel has, newest first (default false)");
        props.putObject("match").put("type", "string").put("description", "words every kept title must carry, or /a pattern/; the whole list is read");
        p.putArray("required").add("channel");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String given = args.path("channel").asText("").strip();
        String url = VideoText.channelUrl(given);
        if (url == null) return "ERROR: not a channel: give the channel's address (https://www.youtube.com/channel/UC… or https://www.youtube.com/@name), its id or its handle";
        boolean all = args.path("all").asBoolean(false);
        String match = args.path("match").asText("").strip();
        boolean whole = all || !match.isEmpty();
        int limit = Math.max(1, Math.min(whole ? MOST : MOST_PLAIN, args.path("limit").asInt(whole ? MOST : 15)));
        String id = url.contains("/channel/") ? url.substring(url.indexOf("/channel/") + 9).replaceAll("/.*$", "") : null;
        if (id == null) {
            // a handle or a custom address: yt-dlp resolves it to the channel's id
            Video.Result r = Video.ytdlp(List.of("--flat-playlist", "--playlist-end", "1", "--dump-single-json", url + "/videos"), Duration.ofMinutes(3), Video.readPaceMs);
            if (r.code() != 0) return "ERROR: " + VideoSearchTool.failure(r);
            try { id = J.readTree(r.out().substring(r.out().indexOf('{'))).path("channel_id").asText(null); } catch (Exception ignored) { }
            if (id == null) return "ERROR: the channel's id could not be read from " + url;
        }
        LocalDate today = LocalDate.now();
        if (!whole && limit <= 15) {
            String atom = Video.fetchThroughTunnel("https://www.youtube.com/feeds/videos.xml?channel_id=" + id, Duration.ofSeconds(60));
            if (atom == null) return "ERROR: the channel's feed could not be fetched" + (Video.refusal().isEmpty() ? "" : ": " + Video.refusal());
            String[] head = VideoText.feedHead(atom);
            String lines = VideoText.feedLines(atom, today, limit);
            if (lines.isBlank()) return "the channel " + id + " has no uploads in its feed";
            return "uploads of " + (head[0].isEmpty() ? id : head[0]) + " (https://www.youtube.com/channel/" + id + "), newest first" + (head[1].isEmpty() ? "" : "; last upload " + head[1] + " (" + VideoText.age(head[1], today) + ")")
                    + " — YouTube's own text, fenced:\n" + Fence.open("CHANNEL UPLOADS") + "\n" + lines + Fence.close("CHANNEL UPLOADS") + "\n" + Fence.rule("CHANNEL UPLOADS") + "\n";
        }
        List<String> a = new ArrayList<>(List.of("--flat-playlist", "--dump-single-json"));
        if (!whole) a.addAll(List.of("--playlist-end", String.valueOf(limit)));
        a.add("https://www.youtube.com/channel/" + id + "/videos");
        Video.Result r = Video.ytdlp(a, Duration.ofMinutes(whole ? 10 : 5), Video.readPaceMs);
        if (r.code() != 0) return "ERROR: " + VideoSearchTool.failure(r);
        try {
            JsonNode listing = J.readTree(r.out().substring(r.out().indexOf('{')));
            int total = listing.path("entries").size(), matching = match.isEmpty() ? total : VideoText.countMatching(listing, match);
            JsonNode shown = VideoText.entriesMatching(listing, match, limit);
            String lines = VideoText.searchLines(shown, false, today);
            String what = match.isEmpty() ? (whole ? ", all " + total : "") : ", " + matching + " of " + total + " titles carry \"" + match + "\"";
            if (shown.path("entries").size() < matching) what += ", the first " + shown.path("entries").size() + " shown";
            if (!match.isEmpty() && matching == 0) return "uploads of " + listing.path("channel").asText(id) + " (https://www.youtube.com/channel/" + id + "): none of its " + total + " titles carries \"" + match + "\"";
            return "uploads of " + listing.path("channel").asText(id) + " (https://www.youtube.com/channel/" + id + "), newest first" + what + " — YouTube's own text, fenced; days come with video_details:\n"
                    + Fence.open("CHANNEL UPLOADS") + "\n" + lines + Fence.close("CHANNEL UPLOADS") + "\n" + Fence.rule("CHANNEL UPLOADS") + "\n";
        } catch (Exception e) {
            return "ERROR: YouTube answered with something that is not a listing: " + VideoSearchTool.tail(r.out());
        }
    }
}
