package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 * What the video tools say about what YouTube gave them: the lines of a search result, a channel's uploads from its feed, a video's
 * details, and a transcript as timed lines. One place for the shapes, so that a research run reads the same form from every tool
 * and a person reads the same form on the page. Figures taken from YouTube are shown with their day, never ranked on.
 */
public final class VideoText {

    private VideoText() { }

    private static final ObjectMapper J = new ObjectMapper();

    /** The video id in any of YouTube's addresses, or the text itself when it already is one; null when there is none. */
    public static String videoId(String text) {
        if (text == null) return null;
        String t = text.strip();
        if (t.matches("[A-Za-z0-9_-]{11}")) return t;
        Matcher m = Pattern.compile("(?:v=|/shorts/|/live/|youtu\\.be/|/embed/)([A-Za-z0-9_-]{11})").matcher(t);
        return m.find() ? m.group(1) : null;
    }

    /** A channel address from an id, a handle or an address; null when it is none of those. */
    public static String channelUrl(String text) {
        if (text == null) return null;
        String t = text.strip();
        if (t.matches("UC[A-Za-z0-9_-]{22}")) return "https://www.youtube.com/channel/" + t;
        if (t.startsWith("@")) return "https://www.youtube.com/" + t;
        if (t.matches("https?://(www\\.)?youtube\\.com/(channel/UC[A-Za-z0-9_-]{22}|@[^/?\\s]+|c/[^/?\\s]+|user/[^/?\\s]+)(/.*)?")) return t.replaceAll("/(videos|streams|shorts|featured|about)/?$", "");
        return null;
    }

    /** A moment in a video, as the address the citation check can verify: the video at that second. */
    public static String moment(String videoId, int seconds) { return "https://www.youtube.com/watch?v=" + videoId + "&t=" + seconds + "s"; }

    private static final Pattern MOMENT = Pattern.compile("[?&#]t=(?:(\\d+)h)?(?:(\\d+)m)?(\\d+)s?\\b");

