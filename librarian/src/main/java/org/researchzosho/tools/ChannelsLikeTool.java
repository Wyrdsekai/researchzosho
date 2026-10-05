package org.researchzosho.tools;

import org.researchzosho.drive.Declined;
import org.researchzosho.librarian.Declines;
import org.researchzosho.librarian.Embeddings;
import org.researchzosho.librarian.Explain;
import org.researchzosho.librarian.Fence;
import org.researchzosho.librarian.Researcher;
import org.researchzosho.librarian.Video;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
/**
 * "Channels like this one": the channels that make what a given channel makes, found by what they make and never by their size.
 * <p>
 * The way that was measured to work (2026-09-29, a held-out list of 21 channels of one kind): search phrasings a model writes about the
 * <em>subject</em> — the activity, the gear, the events, the words people in the field use — each run as a channel search (two pages,
 * which reach the small channels) and a video search (one page, which reaches channels that do not name their subject in their own
 * name); a channel found by several phrasings makes this kind of thing. The first version of this tool wrote its phrasings mechanically
 * from the channel's description and title words and found 1 in 100 of the held-out channels: a description's opening is a greeting
 * and the frequent title words are people's names and sponsors. So the phrasings come from the model, given the channel's own text;
 * without a model they come from the titles' word pairs, and the answer says so.
 * <p>
 * Ranked by how many phrasings found a channel, then by how alike it describes itself (the library's embedder when one is configured,
 * else shared words). Subscribers are shown beside each, never ranked on. Thirteen YouTube calls at the helper's pace: a few minutes.
 */
public final class ChannelsLikeTool implements Tool {

    private static final ObjectMapper J = new ObjectMapper();
    static final int MOST = 20;
    /** How many phrasings, how deep each search goes: the measured setting (many phrasings and a second page both paid). */
    static final int PHRASINGS = 6, CHANNEL_PAGE = 40, VIDEO_PAGE = 20, TITLES = 30;

    /** Scores a pair of texts; the embedder's cosine when one answers, else shared words. Tests replace it. */
    public interface Similarity { double of(String a, String b); }
    static volatile Similarity similarity = ChannelsLikeTool::defaultSimilarity;
    /** Where the model comes from: the configured drive; a test hands in its own. */
    public static volatile Supplier<Researcher.Drive> drives = Explain::drive;

    private volatile Researcher.Drive drive;

    /** The run's own drive for the phrasings; without one the configured drive is used. */
    public ChannelsLikeTool drive(Researcher.Drive d) { this.drive = d; return this; }

    @Override public String name() { return "channels_like"; }

    @Override public String description() {
        return "YouTube channels like a given one: channels that make what it makes, found by searching for its subject the way a person would "
                + "(several phrasings, channels and videos) and ranked by how many searches found each — never by subscribers, which are shown beside each. "
                + "Takes the channel's address, id (UC…) or handle (@name). Takes a few minutes.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("channel").put("type", "string").put("description", "the channel to find others like: address, id (UC…) or handle (@name)");
        props.putObject("limit").put("type", "integer").put("description", "how many channels to return (default 10, up to " + MOST + ")");
        p.putArray("required").add("channel");
        return p;
    }

    /** What a channel says about itself: its description and its newest titles. */
    public record Seed(String id, String name, String url, long subscribers, String description, List<String> titles) {
        String profile() { return (description + "\n" + String.join("\n", titles)).strip(); }
    }

    /** A candidate channel with the phrasings that found it and how. */
    public record Like(String id, String name, String url, long subscribers, String description, int hits, String how, double alike, String shared) { }

