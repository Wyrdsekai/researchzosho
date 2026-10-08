package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.RawCapture;
import org.researchzosho.librarian.Serials;
import org.researchzosho.librarian.Video;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Podcasts for a research run (0.5.5): shows and episodes from Apple's keyless search and from the Podcast Index (with the library's own
 * key, api.podcastindex.org/signup), the feed itself as the source of truth, and an episode's transcript — the one the publisher put in the
 * feed when there is one, else the library's own transcription of the audio through the same server the video helper uses, within the
 * run's budget. Transcripts are kept in the library against the audio's address; the audio is deleted. The Podcast Index's terms forbid
 * crawling the index through the API, and nothing here walks it: every call is one targeted lookup, capped per run.
 */
public final class Podcasts {
    private Podcasts() { }

    private static final ObjectMapper M = new ObjectMapper();
    static volatile String APPLE = "https://itunes.apple.com", INDEX = "https://api.podcastindex.org/api/1.0";
    /** How many redirects an episode's audio may go through: a Megaphone show's goes through five measurement hosts (measured 2026-10-08), and the Java client's own following stopped short of the last. */
    static final int MOST_HOPS = 10;
    static final String BY = "researchzosho-podcast";

    public record Show(String title, String author, String feedUrl, String site, String language, List<String> categories, int episodes, String lastEpisode, String source) { }
    public record Episode(String title, String date, String description, String page, String audioUrl, String audioType, int seconds, String guid,
                          String feedTitle, String feedUrl, List<String[]> transcripts, List<String> people) {
        /** The address a citation points at: the episode's page, else its audio. */
        public String cite() { return page == null || page.isBlank() ? audioUrl : page; }
    }
    /** An episode's spoken lines, and how they were had: published (the feed's transcript), transcribed (by the library's server), or saved (from the library). */
    public record Transcript(List<VideoText.Line> lines, String how) { }

    /** How much one run may transcribe: episodes and seconds of audio, from the settings (2 episodes, 120 minutes unless set). */
    public static final class Budget {
        private int episodes; private long seconds; private final boolean capped;
        public Budget() { this(Config.getInt("RESEARCHZOSHO_TRANSCRIBE_EPISODES", 2), Config.getInt("RESEARCHZOSHO_TRANSCRIBE_MINUTES", 120) * 60L, true); }
        public Budget(int episodes, long seconds, boolean capped) { this.episodes = episodes; this.seconds = seconds; this.capped = capped; }
        /** Takes an episode of {@code length} seconds; false, and why, when the budget is spent. */
        public synchronized String take(int length) {
            if (!capped) return "";
            if (episodes <= 0) return "this run has transcribed its " + Config.getInt("RESEARCHZOSHO_TRANSCRIBE_EPISODES", 2) + " episode(s) (RESEARCHZOSHO_TRANSCRIBE_EPISODES)";
            if (length > 0 && length > seconds) return "this episode is " + length / 60 + " minutes and the run has " + seconds / 60 + " minutes of transcription left (RESEARCHZOSHO_TRANSCRIBE_MINUTES)";
            episodes--; seconds -= Math.max(0, length);
            return "";
        }
        public synchronized String left() { return capped ? episodes + " episode(s), " + seconds / 60 + " minutes" : "no cap"; }
    }

    // ---- shows ----

    /** Apple's podcast search, no key: shows with their feed addresses. */
    public static List<Show> apple(String query, int limit) {
        String body = Archives.text(APPLE + "/search?media=podcast&term=" + Archives.enc(query) + "&limit=" + Math.max(1, limit), Duration.ofSeconds(20), "Apple's podcast search");
        List<Show> out = new ArrayList<>();
        if (body == null) return null;
        try {
            for (JsonNode r : M.readTree(body).path("results")) {
                List<String> genres = new ArrayList<>();
                for (JsonNode g : r.path("genres")) if (!g.asText("").equals("Podcasts")) genres.add(g.asText(""));
                out.add(new Show(r.path("collectionName").asText(""), r.path("artistName").asText(""), r.path("feedUrl").asText(""), r.path("collectionViewUrl").asText(""), "",
                        genres, r.path("trackCount").asInt(0), r.path("releaseDate").asText("").replaceAll("T.*", ""), "Apple"));
            }
        } catch (Exception e) { return null; }
        return out;
    }

