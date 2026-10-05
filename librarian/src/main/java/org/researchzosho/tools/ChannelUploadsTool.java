package org.researchzosho.tools;

import org.researchzosho.librarian.Fence;
import org.researchzosho.librarian.Video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
/**
 * What a channel posted lately, from the feed YouTube publishes for every channel: its newest fifteen uploads with their days, which
 * is how a run judges whether a channel is still active. A channel given by handle or address is resolved to its id first. The feed is
 * one small fetch through the tunnel; yt-dlp's listing is used only when more than fifteen are asked for.
 */
public final class ChannelUploadsTool implements Tool {

    private static final ObjectMapper J = new ObjectMapper();

    @Override public String name() { return "channel_uploads"; }

    @Override public String description() {
        return "A YouTube channel's latest uploads with their days, so that you can see whether it still posts and what it makes. "
                + "Takes the channel's address, its id (UC…) or its handle (@name). Up to 15 from the channel's feed; more through a listing.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("channel").put("type", "string").put("description", "the channel's address, id (UC…) or handle (@name)");
        props.putObject("limit").put("type", "integer").put("description", "how many uploads, newest first (default 15, up to 50)");
        p.putArray("required").add("channel");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String given = args.path("channel").asText("").strip();
        String url = VideoText.channelUrl(given);
        if (url == null) return "ERROR: not a channel: give the channel's address (https://www.youtube.com/channel/UC… or https://www.youtube.com/@name), its id or its handle";
        int limit = Math.max(1, Math.min(50, args.path("limit").asInt(15)));
        String id = url.contains("/channel/") ? url.substring(url.indexOf("/channel/") + 9).replaceAll("/.*$", "") : null;
        if (id == null) {
            // a handle or a custom address: yt-dlp resolves it to the channel's id
            Video.Result r = Video.ytdlp(List.of("--flat-playlist", "--playlist-end", "1", "--dump-single-json", url + "/videos"), Duration.ofMinutes(3));
            if (r.code() != 0) return "ERROR: " + VideoSearchTool.failure(r);
            try { id = J.readTree(r.out().substring(r.out().indexOf('{'))).path("channel_id").asText(null); } catch (Exception ignored) { }
            if (id == null) return "ERROR: the channel's id could not be read from " + url;
        }
        LocalDate today = LocalDate.now();
        if (limit <= 15) {
            String atom = Video.fetchThroughTunnel("https://www.youtube.com/feeds/videos.xml?channel_id=" + id, Duration.ofSeconds(60));
            if (atom == null) return "ERROR: the channel's feed could not be fetched" + (Video.refusal().isEmpty() ? "" : ": " + Video.refusal());
            String[] head = VideoText.feedHead(atom);
            String lines = VideoText.feedLines(atom, today, limit);
            if (lines.isBlank()) return "the channel " + id + " has no uploads in its feed";
            return "uploads of " + (head[0].isEmpty() ? id : head[0]) + " (https://www.youtube.com/channel/" + id + "), newest first" + (head[1].isEmpty() ? "" : "; last upload " + head[1] + " (" + VideoText.age(head[1], today) + ")")
                    + " — YouTube's own text, fenced:\n" + Fence.open("CHANNEL UPLOADS") + "\n" + lines + Fence.close("CHANNEL UPLOADS") + "\n" + Fence.rule("CHANNEL UPLOADS") + "\n";
        }
        Video.Result r = Video.ytdlp(List.of("--flat-playlist", "--playlist-end", String.valueOf(limit), "--dump-single-json", "https://www.youtube.com/channel/" + id + "/videos"), Duration.ofMinutes(5));
        if (r.code() != 0) return "ERROR: " + VideoSearchTool.failure(r);
        try {
            JsonNode listing = J.readTree(r.out().substring(r.out().indexOf('{')));
            String lines = VideoText.searchLines(listing, false, today);
            return "uploads of " + listing.path("channel").asText(id) + " (https://www.youtube.com/channel/" + id + "), newest first — YouTube's own text, fenced; days come with video_details:\n"
                    + Fence.open("CHANNEL UPLOADS") + "\n" + lines + Fence.close("CHANNEL UPLOADS") + "\n" + Fence.rule("CHANNEL UPLOADS") + "\n";
        } catch (Exception e) {
            return "ERROR: YouTube answered with something that is not a listing: " + VideoSearchTool.tail(r.out());
        }
    }
}