    @Override public String execute(JsonNode args) {
        String given = args.path("channel").asText("").strip();
        String url = VideoText.channelUrl(given);
        if (url == null) return "ERROR: not a channel: give the channel's address (https://www.youtube.com/channel/UC… or https://www.youtube.com/@name), its id or its handle";
        int limit = Math.max(1, Math.min(MOST, args.path("limit").asInt(10)));
        Seed seed = seed(url);
        if (seed == null) return "ERROR: the channel could not be read" + (Video.refusal().isEmpty() ? "" : ": " + Video.refusal());
        Researcher.Drive d = drive != null ? drive : drives.get();
        List<String> phrasings;
        try { phrasings = phrasings(seed, d); }
        catch (Declined x) { return "The model declined to write search phrasings for " + seed.name() + " (" + x.getMessage() + "); nothing was searched."; }
        if (phrasings.isEmpty()) return "ERROR: nothing to search for: " + seed.name() + " has no description and no titles to write phrasings from";
        List<Like> likes = likes(seed, phrasings, limit);
        String searched = (d == null ? "searched (phrasings from the titles' word pairs — no model answers): " : "searched: ") + String.join(" / ", phrasings);
        if (likes.isEmpty()) return "no channels found like " + seed.name() + "; " + searched;
        StringBuilder sb = new StringBuilder("channels like " + seed.name() + " (" + seed.url() + "), by how many of " + phrasings.size() + " searches found each; " + searched
                + " — YouTube's own text, fenced:\n" + Fence.open("CHANNELS LIKE") + "\n");
        int n = 0;
        for (Like l : likes) {
            sb.append(++n).append(". ").append(l.name()).append(l.subscribers() >= 0 ? " — " + VideoText.count(l.subscribers()) + " subscribers" : "")
              .append(" · found by ").append(l.hits()).append(" of ").append(phrasings.size()).append(" (").append(l.how()).append(')')
              .append(" · alike ").append(String.format(Locale.ROOT, "%.2f", l.alike()));
            if (!l.shared().isEmpty()) sb.append(" (shares: ").append(l.shared()).append(')');
            sb.append("\n   ").append(l.url()).append('\n');
            if (!l.description().isBlank()) sb.append("   ").append(l.description().length() > 200 ? l.description().substring(0, 200) + "…" : l.description().replace('\n', ' ')).append('\n');
        }
        sb.append(Fence.close("CHANNELS LIKE")).append('\n').append(Fence.rule("CHANNELS LIKE")).append('\n');
        return sb.toString();
    }

