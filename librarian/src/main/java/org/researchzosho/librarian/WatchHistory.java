package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.tools.VideoText;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The person's own YouTube watch history, filed as a shelf of watched videos: the {@code watch-history.json} (or {@code .html}) that
 * Google Takeout writes, read into one line a video (its id, title, channel and the day it was watched) under the library's catalog,
 * and kept as a capture on the shelves too, so the shelf search finds it. The candidate step for a remembered video reads it first:
 * a person who found a video once has it here, whatever the engines rank. It stays in the library like everything else the library
 * holds; whoever may read the library may read it.
 */
public final class WatchHistory {
    private WatchHistory() { }

    private static final ObjectMapper M = new ObjectMapper();
    static final String FILE = "watch-history.jsonl";
    public static final String BY = "researchzosho-watch-history";

    /** One watched video: its id, its title as the history wrote it, the channel's name, and the day it was watched (YYYY-MM-DD, "" when the history gives none). */
    public record Watched(String id, String title, String channel, String watchedOn) { }

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve(FILE); }

    /** Whether a file is a YouTube watch history as Takeout writes it: by its name, else by the shape of its first kilobytes. */
    public static boolean looksLike(Path f) {
        if (f == null || !Files.isRegularFile(f)) return false;
        String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.startsWith("watch-history") && (name.endsWith(".json") || name.endsWith(".html"))) return true;
        try {
            byte[] head = new byte[8192];
            int n;
            try (var in = Files.newInputStream(f)) { n = in.read(head); }
            if (n <= 0) return false;
            String s = new String(head, 0, n, StandardCharsets.UTF_8);
            return s.contains("youtube.com/watch?v=") && (s.contains("\"titleUrl\"") || s.contains("Watched"));
        } catch (IOException e) { return false; }
    }

    /**
     * Reads the history and files what is new: the videos not already held with the same watch day. Returns how many were added. The
     * whole shelf is written again as one capture under the file's address, so the shelves hold the history as text.
     */
    public static int add(LibraryStore store, Path f) throws IOException {
        List<Watched> read = parse(Files.readString(f, StandardCharsets.UTF_8), f.getFileName().toString());
        List<Watched> held = all(store);
        Set<String> seen = new HashSet<>();
        for (Watched w : held) seen.add(w.id() + "|" + w.watchedOn());
        StringBuilder lines = new StringBuilder();
        int added = 0;
        for (Watched w : read) {
            if (!seen.add(w.id() + "|" + w.watchedOn())) continue;
            ObjectNode o = M.createObjectNode().put("id", w.id()).put("title", w.title()).put("channel", w.channel()).put("watched_on", w.watchedOn());
            lines.append(o).append('\n');
            added++;
        }
        Path file = file(store);
        Files.createDirectories(file.getParent());
        if (added > 0) Files.writeString(file, lines.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (added > 0 || held.isEmpty()) {
            List<Watched> now = all(store);
            StringBuilder text = new StringBuilder("The YouTube watch history, " + now.size() + (now.size() == 1 ? " video" : " videos") + span(now) + ".\n");
            for (Watched w : now) text.append(w.watchedOn().isEmpty() ? "" : w.watchedOn() + " ").append("watched ").append(w.title()).append(w.channel().isEmpty() ? "" : " (" + w.channel() + ")").append(" https://www.youtube.com/watch?v=").append(w.id()).append('\n');
            RawCapture.capture(store, "file://" + f.toAbsolutePath().normalize(), text.toString(), "YouTube watch history", BY, "watch-history");
        }
        return added;
    }

    /** Every watched video held, newest first. */
    public static List<Watched> all(LibraryStore store) {
        List<Watched> out = new ArrayList<>();
        Path file = file(store);
        if (!Files.isRegularFile(file)) return out;
        try {
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (l.isBlank()) continue;
                try { JsonNode j = M.readTree(l); out.add(new Watched(j.path("id").asText(""), j.path("title").asText(""), j.path("channel").asText(""), j.path("watched_on").asText(""))); }
                catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
        out.sort((a, b) -> b.watchedOn().compareTo(a.watchedOn()));
        return out;
    }

    /** Whether the library holds a watch history at all. */
    public static boolean held(LibraryStore store) { return store != null && Files.isRegularFile(file(store)); }

    /** The watched videos whose title names one of the things, newest watch first, one line a video (the latest watch of it). */
    public static List<Watched> matching(LibraryStore store, List<String> things) {
        Map<String, Watched> out = new LinkedHashMap<>();
        if (store == null) return List.of();
        for (Watched w : all(store)) if (!out.containsKey(w.id()) && Candidates.names(w.title(), things)) out.put(w.id(), w);
        return new ArrayList<>(out.values());
    }

    /** What the library holds, in a sentence, in English: what a program and the terminal read. */
    public static String summary(LibraryStore store) { return summary(store, Chrome.ENGLISH); }

    /** What the library holds, in a sentence of {@code c}'s language: a whole sentence for one video and for many, with the span of the watches or without. */
    public static String summary(LibraryStore store, Chrome c) {
        List<Watched> all = all(store);
        if (all.isEmpty()) return c.t("No watch history is filed. researchzosho add <the watch-history.json from Google Takeout> files one.");
        String first = "", last = "";
        for (Watched w : all) { if (w.watchedOn().isEmpty()) continue; if (first.isEmpty() || w.watchedOn().compareTo(first) < 0) first = w.watchedOn(); if (w.watchedOn().compareTo(last) > 0) last = w.watchedOn(); }
        boolean one = all.size() == 1;
        if (first.isEmpty()) return one ? c.t("The library holds {0} watched video.", 1) : c.t("The library holds {0} watched videos.", c.number(all.size()));
        return one ? c.t("The library holds {0} watched video watched between {1} and {2}.", 1, c.day(first), c.day(last)) : c.t("The library holds {0} watched videos watched between {1} and {2}.", c.number(all.size()), c.day(first), c.day(last));
    }

    /** The span of the watches as the shelved text says it: " watched between … and …", or "". */
    static String span(List<Watched> all) {
        String first = "", last = "";
        for (Watched w : all) { if (w.watchedOn().isEmpty()) continue; if (first.isEmpty() || w.watchedOn().compareTo(first) < 0) first = w.watchedOn(); if (w.watchedOn().compareTo(last) > 0) last = w.watchedOn(); }
        return first.isEmpty() ? "" : " watched between " + first + " and " + last;
    }

    // ---- reading Takeout's two shapes

    private static final Pattern HTML_ENTRY = Pattern.compile("Watched(?:&nbsp;|\\s)*<a href=\"https?://(?:www\\.)?youtube\\.com/watch\\?v=([A-Za-z0-9_-]{11})[^\"]*\">([^<]*)</a>(?:<br\\s*/?>\\s*<a href=\"[^\"]*\">([^<]*)</a>)?(?:<br\\s*/?>\\s*([^<]{4,60}))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_DAY = Pattern.compile("([A-Z][a-z]{2}) (\\d{1,2}), (\\d{4})");
    private static final DateTimeFormatter HTML_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    /** What Takeout puts round a title in the languages it writes: "Watched X", "X を視聴しました", "X을(를) 시청했습니다". */
    private static final List<String> PREFIXES = List.of("Watched ", "Visionnage de ", "Angesehen: ", "Has visto ", "Hai guardato ");
    private static final List<String> SUFFIXES = List.of(" を視聴しました", "を視聴しました", "을(를) 시청했습니다", " 시청함");

    /** The watched videos in a history file's text, JSON or HTML as Takeout writes them; an entry without a video address is passed over. */
    public static List<Watched> parse(String text, String fileName) {
        List<Watched> out = new ArrayList<>();
        String t = text.stripLeading();
        if (t.startsWith("[") || fileName.toLowerCase(Locale.ROOT).endsWith(".json")) {
            JsonNode root;
            try { root = M.readTree(t); } catch (IOException e) { return out; }
            if (!root.isArray()) return out;
            for (JsonNode e : root) {
                String id = VideoText.videoId(e.path("titleUrl").asText(""));
                if (id == null) continue;
                String channel = e.path("subtitles").isArray() && e.path("subtitles").size() > 0 ? e.path("subtitles").get(0).path("name").asText("") : "";
                String time = e.path("time").asText("");
                out.add(new Watched(id, plainTitle(e.path("title").asText("")), channel, time.length() >= 10 ? time.substring(0, 10) : ""));
            }
            return out;
        }
        Matcher m = HTML_ENTRY.matcher(text);
        while (m.find()) {
            String day = "";
            String tail = m.group(4) == null ? "" : m.group(4);
            Matcher d = HTML_DAY.matcher(tail);
            if (d.find()) { try { day = LocalDate.parse(d.group(1) + " " + d.group(2) + ", " + d.group(3), HTML_FORMAT).toString(); } catch (Exception ignored) { } }
            out.add(new Watched(m.group(1), plainTitle(unescape(m.group(2))), m.group(3) == null ? "" : unescape(m.group(3)).strip(), day));
        }
        return out;
    }

    /** The few entities Takeout's HTML writes in a title. */
    static String unescape(String s) {
        return s == null ? "" : s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    static String plainTitle(String title) {
        String t = title == null ? "" : title.strip();
        for (String p : PREFIXES) if (t.startsWith(p)) { t = t.substring(p.length()); break; }
        for (String s : SUFFIXES) if (t.endsWith(s)) { t = t.substring(0, t.length() - s.length()); break; }
        return t.strip();
    }
}