    /** Whether the library has a Podcast Index key and secret. */
    public static boolean indexConfigured() {
        String k = Config.get("RESEARCHZOSHO_PODCASTINDEX_KEY"), s = Config.get("RESEARCHZOSHO_PODCASTINDEX_SECRET");
        return k != null && !k.isBlank() && s != null && !s.isBlank();
    }

    /** One call to the Podcast Index, signed as its API wants: the key, the time, and the SHA-1 of key, secret and time. Null when it did not answer 200. */
    static String index(String path, String query) {
        String key = Config.get("RESEARCHZOSHO_PODCASTINDEX_KEY"), secret = Config.get("RESEARCHZOSHO_PODCASTINDEX_SECRET");
        if (key == null || secret == null) return null;
        try {
            String now = String.valueOf(Instant.now().getEpochSecond());
            String auth = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest((key.strip() + secret.strip() + now).getBytes(StandardCharsets.UTF_8)));
            HttpResponse<String> r = Stopping.send(Archives.HTTP, HttpRequest.newBuilder(URI.create(INDEX + path + "?" + query)).timeout(Duration.ofSeconds(20))
                    .header("User-Agent", Archives.UA).header("X-Auth-Key", key.strip()).header("X-Auth-Date", now).header("Authorization", auth).GET().build(),
                    HttpResponse.BodyHandlers.ofString(), Duration.ofSeconds(20), "the Podcast Index");
            return r.statusCode() == 200 ? r.body() : null;
        } catch (Stopping.Requested stop) { throw stop; }
        catch (Exception e) { return null; }
    }

    static Show showOf(JsonNode f, String source) {
        List<String> cats = new ArrayList<>();
        for (JsonNode c : f.path("categories")) cats.add(c.asText(""));
        long last = f.path("newestItemPubdate").asLong(f.path("lastUpdateTime").asLong(0));
        return new Show(f.path("title").asText(""), f.path("author").asText(f.path("ownerName").asText("")), f.path("url").asText(""), f.path("link").asText(""), f.path("language").asText(""),
                cats, f.path("episodeCount").asInt(0), last > 0 ? Instant.ofEpochSecond(last).toString().replaceAll("T.*", "") : "", source);
    }

    /** The Podcast Index's search by term: shows. Null without a key or an answer. */
    public static List<Show> indexSearch(String query, int limit) {
        if (!indexConfigured()) return null;
        String body = index("/search/byterm", "q=" + Archives.enc(query) + "&max=" + Math.max(1, limit));
        if (body == null) return null;
        List<Show> out = new ArrayList<>();
        try { for (JsonNode f : M.readTree(body).path("feeds")) if (!f.path("dead").asBoolean(false)) out.add(showOf(f, "Podcast Index")); } catch (Exception e) { return null; }
        return out;
    }

    /** The Podcast Index's record of one feed, with its categories. Null without a key, an answer, or the feed. */
    public static Show indexFeed(String feedUrl) {
        if (!indexConfigured()) return null;
        String body = index("/podcasts/byfeedurl", "url=" + Archives.enc(feedUrl));
        if (body == null) return null;
        try { JsonNode f = M.readTree(body).path("feed"); return f.hasNonNull("url") ? showOf(f, "Podcast Index") : null; } catch (Exception e) { return null; }
    }

    /** Shows trending in a category (the index's own word), for a search by subject. */
    public static List<Show> indexTrending(String category, int limit) {
        if (!indexConfigured()) return null;
        String body = index("/podcasts/trending", "max=" + Math.max(1, limit) + (category == null || category.isBlank() ? "" : "&cat=" + Archives.enc(category)));
        if (body == null) return null;
        List<Show> out = new ArrayList<>();
        try { for (JsonNode f : M.readTree(body).path("feeds")) out.add(showOf(f, "Podcast Index, trending")); } catch (Exception e) { return null; }
        return out;
    }

    static Episode episodeOf(JsonNode it) {
        List<String[]> tr = new ArrayList<>();
        for (JsonNode t : it.path("transcripts")) tr.add(new String[]{t.path("url").asText(""), t.path("type").asText("")});
        if (tr.isEmpty() && it.hasNonNull("transcriptUrl") && !it.path("transcriptUrl").asText("").isEmpty()) tr.add(new String[]{it.path("transcriptUrl").asText(""), ""});
        List<String> people = new ArrayList<>();
        for (JsonNode p : it.path("persons")) { String n = p.path("name").asText(""); if (!n.isEmpty()) people.add(n + (p.path("role").asText("").isEmpty() ? "" : " (" + p.path("role").asText("") + ")")); }
        long when = it.path("datePublished").asLong(0);
        return new Episode(it.path("title").asText(""), when > 0 ? Instant.ofEpochSecond(when).toString().replaceAll("T.*", "") : "", strip(it.path("description").asText("")), it.path("link").asText(""),
                it.path("enclosureUrl").asText(""), it.path("enclosureType").asText(""), it.path("duration").asInt(0), it.path("guid").asText(""), it.path("feedTitle").asText(""), it.path("feedUrl").asText(""), tr, people);
    }

    /** The Podcast Index's search by person: the episodes someone appeared on, with their shows. Null without a key or an answer. */
    public static List<Episode> indexByPerson(String name, int limit) {
        if (!indexConfigured()) return null;
        String body = index("/search/byperson", "q=" + Archives.enc(name) + "&max=" + Math.max(1, limit));
        if (body == null) return null;
        List<Episode> out = new ArrayList<>();
        try { for (JsonNode it : M.readTree(body).path("items")) out.add(episodeOf(it)); } catch (Exception e) { return null; }
        return out;
    }

    /** The Podcast Index's episodes of one feed, newest first, with their transcripts and people where the feed declares them. */
    public static List<Episode> indexEpisodes(String feedUrl, int limit) {
        if (!indexConfigured()) return null;
        String body = index("/episodes/byfeedurl", "url=" + Archives.enc(feedUrl) + "&max=" + Math.max(1, limit));
        if (body == null) return null;
        List<Episode> out = new ArrayList<>();
        try { for (JsonNode it : M.readTree(body).path("items")) out.add(episodeOf(it)); } catch (Exception e) { return null; }
        return out;
    }

    // ---- the feed ----

    /** A feed's show and its newest episodes, read from the RSS itself. Null when the feed did not answer or is not RSS. */
    public static Map.Entry<Show, List<Episode>> feed(String feedUrl, int limit) {
        String xml = Archives.text(feedUrl, Duration.ofSeconds(30), "the podcast's feed");
        if (xml == null) return null;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false); f.setExpandEntityReferences(false);
            Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList channels = doc.getElementsByTagName("channel");
            if (channels.getLength() == 0) return null;
            Element ch = (Element) channels.item(0);
            String title = own(ch, "title"), author = own(ch, "itunes:author"), link = own(ch, "link"), language = own(ch, "language");
            List<String> cats = new ArrayList<>();
            NodeList cn = ch.getElementsByTagName("itunes:category");
            for (int i = 0; i < cn.getLength(); i++) { String t = ((Element) cn.item(i)).getAttribute("text"); if (!t.isEmpty() && !cats.contains(t)) cats.add(t); }
            NodeList items = doc.getElementsByTagName("item");
            List<Episode> eps = new ArrayList<>();
            for (int i = 0; i < items.getLength() && eps.size() < Math.max(1, limit); i++) {
                Element it = (Element) items.item(i);
                String audio = "", type = "";
                NodeList enc = it.getElementsByTagName("enclosure");
                if (enc.getLength() > 0) { audio = ((Element) enc.item(0)).getAttribute("url"); type = ((Element) enc.item(0)).getAttribute("type"); }
                List<String[]> tr = new ArrayList<>();
                NodeList tn = it.getElementsByTagName("podcast:transcript");
                for (int k = 0; k < tn.getLength(); k++) { Element t = (Element) tn.item(k); tr.add(new String[]{t.getAttribute("url"), t.getAttribute("type")}); }
                List<String> people = new ArrayList<>();
                NodeList pn = it.getElementsByTagName("podcast:person");
                for (int k = 0; k < pn.getLength(); k++) { Element p = (Element) pn.item(k); String n = p.getTextContent().strip(); if (!n.isEmpty()) people.add(n + (p.getAttribute("role").isEmpty() ? "" : " (" + p.getAttribute("role") + ")")); }
                String desc = own(it, "itunes:summary"); if (desc.isEmpty()) desc = own(it, "description");
                eps.add(new Episode(own(it, "title"), date(own(it, "pubDate")), strip(desc), own(it, "link"), audio, type, seconds(own(it, "itunes:duration")), own(it, "guid"), title, feedUrl, tr, people));
            }
            Show show = new Show(title, author, feedUrl, link, language, cats, items.getLength(), eps.isEmpty() ? "" : eps.get(0).date(), "feed");
            return Map.entry(show, eps);
        } catch (Exception e) { return null; }
    }

    /** The text of the element's own child with this tag, not a grandchild's. */
    static String own(Element e, String tag) {
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) if (kids.item(i) instanceof Element k && k.getTagName().equals(tag)) return k.getTextContent().strip();
        return "";
    }

    static String date(String rfc) {
        if (rfc == null || rfc.isBlank()) return "";
        try { return ZonedDateTime.parse(rfc.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate().toString(); }
        catch (Exception e) { Matcher m = Pattern.compile("(\\d{1,2}) ([A-Z][a-z]{2}) (\\d{4})").matcher(rfc); if (m.find()) { try { return ZonedDateTime.parse("Mon, " + m.group(1) + " " + m.group(2) + " " + m.group(3) + " 00:00:00 GMT", DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate().toString(); } catch (Exception ignored) { } } return ""; }
    }

    static int seconds(String d) {
        if (d == null || d.isBlank()) return 0;
        String s = d.strip();
        if (s.matches("\\d+")) return Integer.parseInt(s);
        String[] p = s.split(":");
        try {
            if (p.length == 3) return Integer.parseInt(p[0]) * 3600 + Integer.parseInt(p[1]) * 60 + (int) Double.parseDouble(p[2]);
            if (p.length == 2) return Integer.parseInt(p[0]) * 60 + (int) Double.parseDouble(p[1]);
        } catch (NumberFormatException ignored) { }
        return 0;
    }

    static String strip(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replaceAll("\\s+", " ").strip();
    }

    // ---- transcripts ----

    /**
     * An episode's transcript: from the library when it was had before; else the feed's own transcript (SRT, VTT, JSON, text or HTML);
     * else the library's transcription of the audio, within the budget. Null with the reason in {@code why} when none could be had.
     */
    public static Transcript transcript(LibraryStore store, Episode ep, String language, Budget budget, StringBuilder why) {
        String key = ep.audioUrl().isEmpty() ? ep.cite() : ep.audioUrl();
        if (store != null && !key.isEmpty()) {
            try {
                Path saved = RawCapture.find(store, key);
                if (saved != null) { List<VideoText.Line> lines = parseSaved(RawCapture.read(saved)[2]); if (!lines.isEmpty()) return new Transcript(lines, "saved in the library"); }
            } catch (Exception ignored) { }
        }
        for (String[] t : preferred(ep.transcripts())) {
            String body = Archives.text(t[0], Duration.ofSeconds(30), "the episode's transcript");
            if (body == null) continue;
            List<VideoText.Line> lines = parseTranscript(body, t[1]);
            if (!lines.isEmpty()) { keep(store, key, ep, lines); return new Transcript(lines, "published by the show (" + (t[1].isEmpty() ? "transcript" : t[1]) + ")"); }
        }
        if (ep.audioUrl().isEmpty()) { why.append("the episode has no audio address and no published transcript"); return null; }
        if (!Video.transcriptionReady()) { why.append("no transcription server answers (researchzosho video install starts one; RESEARCHZOSHO_WHISPER names another)"); return null; }
        String refused = budget.take(ep.seconds());
        if (!refused.isEmpty()) { why.append(refused); return null; }
        Path audio = Video.dir().resolve("tmp").resolve("podcast-" + Long.toHexString(System.nanoTime()) + ".audio");
        try {
            Files.createDirectories(audio.getParent());
            long most = Config.getInt("RESEARCHZOSHO_FETCH_MAX_BYTES", 0) > 0 ? Config.getInt("RESEARCHZOSHO_FETCH_MAX_BYTES", 0) : 250_000_000L;
            HttpResponse<Path> r = fetchAudio(ep.audioUrl(), audio);
            if (r.statusCode() != 200) { why.append("the audio answered HTTP " + r.statusCode() + " at " + r.uri().getHost()); return null; }
            if (Files.size(audio) > most) { why.append("the audio is larger than the library downloads (" + Files.size(audio) / 1_000_000 + " MB)"); return null; }
            List<VideoText.Line> lines = Video.transcribeAudio(audio, language);
            if (lines == null || lines.isEmpty()) { why.append("the transcription server returned nothing"); return null; }
            keep(store, key, ep, lines);
            return new Transcript(lines, "transcribed by the library's server");
        } catch (Stopping.Requested stop) { throw stop; }
        catch (Exception e) { why.append("the audio could not be had (" + e.getMessage() + ")"); return null; }
        finally { try { Files.deleteIfExists(audio); } catch (IOException ignored) { } }
    }

    /**
     * The audio, followed through every redirect the hosts put in the way, up to {@link #MOST_HOPS}; the answer is the last hop's. A
     * measurement service that has shut down answers 404 for every address through it (Chartable's chtbl.com, measured 2026-10-08) while the
     * file behind it is still there: on a 404 or 410 the address the path carries after the service's own is tried.
     */
    static HttpResponse<Path> fetchAudio(String url, Path audio) throws Exception {
        HttpResponse<Path> r = follow(url, audio);
        if (r.statusCode() == 404 || r.statusCode() == 410) {
            String inner = innerAddress(url);
            if (inner != null) { Files.deleteIfExists(audio); HttpResponse<Path> again = follow(inner, audio); if (again.statusCode() == 200) return again; }
        }
        return r;
    }

    private static HttpResponse<Path> follow(String url, Path audio) throws Exception {
        URI at = URI.create(url);
        HttpResponse<Path> r = null;
        for (int hop = 0; hop <= MOST_HOPS; hop++) {
            r = Stopping.send(Archives.HTTP, HttpRequest.newBuilder(at).timeout(Duration.ofMinutes(10)).header("User-Agent", Archives.UA).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(audio), Duration.ofMinutes(10), "the episode's audio");
            if (r.statusCode() / 100 != 3) return r;
            String loc = r.headers().firstValue("location").orElse("");
            if (loc.isEmpty()) return r;
            at = at.resolve(loc);
            Files.deleteIfExists(audio);
        }
        return r;
    }

    static final Pattern INNER = Pattern.compile("/((?:[a-z0-9-]+\\.)+[a-z0-9-]+(?::\\d+)?/.+)$", Pattern.CASE_INSENSITIVE);   // a host (a name, or an address) then a path

    /** The address a measurement service's path carries after its own segments (…/track/ID/host.example/path.mp3 → scheme://host.example/path.mp3), or null. */
    static String innerAddress(String url) {
        try {
            URI u = URI.create(url);
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            // the LAST host-like segment: the services nest (podtrac → pdst → pscrb → mgln → the host)
            Matcher m = INNER.matcher(path);
            int lastStart = -1; String last = null;
            while (m.find()) { lastStart = m.start(1); last = m.group(1); m.region(m.start(1) + 1, path.length()); }
            if (last == null) return null;
            String scheme = u.getScheme() == null ? "https" : u.getScheme();
            return scheme + "://" + last + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        } catch (Exception e) { return null; }
    }

    /** The feed's transcripts, the timed and plain kinds first, HTML last. */
    static List<String[]> preferred(List<String[]> all) {
        List<String[]> out = new ArrayList<>();
        for (String want : new String[]{"srt", "vtt", "json", "text", "plain", ""}) for (String[] t : all) if (!out.contains(t) && t[1].toLowerCase(Locale.ROOT).contains(want)) out.add(t);
        for (String[] t : all) if (!out.contains(t)) out.add(t);
        return out;
    }

    static final Pattern CUE = Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})[.,](\\d{1,3})\\s*-->");
    static final Pattern SHORT_CUE = Pattern.compile("^(\\d{1,2}):(\\d{2})[.,](\\d{1,3})\\s*-->");

    /** SRT and VTT cues, the podcast JSON transcript, or plain text and HTML (one line at the start) as timed lines. */
    public static List<VideoText.Line> parseTranscript(String body, String type) {
        List<VideoText.Line> out = new ArrayList<>();
        String t = type == null ? "" : type.toLowerCase(Locale.ROOT), b = body.strip();
        if (t.contains("json") || b.startsWith("{")) {
            try {
                JsonNode root = M.readTree(b);
                for (JsonNode s : root.path("segments")) { String text = s.path("body").asText(s.path("text").asText("")).strip(); if (!text.isEmpty()) out.add(new VideoText.Line((int) s.path("startTime").asDouble(s.path("start").asDouble(0)), text)); }
                if (!out.isEmpty()) return merge(out);
            } catch (Exception ignored) { }
        }
        if (CUE.matcher(b).find() || SHORT_CUE.matcher(b).find() || t.contains("srt") || t.contains("vtt")) {
            String[] blocks = b.replace("\r", "").split("\n\n+");
            for (String block : blocks) {
                String[] lines = block.split("\n");
                int at = -1; StringBuilder text = new StringBuilder();
                for (String line : lines) {
                    Matcher m = CUE.matcher(line);
                    if (m.find()) { at = Integer.parseInt(m.group(1)) * 3600 + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3)); continue; }
                    Matcher s = SHORT_CUE.matcher(line);
                    if (s.find()) { at = Integer.parseInt(s.group(1)) * 60 + Integer.parseInt(s.group(2)); continue; }
                    if (line.matches("^\\d+$") || line.startsWith("WEBVTT") || line.startsWith("NOTE") || line.isBlank()) continue;
                    if (at >= 0) text.append(text.length() == 0 ? "" : " ").append(line.replaceAll("<[^>]+>", "").strip());
                }
                if (at >= 0 && text.length() > 0) out.add(new VideoText.Line(at, text.toString()));
            }
            if (!out.isEmpty()) return merge(out);
        }
        String plain = t.contains("html") || b.startsWith("<") ? strip(b) : b;
        if (!plain.isBlank()) for (String para : plain.split("\n\\s*\n")) if (!para.isBlank()) out.add(new VideoText.Line(0, para.replaceAll("\\s+", " ").strip()));
        return out;
    }

    /** Cues joined into lines of a sentence or two, so a transcript reads as speech and not as subtitles; a pause of more than a few seconds starts a new line, so a time still names its words. */
    static List<VideoText.Line> merge(List<VideoText.Line> cues) {
        List<VideoText.Line> out = new ArrayList<>();
        int at = -1, last = -1; StringBuilder text = new StringBuilder();
        for (VideoText.Line c : cues) {
            if (at >= 0 && c.startSeconds() - last > 8 && text.length() > 0) { out.add(new VideoText.Line(at, text.toString())); at = -1; text = new StringBuilder(); }
            if (at < 0) at = c.startSeconds();
            last = c.startSeconds();
            text.append(text.length() == 0 ? "" : " ").append(c.text());
            if (text.length() > 240 || c.text().matches(".*[.!?。]$") && text.length() > 80) { out.add(new VideoText.Line(at, text.toString())); at = -1; text = new StringBuilder(); }
        }
        if (text.length() > 0) out.add(new VideoText.Line(Math.max(0, at), text.toString()));
        return out;
    }

    /** The transcript as the library keeps it: one line per timed line, {@code [h:mm:ss] text}. */
    static String saved(List<VideoText.Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (VideoText.Line l : lines) sb.append('[').append(VideoText.clock(l.startSeconds())).append("] ").append(l.text()).append('\n');
        return sb.toString();
    }

    static final Pattern SAVED = Pattern.compile("^\\[(?:(\\d+):)?(\\d{1,2}):(\\d{2})\\] (.*)$");

    static List<VideoText.Line> parseSaved(String text) {
        List<VideoText.Line> out = new ArrayList<>();
        if (text == null) return out;
        for (String line : text.split("\n")) {
            Matcher m = SAVED.matcher(line.strip());
            if (m.matches()) out.add(new VideoText.Line((m.group(1) == null ? 0 : Integer.parseInt(m.group(1)) * 3600) + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3)), m.group(4)));
        }
        return out;
    }

    static void keep(LibraryStore store, String key, Episode ep, List<VideoText.Line> lines) {
        if (store == null || key.isEmpty()) return;
        try { RawCapture.capture(store, key, saved(lines), (ep.feedTitle().isEmpty() ? "" : ep.feedTitle() + " — ") + ep.title(), BY, "", ep.date()); } catch (Exception ignored) { }
    }

    /** Whether the library already holds this episode's transcript. */
    public static boolean saved(LibraryStore store, Episode ep) {
        try { return store != null && !ep.audioUrl().isEmpty() && RawCapture.find(store, ep.audioUrl()) != null; } catch (Exception e) { return false; }
    }

    /**
     * The housekeeping's step (0.5.5): for every podcast the library follows, the newest episodes whose transcript it does not hold yet are
     * transcribed — the show's own transcript when published, else the library's server — within the night's minutes
     * (RESEARCHZOSHO_TRANSCRIBE_FOLLOWED_MINUTES; 0 = no cap). Says what it did in a sentence.
     */
    public static String transcribeFollowed(LibraryStore store) throws IOException {
        List<Serials.Shelf> shelves = new ArrayList<>();
        for (Serials.Shelf s : Serials.shelves(store)) if (s.podcast() && !s.parked()) shelves.add(s);
        if (shelves.isEmpty()) return "no podcast followed";
        int minutes = Config.getInt("RESEARCHZOSHO_TRANSCRIBE_FOLLOWED_MINUTES", 0);
        Budget budget = minutes <= 0 ? new Budget(Integer.MAX_VALUE, Long.MAX_VALUE, false) : new Budget(Integer.MAX_VALUE, minutes * 60L, true);
        int done = 0, waiting = 0, published = 0; String stopped = "";
        for (Serials.Shelf s : shelves) {
            var fe = feed(s.feedUrl(), 10);
            if (fe == null) continue;
            for (Episode e : fe.getValue()) {
                if (saved(store, e)) continue;
                StringBuilder why = new StringBuilder();
                Transcript t = transcript(store, e, "", budget, why);
                if (t == null) { waiting++; if (why.toString().contains("RESEARCHZOSHO_TRANSCRIBE") || why.toString().contains("no transcription server")) stopped = why.toString(); continue; }
                done++; if (t.how().startsWith("published")) published++;
            }
        }
        return done + " episode(s) transcribed (" + published + " from the shows' own transcripts), " + waiting + " waiting" + (stopped.isEmpty() ? "" : " — " + stopped);
    }

    static Map<String, Show> byFeed(List<Show> shows) {
        Map<String, Show> out = new LinkedHashMap<>();
        if (shows != null) for (Show s : shows) if (!s.feedUrl().isEmpty()) out.putIfAbsent(s.feedUrl().replaceFirst("^http://", "https://"), s);
        return out;
    }
}