    /** The seed from one yt-dlp listing of its uploads tab: the channel's id, name, description and subscribers come with the titles. */
    public static Seed seed(String channelUrl) {
        Video.Result r = Video.ytdlp(List.of("--flat-playlist", "--playlist-end", String.valueOf(TITLES), "--dump-single-json", channelUrl.replaceAll("/+$", "") + "/videos"), Duration.ofMinutes(3));
        if (r.code() != 0) return null;
        try {
            JsonNode d = J.readTree(r.out().substring(r.out().indexOf('{')));
            String id = d.path("channel_id").asText(d.path("id").asText(""));
            String name = d.path("channel").asText(d.path("uploader").asText(d.path("title").asText(id)));
            List<String> titles = new ArrayList<>();
            for (JsonNode e : d.path("entries")) { String t = e.path("title").asText(""); if (!t.isBlank()) titles.add(t); }
            return new Seed(id, name, "https://www.youtube.com/channel/" + id, d.path("channel_follower_count").asLong(-1), d.path("description").asText(""), titles);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The phrasings a person would type to find channels that make what the seed makes: written by the model from the seed's own text,
     * about the subject and never about the seed's own name or people; from the titles' word pairs when no model answers.
     */
    public static List<String> phrasings(Seed seed, Researcher.Drive drive) {
        if (seed.profile().isBlank()) return List.of();
        if (drive == null) return mechanical(seed);
        StringBuilder p = new StringBuilder();
        p.append("Here is what a YouTube channel says about itself: its description and the titles of its newest videos.\n\n");
        p.append("DESCRIPTION:\n").append(seed.description().isBlank() ? "(none)" : seed.description().strip()).append("\n\nNEWEST TITLES:\n");
        for (String t : seed.titles()) p.append("- ").append(t).append('\n');
        p.append("\nWrite ").append(PHRASINGS).append(" different search phrasings a person would type into YouTube to find OTHER channels that make the same kind of videos. ")
         .append("Each names the subject — the activity, the discipline, the gear, the events, the words people in this field use — in the language the titles are in. ")
         .append("Cover different sides of what the channel makes. Two to seven words each. ")
         .append("Leave out the channel's own name and the names of the people in it: the point is the others, not this channel again.\n")
         .append("One phrasing per line, no numbering, nothing else.");
        ArrayNode msgs = J.createArrayNode();
        msgs.addObject().put("role", "user").put("content", p.toString());
        String raw;
        try (var step = Declines.step("write YouTube search phrasings for the kind of videos a channel makes")) { raw = drive.classify(msgs, 400); }
        List<String> out = new ArrayList<>();
        Set<String> own = new HashSet<>(words(seed.name())), seen = new HashSet<>();
        for (String line : raw == null ? new String[0] : raw.split("\\R")) {
            String s = line.strip().replaceFirst("^[-*•·\\d.)\\]\\s]+", "").replaceAll("^[\"“”'`]+|[\"“”'`.]+$", "").strip();
            if (s.isEmpty() || s.contains(":")) continue;
            int wordsIn = s.split("\\s+").length;
            if (wordsIn < 2 || wordsIn > 8) continue;
            List<String> ws = words(s);
            if (!own.isEmpty() && ws.containsAll(own)) continue;   // the seed's own name again: not a phrasing for the others
            if (seen.add(String.join(" ", ws))) out.add(s);
            if (out.size() >= PHRASINGS) break;
        }
        return out.size() >= 2 ? out : mechanical(seed);
    }

    /** Without a model: the word pairs the titles use most, then the single words, the seed's own name left out. */
    static List<String> mechanical(Seed seed) {
        Set<String> own = new HashSet<>(words(seed.name()));
        Map<String, Integer> pairs = new HashMap<>(), singles = new HashMap<>();
        for (String t : seed.titles()) {
            List<String> ws = new ArrayList<>();
            for (String w : words(t)) if (!own.contains(w)) ws.add(w);
            for (String w : new LinkedHashSet<>(ws)) singles.merge(w, 1, Integer::sum);
            Set<String> inTitle = new LinkedHashSet<>();
            for (int i = 0; i + 1 < ws.size(); i++) inTitle.add(ws.get(i) + " " + ws.get(i + 1));
            for (String pr : inTitle) pairs.merge(pr, 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        pairs.entrySet().stream().filter(e -> e.getValue() >= 2).sorted((a, b) -> !b.getValue().equals(a.getValue()) ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()))
             .limit(PHRASINGS - 1).forEach(e -> out.add(e.getKey()));
        List<String> top = singles.entrySet().stream().sorted((a, b) -> !b.getValue().equals(a.getValue()) ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey())).map(Map.Entry::getKey).limit(3).toList();
        if (top.size() >= 2) out.add(String.join(" ", top));
        return out.stream().distinct().limit(PHRASINGS).toList();
    }

    /** A candidate as the searches build it up. */
    private static final class Cand {
        String name, url, description = ""; long subscribers = -1;
        final Set<String> phrasings = new LinkedHashSet<>(); final List<String> titles = new ArrayList<>();
        boolean byChannel, byVideo;
    }

    /** Every phrasing as a channel search (two pages) and a video search (one page); the channels tallied, the seed left out, best first. */
    public static List<Like> likes(Seed seed, List<String> phrasings, int limit) {
        Map<String, Cand> found = new LinkedHashMap<>();
        for (String q : phrasings) {
            Video.Result c = Video.ytdlp(List.of("--flat-playlist", "--dump-single-json", "--playlist-end", String.valueOf(CHANNEL_PAGE),
                    "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&sp=EgIQAg%253D%253D"), Duration.ofMinutes(3));
            if (c.code() == 0) for (JsonNode e : entries(c)) {
                String id = e.path("channel_id").asText(e.path("id").asText(""));
                if (id.isEmpty() || id.equals(seed.id())) continue;
                Cand k = found.computeIfAbsent(id, x -> new Cand());
                k.phrasings.add(q); k.byChannel = true;
                if (k.name == null) k.name = e.path("title").asText(e.path("channel").asText("?"));
                if (k.url == null) k.url = e.path("url").asText("https://www.youtube.com/channel/" + id);
                if (k.description.isEmpty()) k.description = e.path("description").asText("");
                if (k.subscribers < 0) k.subscribers = e.path("channel_follower_count").asLong(-1);
            }
            Video.Result v = Video.ytdlp(List.of("--flat-playlist", "--dump-single-json", "ytsearch" + VIDEO_PAGE + ":" + q), Duration.ofMinutes(3));
            if (v.code() == 0) for (JsonNode e : entries(v)) {
                String id = e.path("channel_id").asText("");
                if (id.isEmpty() || id.equals(seed.id())) continue;
                Cand k = found.computeIfAbsent(id, x -> new Cand());
                k.phrasings.add(q); k.byVideo = true;
                if (k.name == null) k.name = e.path("channel").asText(e.path("uploader").asText("?"));
                if (k.url == null) k.url = e.path("channel_url").asText("https://www.youtube.com/channel/" + id);
                String t = e.path("title").asText(""); if (!t.isBlank() && k.titles.size() < 10) k.titles.add(t);
            }
        }
        String profile = seed.profile();
        List<Like> out = new ArrayList<>();
        for (Map.Entry<String, Cand> e : found.entrySet()) {
            Cand k = e.getValue();
            String text = k.name + "\n" + k.description + "\n" + String.join("\n", k.titles);
            String how = k.byChannel && k.byVideo ? "channel and video search" : k.byChannel ? "channel search" : "video search";
            out.add(new Like(e.getKey(), k.name, k.url, k.subscribers, k.description, k.phrasings.size(), how, Math.max(0, Math.min(1, similarity.of(profile, text))), shared(profile, text)));
        }
        out.sort((a, b) -> a.hits() != b.hits() ? b.hits() - a.hits() : Double.compare(b.alike(), a.alike()));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private static JsonNode entries(Video.Result r) {
        try { return J.readTree(r.out().substring(r.out().indexOf('{'))).path("entries"); }
        catch (Exception e) { return J.createArrayNode(); }
    }

    /** The embedder's cosine when one answers, else the share of words the two texts have in common. */
    static double defaultSimilarity(String a, String b) {
        try {
            Embeddings.Embedder e = Embeddings.configured();
            float[] va = e.embed(a), vb = e.embed(b);
            if (va != null && vb != null && va.length == vb.length) {
                double dot = 0, na = 0, nb = 0;
                for (int i = 0; i < va.length; i++) { dot += va[i] * vb[i]; na += va[i] * va[i]; nb += vb[i] * vb[i]; }
                return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
            }
        } catch (Exception ignored) { }
        return wordOverlap(a, b);
    }

    /** The words two texts share over the words of the shorter one, stop words and the very short left out. */
    static double wordOverlap(String a, String b) {
        Set<String> wa = new HashSet<>(words(a)), wb = new HashSet<>(words(b));
        if (wa.isEmpty() || wb.isEmpty()) return 0;
        int common = 0;
        for (String w : wb) if (wa.contains(w)) common++;
        return common / (double) Math.min(wa.size(), wb.size());
    }

    /** The words two texts share, a few of them, for the reader. */
    static String shared(String a, String b) {
        Set<String> wa = new HashSet<>(words(a));
        List<String> out = new ArrayList<>();
        for (String w : new LinkedHashSet<>(words(b))) if (wa.contains(w) && out.size() < 5) out.add(w);
        return String.join(", ", out);
    }

    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "that", "this", "from", "you", "your", "our", "are", "was", "were", "have", "has",
            "not", "but", "all", "any", "can", "will", "how", "what", "why", "who", "when", "where", "which", "about", "into", "than", "then", "them", "they",
            "video", "videos", "channel", "channels", "subscribe", "youtube", "new", "more", "best", "top", "vs", "part", "episode", "live", "official", "com", "www", "http", "https");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{3,}");

    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        var m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) { String w = m.group(); if (!STOP.contains(w) && !w.matches("\\d+")) out.add(w); }
        return out;
    }
}
