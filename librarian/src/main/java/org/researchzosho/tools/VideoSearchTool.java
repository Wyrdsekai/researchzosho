package org.researchzosho.tools;

import org.researchzosho.librarian.Fence;
import org.researchzosho.librarian.Video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
/**
 * YouTube search for a research run, through the video helper: videos, or channels. YouTube's own search leans on what is already
 * popular; a channel search returns the small ones too (measured 2026-10-03: channels of 3 and 15 subscribers beside one of 4,000),
 * and later pages reach past the first screen. What comes back is YouTube's text — titles, descriptions — fenced as such.
 */
public final class VideoSearchTool implements Tool {

    private static final ObjectMapper J = new ObjectMapper();
    static final int MOST = 20;

    @Override public String name() { return "video_search"; }

    @Override public String description() {
        return "Search YouTube for videos, or for channels (kind=channels), without an API key, through the library's own tunnel. "
                + "Results show the channel and its subscribers, the length, views and upload day where YouTube gives them — shown, not ranked on. "
                + "page 2, 3… reaches past the first results. video_details reads one video; channel_uploads lists what a channel posted lately.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("query").put("type", "string").put("description", "the words to search for, in the language the videos would be in");
        ObjectNode kind = props.putObject("kind").put("type", "string").put("description", "videos (default) or channels");
        kind.putArray("enum").add("videos").add("channels");
        props.putObject("limit").put("type", "integer").put("description", "how many results, up to " + MOST + " (default 10)");
        props.putObject("page").put("type", "integer").put("description", "which page of results, 1 (default), 2, 3…");
        p.putArray("required").add("query");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String query = args.path("query").asText("").strip();
        if (query.isEmpty()) return "ERROR: query is empty";
        boolean channels = "channels".equalsIgnoreCase(args.path("kind").asText("videos"));
        int limit = Math.max(1, Math.min(MOST, args.path("limit").asInt(10)));
        int page = Math.max(1, args.path("page").asInt(1));
        int from = (page - 1) * limit + 1, to = page * limit;
        String target = channels
                ? "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&sp=EgIQAg%253D%253D"
                : "ytsearch" + to + ":" + query;
        Video.Result r = Video.ytdlp(List.of("--flat-playlist", "--dump-single-json", "--playlist-start", String.valueOf(from), "--playlist-end", String.valueOf(to), target), Duration.ofMinutes(3));
        if (r.code() != 0) return "ERROR: " + failure(r);
        try {
            JsonNode listing = J.readTree(r.out().substring(r.out().indexOf('{')));
            String lines = VideoText.searchLines(listing, channels, LocalDate.now());
            if (lines.isBlank()) return "no " + (channels ? "channels" : "videos") + " for: " + query + (page > 1 ? " (page " + page + ")" : "");
            return (channels ? "channels" : "videos") + " for \"" + query + "\"" + (page > 1 ? ", page " + page : "") + " (YouTube's own text, fenced):\n"
                    + Fence.open("SEARCH RESULTS") + "\n" + lines + Fence.close("SEARCH RESULTS") + "\n" + Fence.rule("SEARCH RESULTS") + "\n";
        } catch (Exception e) {
            return "ERROR: YouTube answered with something that is not a listing: " + tail(r.out());
        }
    }

    static String failure(Video.Result r) {
        String refusal = Video.refusal();
        return (Video.refused(r.out()) && !refusal.isEmpty() ? refusal + " " : "") + tail(r.out());
    }

    static String tail(String out) {
        if (out == null) return "";
        String[] lines = out.strip().split("\\R");
        String l = lines[lines.length - 1].strip();
        return l.length() > 300 ? l.substring(0, 300) + "…" : l;
    }
}
