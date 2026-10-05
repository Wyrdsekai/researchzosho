package org.researchzosho.tools;

import org.researchzosho.librarian.Acquisitions;
import org.researchzosho.librarian.Fence;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.RawCapture;
import org.researchzosho.librarian.Video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
/**
 * One video, read: its details (who, when, how long, chapters, description, which caption languages exist) and, when asked, its
 * transcript as timed lines, so that a moment can be cited as {@code watch?v=…&t=312s} and checked. The transcript comes from the
 * captions when YouTube gives them (the uploader's own first, else the automatic ones) and otherwise from the library's own
 * transcription of the audio.
 */
public final class VideoDetailsTool implements Tool {

    private static final ObjectMapper J = new ObjectMapper();
    static final int TRANSCRIPT_CHARS = 14_000;
    private volatile ContentPolicy policy = ContentPolicy.defaults();

    /** The run's content policy, which carries the library the reading is captured into. */
    public VideoDetailsTool policy(ContentPolicy p) { this.policy = p == null ? ContentPolicy.defaults() : p; return this; }

    @Override public String name() { return "video_details"; }

    @Override public String description() {
        return "Read one YouTube video: title, channel, upload day, length, views, chapters with their moments, description, caption languages; "
                + "with transcript=true, what is said as timed lines [m:ss]. Cite a moment as https://www.youtube.com/watch?v=<id>&t=<seconds>s — "
                + "the citation check verifies it against the transcript. A long transcript comes in windows: from_seconds / to_seconds.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("video").put("type", "string").put("description", "the video's address or its 11-character id");
        props.putObject("transcript").put("type", "boolean").put("description", "also return what is said, as timed lines (default false)");
        props.putObject("language").put("type", "string").put("description", "the transcript's language code, e.g. en, ja (default: the video's own, else en)");
        props.putObject("from_seconds").put("type", "integer").put("description", "the transcript from this second on (default 0)");
        props.putObject("to_seconds").put("type", "integer").put("description", "the transcript up to this second (default: the end)");
        p.putArray("required").add("video");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String id = VideoText.videoId(args.path("video").asText(""));
        if (id == null) return "ERROR: not a YouTube video: give its address (https://www.youtube.com/watch?v=…) or its 11-character id";
        Video.Result r = Video.ytdlp(List.of("--skip-download", "--dump-single-json", "https://www.youtube.com/watch?v=" + id), Duration.ofMinutes(3));
        if (r.code() != 0) return "ERROR: " + VideoSearchTool.failure(r);
        JsonNode d;
        try { d = J.readTree(r.out().substring(r.out().indexOf('{'))); }
        catch (Exception e) { return "ERROR: YouTube answered with something that is not a video's details: " + VideoSearchTool.tail(r.out()); }
        LocalDate today = LocalDate.now();
        String details = VideoText.detailLines(d, today);
        StringBuilder sb = new StringBuilder("video " + id + " (YouTube's own text, fenced):\n" + Fence.open("VIDEO") + "\n" + details);
        String whole = null;
        if (args.path("transcript").asBoolean(false)) {
            String language = args.path("language").asText("");
            if (language.isBlank()) language = d.path("language").asText("en");
            List<VideoText.Line> lines = lines(d, id, language);
            String[] route = {""};
            String shown = transcript(d, id, language, lines, args.path("from_seconds").asInt(0), args.path("to_seconds").asInt(-1), route);
            sb.append("\ntranscript (").append(language).append("):\n").append(shown);
            if (!lines.isEmpty()) whole = "transcript (" + language + ", from " + route[0] + "):\n" + VideoText.transcript(lines, 0, -1, Integer.MAX_VALUE);
        }
        sb.append(Fence.close("VIDEO")).append('\n').append(Fence.rule("VIDEO")).append('\n');
        capture(d, id, details + (whole == null ? "" : "\n" + whole));
        return sb.toString();
    }

    /**
     * What was read goes to the library's raw tier under the video's own address, as a fetched page does: the citation check reads a
     * cited moment against the transcript's lines around it, and the video stays a source the library holds.
     */
    private void capture(JsonNode d, String id, String text) {
        try {
            LibraryStore into = policy.store();
            if (into == null && Acquisitions.libraryExists()) into = LibraryStore.open();
            if (into == null) return;
            RawCapture.capture(into, "https://www.youtube.com/watch?v=" + id, text, d.path("title").asText("video " + id), "researchzosho-video", "", VideoText.day(d.path("upload_date").asText("")));
        } catch (Exception ignored) {
            // the reading reaches the run either way; the capture is for the check and the shelves
        }
    }

    /** Where the last transcript's lines came from, for the note beside it. */
    private static final ThreadLocal<String> LAST_ROUTE = new ThreadLocal<>();

    /** The timed lines for a language: from the captions, else from the library's own transcription; empty when neither can be had. */
    static List<VideoText.Line> lines(JsonNode d, String id, String language) {
        LAST_ROUTE.set(null);
        String url = VideoText.captionUrl(d, language);
        if (url != null) {
            String json3 = Video.fetchThroughTunnel(url, Duration.ofSeconds(90));
            if (json3 != null) {
                List<VideoText.Line> lines = VideoText.linesFromJson3(json3);
                if (!lines.isEmpty()) { LAST_ROUTE.set(d.path("subtitles").has(language) ? "the uploader's captions" : "YouTube's automatic captions"); return lines; }
            }
        }
        List<VideoText.Line> spoken = Video.transcribe(id, language);
        if (spoken != null && !spoken.isEmpty()) LAST_ROUTE.set("the library's own transcription of the audio");
        return spoken == null ? List.of() : spoken;
    }

    /** The transcript as the run reads it: the window asked for, with where it came from, or a sentence saying why there is none. */
    static String transcript(JsonNode d, String id, String language, List<VideoText.Line> lines, int from, int to, String[] routeOut) {
        String url = VideoText.captionUrl(d, language);
        if (lines.isEmpty()) {
            List<String> have = VideoText.captionLanguages(d);
            return (url == null ? "no captions in " + language + (have.isEmpty() ? "" : "; captions exist in " + String.join(", ", have.size() > 12 ? have.subList(0, 12) : have) + (have.size() > 12 ? "…" : ""))
                    : "the captions could not be fetched" + (Video.refusal().isEmpty() ? "" : " (" + Video.refusal() + ")"))
                    + (Video.transcriptionReady() ? "; the audio could not be transcribed either" : "; the library's own transcription is not set up (researchzosho video install)") + "\n";
        }
        // which route the lines came from is told by whether the captions answered: the audio is tried only when they did not
        String route = LAST_ROUTE.get() == null ? "the captions" : LAST_ROUTE.get();
        routeOut[0] = route;
        return "(from " + route + "; each line starts at the moment shown; cite a moment as " + VideoText.moment(id, 0).replace("&t=0s", "&t=<seconds>s") + ")\n"
                + VideoText.transcript(lines, from, to, TRANSCRIPT_CHARS);
    }

    /** Kept for a caller that wants the old shape. */
    static String transcript(JsonNode d, String id, String language, int from, int to) {
        return transcript(d, id, language, lines(d, id, language), from, to, new String[1]);
    }

}
