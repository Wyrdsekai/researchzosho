package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.tools.Fetch;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.util.function.Function;
/**
 * Who mentions this. What a person took part in is written up under the event, the institution or the work, not under the person:
 * their own page gives an office and its dates, and the affair that ended it has a page of its own. A search by the person's name
 * finds the first and only by luck the second. The pages of an encyclopedia that LINK TO the person's page are exactly the second
 * kind, and the encyclopedia lists them on request. The list is made by the library, without a model; a research run is handed the
 * titles as leads and decides which to open. Nothing here is about families: a scientist, a firm or a program has such a list too.
 */
public final class Mentions {

    private Mentions() { }

    /** As many as the encyclopedia has, up to this: the order it gives them in means nothing, so none is cut for its place in it. */
    static final int MOST = 3000;
    static final int CHUNK = 150, LEADS = 100;
    private static final ObjectMapper J = new ObjectMapper();
    private static final Pattern WIKI = Pattern.compile("^https?://([a-z-]+)\\.wikipedia\\.org/wiki/([^?#]+)");

    /** An address in, the body out. The live one is {@link Fetch}; a test passes its own. */
    public interface Reader { String read(String url) throws Exception; }

    public static final Reader LIVE = url -> {
        Fetch.Result r = Fetch.get(url, Duration.ofSeconds(25));
        if (r.status() >= 400) throw new IllegalStateException("HTTP " + r.status());
        return new String(r.body(), StandardCharsets.UTF_8);
    };

    /** The title of an encyclopedia page from its address, as the page writes it, or null when the address is not one. [0] the language, [1] the title. */
    public static String[] page(String url) {
        Matcher m = WIKI.matcher(url == null ? "" : url.strip());
        if (!m.find()) return null;
        return new String[]{m.group(1), URLDecoder.decode(m.group(2), StandardCharsets.UTF_8).replace('_', ' ')};
    }

    /**
     * The pages that link to this page: articles only, without the pages that link to everything (a year, a day, a list), at most
     * {@value #MOST}. Empty when the encyclopedia cannot be asked: a lead that is missing loses nothing that was there before.
     */
    public static List<String> of(String lang, String title, Reader reader) {
        Set<String> out = new LinkedHashSet<>();
        String more = "";
        try {
            for (int pages = 0; pages < 8 && out.size() < MOST; pages++) {
                if (pages > 0) Thread.sleep(1200);   // the encyclopedia asks for unhurried requests
                String url = "https://" + lang + ".wikipedia.org/w/api.php?action=query&list=backlinks&blnamespace=0&blfilterredir=nonredirects&bllimit=500&format=json&bltitle="
                        + URLEncoder.encode(title, StandardCharsets.UTF_8) + more;
                JsonNode n = J.readTree(reader.read(url));
                for (JsonNode b : n.path("query").path("backlinks")) { String t = b.path("title").asText(""); if (!t.isBlank() && !everywhere(t) && out.size() < MOST) out.add(t); }
                String next = n.path("continue").path("blcontinue").asText("");
                if (next.isEmpty()) break;
                more = "&blcontinue=" + URLEncoder.encode(next, StandardCharsets.UTF_8);
            }
        } catch (Exception e) { return new ArrayList<>(out); }
        return new ArrayList<>(out);
    }

    /**
     * The titles among these that a research run should open: an event, an affair, a trial, a war, an organisation, a work the person
     * could have had a part in. The model reads titles only, a chunk at a time, and answers with titles copied from the list; a title that
     * is not in the list is dropped. The list came from the encyclopedia: the model chooses among what it was shown and names nothing itself.
     */
    public static List<String> sift(String person, String what, List<String> titles, Function<String, String> model) {
        Set<String> kept = new LinkedHashSet<>();
        for (int at = 0; at < titles.size(); at += CHUNK) {
            List<String> chunk = titles.subList(at, Math.min(titles.size(), at + CHUNK));
            StringBuilder list = new StringBuilder();
            for (String t : chunk) list.append("- ").append(t).append('\n');
            String raw = model.apply("These are titles of encyclopedia pages that mention " + person + (what == null || what.isBlank() ? "" : " (" + what + ")") + ".\n"
                    + "Pick the titles that are something a person can have had a part in: an event, an incident or an affair, a scandal, a trial, a war or a battle, a treaty, "
                    + "a disaster, a movement, a company or an organisation, a work. Leave the titles that are another person, a place, a family, an office or a rank, a date.\n"
                    + "Answer with JSON only: {\"titles\": [\"…\"]}, each title copied exactly as it stands in the list. An empty list is a good answer when none fits.\n\n" + list);
            if (raw == null) continue;
            int a = raw.indexOf('{'), b = raw.lastIndexOf('}');
            if (a < 0 || b <= a) continue;
            try { for (JsonNode t : J.readTree(raw.substring(a, b + 1)).path("titles")) if (chunk.contains(t.asText(""))) kept.add(t.asText()); }
            catch (Exception notJson) { }
        }
        // no second choosing among the kept ones: tried on public figures with a known affair, a second choice by the model lost the affair for
        // two of six much-linked people under one wording and for a different one under another. The run gets what was kept, and chooses what to open
        List<String> out = new ArrayList<>(kept);
        return out.size() > LEADS ? out.subList(0, LEADS) : out;
    }

    /** A page that links to thousands of others by its nature, and says nothing about any of them: a year, a day of the year, a list. */
    static boolean everywhere(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        return t.matches("\\d{1,4}年?(代)?") || t.matches("\\d{1,2}月\\d{1,2}日") || t.matches("(january|february|march|april|may|june|july|august|september|october|november|december) \\d{1,2}")
                || t.matches("\\d{3,4}s?( in .*)?") || t.startsWith("list of ") || t.startsWith("lists of ") || t.endsWith("一覧") || t.endsWith("の一覧") || t.contains("(disambiguation)") || t.contains("曖昧さ回避");
    }

    /** {@code https://ja.wikipedia.org/wiki/…} for a title, so that a lead can be opened as it stands. */
    public static String address(String lang, String title) { return URI.create("https://" + lang + ".wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8).replace("+", "%20")).toString(); }
}
