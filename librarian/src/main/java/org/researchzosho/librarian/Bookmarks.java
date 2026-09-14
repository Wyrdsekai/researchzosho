package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A browser's bookmarks as a starting point: the Netscape bookmark file every browser exports
 * ({@code <DT><A HREF=…>title</A>} under {@code <H3>} folders), Chrome's {@code Bookmarks} JSON, or plain
 * lines of urls. Each page is fetched and shelved; the list can be registered so the housekeeping
 * re-reads the pages and keeps a new capture when one changes.
 */
public final class Bookmarks {

    public record Mark(String title, String url, String folder) { }

    static final int MAX = org.researchzosho.Config.getInt("RESEARCHZOSHO_BOOKMARKS_MAX", 200);
    static final Pattern A = Pattern.compile("(?is)<(H3|A)\\b([^>]*)>(.*?)</\\1>");
    static final Pattern HREF = Pattern.compile("(?i)HREF=\"([^\"]+)\"");
    private static final ObjectMapper M = new ObjectMapper();

    private Bookmarks() { }

    public static List<Mark> parse(String text) {
        String head = text.stripLeading();
        List<Mark> out;
        if (head.startsWith("{")) out = chrome(text);
        else if (head.toUpperCase().contains("<!DOCTYPE NETSCAPE-BOOKMARK") || head.toUpperCase().contains("<DL>")) out = netscape(text);
        else out = plain(text);
        Set<String> seen = new LinkedHashSet<>();
        List<Mark> uniq = new ArrayList<>();
        for (Mark m : out) if (m.url().startsWith("http") && seen.add(m.url()) && uniq.size() < MAX) uniq.add(m);
        return uniq;
    }

    static List<Mark> netscape(String html) {
        List<Mark> out = new ArrayList<>();
        String folder = "";
        Matcher m = A.matcher(html);
        while (m.find()) {
            String inner = Pages.unentity(m.group(3).replaceAll("<[^>]+>", "")).strip();
            if (m.group(1).equalsIgnoreCase("H3")) { folder = inner; continue; }
            Matcher h = HREF.matcher(m.group(2));
            if (h.find()) out.add(new Mark(inner, Pages.unentity(h.group(1)), folder));
        }
        return out;
    }

    static List<Mark> chrome(String json) {
        List<Mark> out = new ArrayList<>();
        try { walk(M.readTree(json).path("roots"), "", out); } catch (Exception ignored) { }
        return out;
    }

    private static void walk(JsonNode n, String folder, List<Mark> out) {
        if (n.isObject()) {
            if ("url".equals(n.path("type").asText()) && n.path("url").isTextual()) { out.add(new Mark(n.path("name").asText(""), n.path("url").asText(), folder)); return; }
            String f = n.path("name").isTextual() && n.path("children").isArray() ? n.path("name").asText() : folder;
            var it = n.fields();
            while (it.hasNext()) { var e = it.next(); walk(e.getValue(), f, out); }
        } else if (n.isArray()) for (JsonNode c : n) walk(c, folder, out);
    }

    static List<Mark> plain(String text) {
        List<Mark> out = new ArrayList<>();
        for (Items.Item it : Items.parse(text, null)) {
            Matcher u = Drafts.URL.matcher(it.line());
            if (!u.find()) continue;
            String url = u.group();
            String title = it.line().replace(url, "").replaceAll("^\\s*[—–-]\\s*|\\s*[—–-]\\s*$", "").strip();
            out.add(new Mark(title, url, ""));
        }
        return out;
    }
}