    /** The second a video address points at ({@code &t=312s}, {@code t=5m12s}, {@code t=312}); -1 when it names none. */
    public static int momentSeconds(String locator) {
        if (locator == null || videoId(locator) == null) return -1;
        Matcher m = MOMENT.matcher(locator);
        if (!m.find()) return -1;
        int h = m.group(1) == null ? 0 : Integer.parseInt(m.group(1)), min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2)), s = Integer.parseInt(m.group(3));
        return h * 3600 + min * 60 + s;
    }

    /** A video address without its moment: the video itself, which is what the library captured. */
    public static String withoutMoment(String locator) {
        if (locator == null || videoId(locator) == null) return locator;
        String out = MOMENT.matcher(locator).replaceAll("");
        return out.endsWith("?") || out.endsWith("&") ? out.substring(0, out.length() - 1) : out;
    }

    /**
     * The part of a captured transcript around a moment: the lines whose clock lies within {@code spread} seconds of it, with the
     * video's header lines kept. A citation of a moment is checked against what is said there, not against the whole video.
     */
    public static String momentWindow(String source, int seconds, int spread) {
        if (source == null || seconds < 0) return source;
        StringBuilder sb = new StringBuilder();
        boolean anyClock = false;
        for (String line : source.split("\\R")) {
            Matcher m = Pattern.compile("^\\[(?:(\\d+):)?(\\d+):(\\d\\d)\\] ").matcher(line);
            if (!m.find()) { if (!anyClock) sb.append(line).append('\n'); continue; }
            anyClock = true;
            int at = (m.group(1) == null ? 0 : Integer.parseInt(m.group(1)) * 3600) + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3));
            if (Math.abs(at - seconds) <= spread) sb.append(line).append('\n');
        }
        return anyClock ? sb.toString() : source;
    }

    /** 1343 → 22:23; 3700 → 1:01:40. */
    public static String clock(long seconds) {
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    /** 20210615 → 2021-06-15; "" when there is no date. */
    public static String day(String uploadDate) {
        if (uploadDate == null || !uploadDate.matches("\\d{8}")) return "";
        return uploadDate.substring(0, 4) + "-" + uploadDate.substring(4, 6) + "-" + uploadDate.substring(6);
    }

    /** "3 days ago", "2 years ago", for a day; "" when there is none. */
    public static String age(String isoDay, LocalDate today) {
        if (isoDay == null || isoDay.isBlank()) return "";
        try {
            LocalDate d = LocalDate.parse(isoDay.substring(0, 10));
            long days = ChronoUnit.DAYS.between(d, today);
            if (days < 1) return "today";
            if (days < 60) return days + (days == 1 ? " day ago" : " days ago");
            long months = days / 30;
            if (months < 24) return months + " months ago";
            return (days / 365) + " years ago";
        } catch (Exception e) { return ""; }
    }

    /** 1850000 → 1.85M; 2380 → 2,380. */
    public static String count(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.2fM", n / 1_000_000.0).replaceAll("\\.?0+M$", "M");
        if (n >= 100_000) return String.format(Locale.ROOT, "%dK", n / 1000);
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** The entries of a yt-dlp search or listing as lines: videos, or channels. */
    public static String searchLines(JsonNode listing, boolean channels, LocalDate today) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (JsonNode e : listing.path("entries")) {
            n++;
            if (channels) {
                long followers = e.path("channel_follower_count").asLong(-1);
                sb.append(n).append(". ").append(e.path("title").asText(e.path("channel").asText("?")));
                if (followers >= 0) sb.append(" — ").append(count(followers)).append(" subscribers");
                sb.append("\n   ").append(e.path("url").asText(e.path("channel_url").asText(""))).append('\n');
                String d = e.path("description").asText("");
                if (!d.isBlank()) sb.append("   ").append(d.length() > 220 ? d.substring(0, 220) + "…" : d.replace('\n', ' ')).append('\n');
            } else {
                String id = e.path("id").asText("");
                sb.append(n).append(". ").append(e.path("title").asText("?"));
                String ch = e.path("channel").asText(e.path("uploader").asText(""));
                if (!ch.isBlank()) sb.append(" — ").append(ch);
                long dur = e.path("duration").asLong(-1), views = e.path("view_count").asLong(-1);
                List<String> facts = new ArrayList<>();
                if (dur > 0) facts.add(clock(dur));
                if (views >= 0) facts.add(count(views) + " views");
                String day = day(e.path("upload_date").asText(""));
                if (!day.isEmpty()) facts.add("uploaded " + day + (today == null ? "" : " (" + age(day, today) + ")"));
                if (!facts.isEmpty()) sb.append(" · ").append(String.join(" · ", facts));
                sb.append("\n   https://www.youtube.com/watch?v=").append(id.isEmpty() ? videoId(e.path("url").asText("")) : id).append('\n');
            }
        }
        return sb.toString();
    }

    /** A channel's feed (YouTube's Atom feed of its latest fifteen uploads) as lines with their days. */
    public static String feedLines(String atom, LocalDate today, int limit) {
        StringBuilder sb = new StringBuilder();
        Matcher entry = Pattern.compile("(?s)<entry>(.*?)</entry>").matcher(atom);
        int n = 0;
        while (entry.find() && n < limit) {
            String e = entry.group(1);
            String id = first(e, "<yt:videoId>(.*?)</yt:videoId>"), title = unescape(first(e, "<title>(.*?)</title>")), published = first(e, "<published>(.*?)</published>");
            String views = first(e, "<media:statistics views=\"(\\d+)\"");
            n++;
            String day = published.length() >= 10 ? published.substring(0, 10) : "";
            sb.append(n).append(". ").append(title).append(" · uploaded ").append(day).append(today == null || day.isEmpty() ? "" : " (" + age(day, today) + ")");
            if (!views.isEmpty()) sb.append(" · ").append(count(Long.parseLong(views))).append(" views");
            sb.append("\n   https://www.youtube.com/watch?v=").append(id).append('\n');
        }
        return sb.toString();
    }

    /** The channel's name and its newest upload's day, from the feed; {"", ""} when the feed has neither. */
    public static String[] feedHead(String atom) {
        String name = unescape(first(atom, "(?s)<author>\\s*<name>(.*?)</name>"));
        String newest = first(atom, "<entry>[\\s\\S]*?<published>(.*?)</published>");
        return new String[]{name, newest.length() >= 10 ? newest.substring(0, 10) : ""};
    }

    /** One video's details as lines: who, when, how long, how watched, the chapters, the description's start, the caption languages. */
    public static String detailLines(JsonNode d, LocalDate today) {
        StringBuilder sb = new StringBuilder();
        String id = d.path("id").asText("");
        sb.append("title: ").append(d.path("title").asText("?")).append('\n');
        sb.append("address: https://www.youtube.com/watch?v=").append(id).append('\n');
        sb.append("channel: ").append(d.path("channel").asText(d.path("uploader").asText("?")));
        long followers = d.path("channel_follower_count").asLong(-1);
        if (followers >= 0) sb.append(" (").append(count(followers)).append(" subscribers)");
        sb.append(" — ").append(d.path("channel_url").asText("")).append('\n');
        String day = day(d.path("upload_date").asText(""));
        if (!day.isEmpty()) sb.append("uploaded: ").append(day).append(today == null ? "" : " (" + age(day, today) + ")").append('\n');
        long dur = d.path("duration").asLong(-1);
        if (dur > 0) sb.append("length: ").append(clock(dur)).append('\n');
        List<String> figures = new ArrayList<>();
        if (d.path("view_count").isNumber()) figures.add(count(d.path("view_count").asLong()) + " views");
        if (d.path("like_count").isNumber()) figures.add(count(d.path("like_count").asLong()) + " likes");
        if (d.path("comment_count").isNumber()) figures.add(count(d.path("comment_count").asLong()) + " comments");
        if (!figures.isEmpty()) sb.append("figures").append(today == null ? "" : " on " + today).append(": ").append(String.join(", ", figures)).append('\n');
        if (d.path("language").isTextual()) sb.append("language: ").append(d.path("language").asText()).append('\n');
        if (d.path("chapters").isArray() && d.path("chapters").size() > 0) {
            sb.append("chapters:\n");
            for (JsonNode c : d.path("chapters")) {
                int at = (int) c.path("start_time").asDouble(0);
                sb.append("  ").append(clock(at)).append("  ").append(c.path("title").asText("")).append("  ").append(moment(id, at)).append('\n');
            }
        }
        List<String> captions = new ArrayList<>();
        d.path("subtitles").fieldNames().forEachRemaining(captions::add);
        int auto = d.path("automatic_captions").size();
        sb.append("captions: ").append(captions.isEmpty() ? "none by the uploader" : "by the uploader in " + String.join(", ", captions)).append(auto > 0 ? "; automatic in " + auto + " languages" : "").append('\n');
        String desc = d.path("description").asText("").strip();
        if (!desc.isEmpty()) sb.append("description:\n").append(desc.length() > 1500 ? desc.substring(0, 1500) + "…" : desc).append('\n');
        return sb.toString();
    }

    /** The caption track's address in a details document for a language: the uploader's own first, else the automatic one; null when neither. */
    public static String captionUrl(JsonNode d, String language) {
        for (String field : new String[]{"subtitles", "automatic_captions"}) {
            JsonNode tracks = d.path(field);
            for (String lang : language == null || language.isBlank() ? List.of("en") : List.of(language, language + "-orig")) {
                for (JsonNode t : tracks.path(lang)) if (t.path("ext").asText("").equals("json3")) return t.path("url").asText(null);
            }
        }
        return null;
    }

    /** The caption languages a video offers, the uploader's own marked. */
    public static List<String> captionLanguages(JsonNode d) {
        List<String> out = new ArrayList<>();
        d.path("subtitles").fieldNames().forEachRemaining(l -> out.add(l + " (uploader)"));
        d.path("automatic_captions").fieldNames().forEachRemaining(l -> { if (!d.path("subtitles").has(l)) out.add(l); });
        return out;
    }

    /** One timed line of a transcript. */
    public record Line(int startSeconds, String text) { }

    /**
     * YouTube's json3 caption format as lines of roughly ten seconds each: the words are given in short segments with their start
     * times; they are joined up to the next pause or ten seconds, whichever comes first, so that a citation names a moment and a
     * reader can follow.
     */
    public static List<Line> linesFromJson3(String json3) {
        List<Line> out = new ArrayList<>();
        try {
            JsonNode root = J.readTree(json3);
            StringBuilder cur = new StringBuilder();
            int start = -1;
            long lastEnd = -1;
            for (JsonNode e : root.path("events")) {
                if (!e.has("segs")) continue;
                long t = e.path("tStartMs").asLong(0);
                StringBuilder seg = new StringBuilder();
                for (JsonNode s : e.path("segs")) seg.append(s.path("utf8").asText(""));
                String words = seg.toString().replace("\n", " ").strip();
                if (words.isEmpty()) continue;
                boolean pause = lastEnd >= 0 && t - lastEnd > 2500;
                if (start >= 0 && (pause || t / 1000 - start >= 10)) { out.add(new Line(start, cur.toString().strip())); cur.setLength(0); start = -1; }
                if (start < 0) start = (int) (t / 1000);
                cur.append(cur.length() > 0 ? " " : "").append(words);
                lastEnd = t + e.path("dDurationMs").asLong(0);
            }
            if (start >= 0 && cur.length() > 0) out.add(new Line(start, cur.toString().strip()));
        } catch (Exception ignored) { }
        return out;
    }

    /** The lines as a transcript: {@code [m:ss] words}, within a window of seconds (0 and -1 for all), and how many were left out. */
    public static String transcript(List<Line> lines, int fromSeconds, int toSeconds, int maxChars) {
        StringBuilder sb = new StringBuilder();
        int shown = 0, total = 0;
        boolean cut = false;
        for (Line l : lines) {
            if (l.startSeconds() < fromSeconds || (toSeconds > 0 && l.startSeconds() > toSeconds)) continue;
            total++;
            String line = "[" + clock(l.startSeconds()) + "] " + l.text() + "\n";
            if (sb.length() + line.length() > maxChars) { cut = true; continue; }
            sb.append(line);
            shown++;
        }
        if (cut) sb.append("… ").append(total - shown).append(" more lines after this point; ask for the window from ").append(clock(lastShown(lines, fromSeconds, toSeconds, shown))).append(" on.\n");
        return sb.toString();
    }

    private static int lastShown(List<Line> lines, int from, int to, int shown) {
        int n = 0;
        for (Line l : lines) {
            if (l.startSeconds() < from || (to > 0 && l.startSeconds() > to)) continue;
            if (++n == shown) return l.startSeconds();
        }
        return 0;
    }

    static String first(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).strip() : "";
    }

    static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }

    public static String today() { return OffsetDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE); }

    static Duration tenMinutes() { return Duration.ofMinutes(10); }
}
