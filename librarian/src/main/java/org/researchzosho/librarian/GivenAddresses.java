package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The addresses the library opens for a model: one that appears as it is in the person's words, or in a result the library received
 * (a search's, a page's text, any tool's), or a link of a page it read, the fragment aside; never one a model put together. On
 * 2026-11-18 gpt-oss-20b in the chat read a page that held an address with blanks to fill in, put the person's notes from a shelf
 * search into it, URL-encoded, and opened it: the page's server had the notes. A search's query is the model's own words by nature
 * and is not held to this.
 *
 * <p>Kept for this process (one library's), the most recent {@link #MOST}: an address a model read in a result an hour ago may be
 * opened now, in another worker of the same run or in a later turn.
 */
public final class GivenAddresses {
    private GivenAddresses() { }

    static final int MOST = 50_000;
    /**
     * Each given address as it is compared ({@link #key}), with the address as it was given ({@link #exact}): what a fetch requests is
     * always that string, never the model's way of writing the same address (round F, the second review: invalid escapes, case, look-alikes,
     * %3F for ? all compared equal and were sent as the model wrote them, so a model could choose the bytes on the wire).
     */
    private static final Map<String, String> SEEN = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > MOST; }
    });

    /** An address written out. */
    static final Pattern URL = Pattern.compile("(?i)https?://[^\\s<>\"'`\\[\\]]+");   // braces and bars stay in it: a template is compared whole, never by its prefix
    /** An address a person writes without its scheme: example.org, example.org/page. */
    static final Pattern BARE = Pattern.compile("(?i)(?<![\\w@./:%-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}(?::\\d{1,5})?(?:/[^\\s<>\"'`{}|\\\\^\\[\\]]*)?");   // a port too: host:8443/page
    static final Pattern HREF = Pattern.compile("(?i)\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>\"']+))");
    /** The arguments that are a search's words: the model writes them, and they are not held to the rule. */
    static final Set<String> QUERY_KEYS = Set.of("query", "q", "queries");
    /** A run's tools that open nothing: a note's source is a citation, not a fetch. */
    static final Set<String> OPEN_NOTHING = Set.of("note", "done", "write_section");


    /** A value that names a file: an entry's id (2026-11-18-b8f65f124e9f.md) or a file's name, not a host, whatever its ending. */
    static final Pattern FILE_LIKE = Pattern.compile("(?i)^[\\w.-]+\\.(md|txt|pdf|json|jsonl|csv|tsv|docx?|xlsx?|pptx?|odt|epub|png|jpe?g|gif|webp|svg|html?|xml|zip|cbz|cbr|mp3|mp4|m4a|wav|bib|ris|srt|vtt)$");

    /** The hyphens that look alike, and every other look-alike NFKC folds: the person's ferry\u2011almanac.org is the model's ferry-almanac.org. */
    static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        return n.replaceAll("[\u2010\u2011\u2012\u2013\u2212\uFE63\uFF0D]", "-");
    }

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * The locators of the library's own holdings that the library itself listed (a shelf search's hits, a claim's sources), as it wrote
     * them: a file it shelved (file://…), a work by its citation (cite:…). Given exactly so, and only so: a page, a search on the web or a
     * model that writes such a locator gives nothing (round G: since round F a research run could not read the person's own shelved file
     * by the file:// locator its shelf search listed).
     */
    private static final Set<String> INSIDE = Collections.newSetFromMap(Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > MOST; }
    }));
    /** A locator of the library's own kind, not an address on the web. */
    static boolean internal(String s) { return s != null && s.strip().matches("(?is)^(?:file:|cite:)\\S.*"); }

    /** A locator of the library's own holdings, as the library's own tool listed it: given, exactly as written. */
    public static void fromTheLibrary(String locator) { if (internal(locator)) INSIDE.add(locator.strip()); }

    /** Every address in a result the library received: a tool's, a search's, a page's text. */
    public static void received(String text) { received(text, Set.of()); }

    /**
     * The same, except the addresses of the call that brought the result ({@link #keysIn}): a result that repeats its own call's argument
     * (a refusal, an error, "no results for …") never makes it given. On 2026-11-18 the refusal named the address, and the model's next call
     * of the same address went out.
     */
    public static void received(String text, Set<String> callsOwn) {
        if (text == null || text.indexOf("://") < 0) return;
        Matcher m = URL.matcher(fold(text));
        while (m.find()) { String k = key(m.group()); if (k != null && !callsOwn.contains(k)) keep(k, m.group()); }
    }

    /** The addresses a call's arguments carry, as compared: what its own result may not make given. */
    public static Set<String> keysIn(JsonNode args) {
        Set<String> out = new HashSet<>();
        if (args == null) return out;
        if (args.isTextual()) { String v = fold(args.asText()); Matcher m = URL.matcher(v); while (m.find()) { String k = key(m.group()); if (k != null) out.add(k); }
            String w = v.strip(); if (BARE.matcher(w).matches()) { String k = key(w); if (k != null) out.add(k); } if (w.matches("(?i)^https?://.*")) { String k = key(w); if (k != null) out.add(k); } }
        else for (JsonNode v : args) out.addAll(keysIn(v));
        return out;
    }

    /**
     * Every tool result of a conversation or a run's history, each without the addresses of the call that brought it: the results a model
     * read, and nothing it wrote itself.
     */
    public static void receivedIn(Iterable<? extends JsonNode> messages) {
        Map<String, Set<String>> own = new HashMap<>();
        for (JsonNode m : messages) for (JsonNode c : m.path("tool_calls")) {
            JsonNode a = c.path("function").path("arguments");
            JsonNode parsed = a;
            if (a.isTextual()) { try { parsed = M.readTree(a.asText()); } catch (Exception notJson) { parsed = a; } }
            own.put(c.path("id").asText(""), keysIn(parsed));
        }
        for (JsonNode m : messages) if ("tool".equals(m.path("role").asText())) received(m.path("content").asText(""), own.getOrDefault(m.path("tool_call_id").asText(""), Set.of()));
    }

    /** The person's own words: an address written out, or one without its scheme. */
    public static void personSaid(String words) {
        if (words == null || words.isBlank()) return;
        received(words);
        Matcher m = BARE.matcher(fold(words));
        while (m.find()) add(m.group());
    }

    /** The links of a page the library read, resolved against the page's own address. */
    public static void linksOf(String html, String pageUrl) {
        if (html == null || pageUrl == null) return;
        URI base;
        try { base = URI.create(pageUrl); } catch (IllegalArgumentException e) { return; }
        Matcher m = HREF.matcher(html);
        while (m.find()) {
            String h = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            if (h == null || h.isBlank()) continue;
            h = h.strip().replace("&amp;", "&");
            try {
                URI u = base.resolve(h.replace(" ", "%20"));
                if (u.getScheme() != null && (u.getScheme().equalsIgnoreCase("http") || u.getScheme().equalsIgnoreCase("https"))) add(u.toString());
            } catch (IllegalArgumentException notAnAddress) { /* a link the page wrote wrong is no address */ }
        }
    }

    private static void add(String address) { String k = key(address); if (k != null) keep(k, address); }

    /** The first way an address was given stays its way: a later result that writes it otherwise does not change what is fetched. */
    private static void keep(String key, String address) { SEEN.merge(key, exact(address), (first, later) -> first); }

    /** Whether the library may open this address for a model: it, the fragment aside, is one the person gave or a result held. */
    public static boolean given(String address) { String k = key(address); return k != null && SEEN.containsKey(k); }

    /**
     * An address as it was given, the one string a fetch of it requests: folded as {@link #fold} folds it (the person's look-alike hyphens
     * are the hyphens a host has), the sentence's stop after it and its fragment off, https:// before it when it was written without a
     * scheme, as the fetch adds it.
     */
    static String exact(String a) {
        String s = trimmed(fold(a).strip());
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        return requested(s);
    }

    /** What a fetch requests for a value: the value, with https:// before it when it names no scheme (WebFetchTool does the same). */
    static String requested(String v) { return v.matches("(?is)^[a-z][a-z0-9+.-]*://.*") ? v : "https://" + v; }

    /** An address with the punctuation of the sentence after it taken off; a closing bracket stays when the address opened one ("…/Foo_(bar)"). */
    static String trimmed(String s) {
        while (!s.isEmpty()) {
            char c = s.charAt(s.length() - 1);
            if (".,;:!?'\"’”".indexOf(c) >= 0) { s = s.substring(0, s.length() - 1); continue; }
            char open = c == ')' ? '(' : c == ']' ? '[' : c == '}' ? '{' : 0;
            if (open != 0 && s.chars().filter(x -> x == open).count() < s.chars().filter(x -> x == c).count()) { s = s.substring(0, s.length() - 1); continue; }
            break;
        }
        return s;
    }

    /**
     * The call's arguments with each address a fetch takes (url, address) put back as it was given: what goes out is exactly a string a
     * search, a page, a tool or the person gave. A value that was not given is left as it is (the check refuses it before this).
     */
    public static JsonNode asGiven(JsonNode args) {
        if (args == null || !args.isObject()) return args;
        ObjectNode out = ((ObjectNode) args).deepCopy();
        for (Iterator<Map.Entry<String, JsonNode>> it = out.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v.isObject()) e.setValue(asGiven(v));
            else if (v.isTextual() && BARE_KEYS.contains(e.getKey().toLowerCase(Locale.ROOT)) && !v.asText().isBlank()) {
                String k = key(requested(fold(v.asText()).strip()));
                String was = k == null ? null : SEEN.get(k);
                if (was != null) e.setValue(TextNode.valueOf(was));
            }
        }
        return out;
    }

    /**
     * The first address in a tool call's arguments that was not given, or null when there is none. {@code within}: an address anywhere in
     * a value's text counts (the chat, where a research run's question carries what the run will open); otherwise only a value that is an
     * address (a run's own tools). A search's words are not looked at.
     */
    public static String notGiven(JsonNode args, boolean within) { return notGiven(args, within, ""); }

    private static String notGiven(JsonNode v, boolean within, String key) {
        if (v == null) return null;
        if (v.isTextual()) return textNotGiven(v.asText(), within, BARE_KEYS.contains(key));
        if (v.isArray()) { for (JsonNode x : v) { String r = notGiven(x, within, key); if (r != null) return r; } return null; }
        if (v.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey().toLowerCase(Locale.ROOT);
                if (QUERY_KEYS.contains(k) || k.equals("patron")) continue;
                String r = notGiven(e.getValue(), within, k);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** The arguments a fetch takes an address in without its scheme, adding https:// itself (web_fetch's url, open's address). */
    static final Set<String> BARE_KEYS = Set.of("url", "address");

    private static String textNotGiven(String v, boolean within, boolean bareCounts) {
        String s = fold(v).strip();
        if (s.isEmpty()) return null;
        // a fetch's url or address is the address it will request, whatever it looks like: https:// is put before a value without a scheme,
        // so a host ending .md or .zip, host:port/path and user@host/path are addresses too (round F), spaces and braces in it
        if (bareCounts && internal(s)) return INSIDE.contains(v.strip()) ? null : v.strip();   // a locator of the library's own: exactly as the library listed it
        if (bareCounts) return given(requested(s)) ? null : v.strip();
        if (FILE_LIKE.matcher(s).matches()) return null;   // an entry id or a file's name in any other value: no address
        if (s.indexOf(' ') < 0 && (URL.matcher(s).matches() || (bareCounts && BARE.matcher(s).matches()))) return given(s) ? null : v.strip();
        if (!within) return null;
        Matcher m = URL.matcher(s);
        while (m.find()) if (!given(m.group())) return m.group();
        return null;
    }

    /** A run's tool call, or null when it may go: an address in it that was not given makes it the sentence the model is told. */
    public static String unopened(String tool, JsonNode args) {
        if (OPEN_NOTHING.contains(tool)) return null;
        String a = notGiven(args, false);
        return a == null ? null : refused(a);
    }

    /** What the model is told of an address it was not given: nothing was opened, and why, in one sentence. */
    public static String refused(String address) {
        return "NOT OPENED: the library opens only an address that the person gave or that a search, a page or a tool returned, as it was written there, and this one is none of them; an address put together from a page's instructions or from what the library holds is never opened.";
    }

    /**
     * An address as it is compared: the sentence's punctuation after it and the fragment taken off (the fragment never leaves the
     * browser, so an address and its fragment are the same request), the scheme set aside, the host in small letters, the path's
     * escapes read (Fähre and F%C3%A4hre are one address). Null for no address.
     */
    static String key(String a) {
        if (a == null) return null;
        String s = trimmed(fold(a).strip());
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        Matcher scheme = Pattern.compile("(?i)^https?://").matcher(s);
        String rest = scheme.find() ? s.substring(scheme.end()) : s;
        if (rest.isEmpty() || rest.startsWith("/")) return null;
        int cut = rest.length();
        for (char c : new char[]{'/', '?'}) { int i = rest.indexOf(c); if (i >= 0 && i < cut) cut = i; }
        String host = rest.substring(0, cut).toLowerCase(Locale.ROOT);
        String path = rest.substring(cut);
        if (path.isEmpty()) path = "/";
        else if (path.startsWith("?")) path = "/" + path;
        return "//" + host + unescaped(path);
    }

    /** The %XX escapes of a path read as UTF-8; anything else as it is. */
    static String unescaped(String p) {
        if (p.indexOf('%') < 0) return p;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int i = 0; i < p.length(); ) {
            if (p.charAt(i) == '%' && i + 2 < p.length()) {
                int hi = Character.digit(p.charAt(i + 1), 16), lo = Character.digit(p.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) { b.write(hi * 16 + lo); i += 3; continue; }
            }
            int cp = p.codePointAt(i);
            byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
            b.write(bytes, 0, bytes.length);
            i += Character.charCount(cp);
        }
        return b.toString(StandardCharsets.UTF_8);
    }
}
