package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.researchzosho.tools.Addresses;
import org.researchzosho.tools.Outbound;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
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

    /**
     * Every spelling an address was given in ({@link #exact}, or whole for a link and for where a server sent a request on). A request
     * for an address is made in the spelling it was written in when that is one of these, and in the first one given otherwise
     * ({@link #asRequested}): an address given both with and without a slash after it, or with http and with https, is asked for either way.
     */
    private static final Set<String> EXACT = Collections.newSetFromMap(Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 2 * MOST; }
    }));
    /**
     * The sites of the given addresses, each with the scheme it was first given with: a site's own front page and robots file are
     * its to open ({@link GivenIdentifiers}). A site is a host at a port ({@link #site}): the port the address named, and none
     * when it named the one its scheme has anyway. Kept as the host was written, with or without "www." before it: a front page
     * is asked on a spelling that was given ({@link #siteGiven}).
     */
    private static final Map<String, String> HOSTS = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > MOST; }
    });

    /**
     * An address written out. Braces and bars stay in it: a template is compared whole, never by its prefix. A host written as an IPv6
     * address stands in square brackets, which end an address everywhere else (until 0.8.1 they ended it there too, so such an
     * address could never be given).
     */
    static final Pattern URL = Pattern.compile("(?i)https?://(?:\\[[0-9a-f:.]+\\](?::\\d{1,5})?(?:[/?#][^\\s<>\"'`\\[\\]]*)?|[^\\s<>\"'`\\[\\]]+)");
    /** An address a person writes without its scheme: example.org, example.org/page. */
    static final Pattern BARE = Pattern.compile("(?i)(?<![\\w@./:%-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24}(?::\\d{1,5})?(?:/[^\\s<>\"'`{}|\\\\^\\[\\]]*)?");   // a port too: host:8443/page
    /**
     * The most names joined by dots that are read as a host's (a host has at most 127). {@link #BARE} reads such names one level
     * deeper for each, and some 2,000 in a row used a thread's stack up (a text that repeats "0.0.0." long enough, in a person's
     * words or a call's arguments); a longer run is no host, and is not handed to the pattern ({@link #bareIn}, {@link #bareWhole}).
     */
    static final int MOST_NAMES = 128;
    /** Where an address without its scheme can begin: a letter or a digit that {@link #BARE} would begin at, with what may be a host after it. */
    private static final Pattern HOST_RUN = Pattern.compile("(?<![\\w@./:%-])[A-Za-z0-9][A-Za-z0-9.-]*");

    private static int dots(String s, int from, int to) { int n = 0; for (int i = from; i < to; i++) if (s.charAt(i) == '.') n++; return n; }

    /** Every address without its scheme in a text, as {@link #BARE} finds them one after the other. */
    static List<String> bareIn(String text) {
        List<String> out = new ArrayList<>();
        Matcher run = HOST_RUN.matcher(text), bare = BARE.matcher(text).useTransparentBounds(true);
        for (int from = 0; from <= text.length() && run.find(from); ) {
            // an address can begin only where such a run begins: inside it every character stands after one that cannot stand before an address
            if (dots(text, run.start(), run.end()) <= MOST_NAMES && bare.region(run.start(), text.length()).lookingAt()) { out.add(bare.group()); from = Math.max(bare.end(), run.start() + 1); }
            else from = Math.max(run.end(), run.start() + 1);
        }
        return out;
    }

    /** Whether a whole value is an address without its scheme. */
    static boolean bareWhole(String s) {
        int host = 0;
        while (host < s.length() && (Character.isLetterOrDigit(s.charAt(host)) || s.charAt(host) == '.' || s.charAt(host) == '-')) host++;
        return dots(s, 0, host) <= MOST_NAMES && BARE.matcher(s).matches();
    }

    /**
     * A machine a person writes by its numbers, without a scheme: 192.0.2.20:8096/web, 198.51.100.5/status. With a port or a path after
     * it, so that a number with dots in a sentence (a version, a section) is not one (0.8.1: such an address could not be given, a
     * host having to end in letters; whether the library may ask a machine of the house stays the fetcher's own rule).
     */
    static final Pattern NUMBERED = Pattern.compile("(?<![\\w@./:%-])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5}(?:/[^\\s<>\"'`{}|\\\\^\\[\\]]*)?|/[^\\s<>\"'`{}|\\\\^\\[\\]]*)");
    static final Pattern HREF = Pattern.compile("(?i)\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>\"']+))");
    /** The arguments that are a search's words: the model writes them, and they are not held to the rule. */
    static final Set<String> QUERY_KEYS = Set.of("query", "q", "queries");
    /** A run's tools that open nothing: a note's source is a citation, not a fetch. */
    static final Set<String> OPEN_NOTHING = Set.of("note", "done", "write_section", "revise");


    /** A value that names a file: an entry's id (2026-11-18-b8f65f124e9f.md) or a file's name, not a host, whatever its ending. */
    static final Pattern FILE_LIKE = Pattern.compile("(?i)^[\\w.-]+\\.(md|txt|pdf|json|jsonl|csv|tsv|docx?|xlsx?|pptx?|odt|epub|png|jpe?g|gif|webp|svg|html?|xml|zip|cbz|cbr|mp3|mp4|m4a|wav|bib|ris|srt|vtt)$");

    /** The hyphens that look alike, and every other look-alike NFKC folds: the person's ferry\u2011almanac.org is the model's ferry-almanac.org. */
    public static String fold(String s) {
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
    /**
     * A locator of the library's own kind, not an address on the web: a shelved file (file:), a work by its citation (cite:), and what the
     * library keeps under a scheme of its own (db://, mcp://, list://, researchzosho://…). Never http or https, and never a host with a
     * port (host:8443/page has no // after its colon).
     */
    public static boolean internal(String s) {
        if (s == null) return false;
        String t = s.strip();
        return t.matches("(?is)^(?:file:|cite:)\\S.*") || (t.matches("(?is)^[a-z][a-z0-9+.-]{1,24}://\\S.*") && !t.matches("(?is)^https?://.*"));
    }

    /**
     * An entry of the library by its id, as its own tools read one: a claim (F-0131-…, or its code F-1338), a report (I-0042-…), an article
     * (A-…). An id is no address: it names no host, nothing is requested for it, and it is not held to the rule (0.8.1: since the rule held
     * every {@code url} value, a run could not read a claim or a report by the id its shelf search listed).
     */
    static final Pattern ENTRY_ID = Pattern.compile("^[A-Z]-\\d{1,6}(?:-[A-Za-z0-9-]{1,200})?$");
    public static boolean entryId(String s) { return s != null && ENTRY_ID.matcher(s.strip()).matches(); }

    /** A locator of the library's own holdings, as the library's own tool listed it: given, exactly as written. */
    public static void fromTheLibrary(String locator) { if (internal(locator)) INSIDE.add(locator.strip()); }

    /**
     * What a tool's answer gives, by where the answer came from ({@code Tool.fromOutside}): from outside the
     * library, every address written in it ({@link #received}); from the library's own shelves, only the addresses the library holds a
     * saved copy of ({@link #recorded}).
     */
    public static void gave(boolean outside, LibraryStore store, String text, Set<String> callsOwn) { gave(outside, store, text, new Own(callsOwn, null)); }

    /** The same, with all of the call's own: its addresses and its arguments' words ({@link Own}). */
    public static void gave(boolean outside, LibraryStore store, String text, Own own) {
        if (outside) received(text, own); else recorded(store, text, own.keys());
    }

    /**
     * What a call brought with it, which its own answer never makes given: the addresses its arguments hold, as compared, and the
     * arguments' words. An answer can hold an address only because the call did: the refusal that names it, "no results for …", and
     * also "no archived copy of https://" followed by a word of the query, or a list headed by the things asked for. Whatever the tool
     * put before or around them, an address whose host the arguments name and whose path they spell out came from the model, not from
     * outside ({@link Outbound.Call#wrote}; 0.8.1: an address written without its scheme as a search's words came back with https://
     * before it in a from-outside answer, and was opened).
     */
    public record Own(Set<String> keys, Outbound.Call call) {
        public static Own of(JsonNode args) { return new Own(keysIn(args), Outbound.of(args)); }
        boolean holds(String key, String address) { return keys.contains(key) || (call != null && call.wrote(trimmed(fold(address).strip()))); }
    }

    /**
     * The addresses in an answer made of what the library stores (a report, a claim, a note, a list, a question: text a model may have
     * written): an address counts only when the library holds a copy it saved from that address itself, and it is given as the library
     * recorded it. The record is the saved copy's own first line, written by the fetch (or by the person's own add); no tool writes one
     * for an address the library did not open, so a model cannot make an address count by writing it down (0.8.1: a run wrote an address
     * holding the person's private text into its report, a later chat turn read the report and opened it).
     */
    public static void recorded(LibraryStore store, String text, Set<String> callsOwn) {
        if (store == null || text == null || text.indexOf("://") < 0) return;
        Map<String, String> held = null;
        // a result written as JSON carries a line's end as two characters (\n): they end an address here as the line's end does
        Matcher m = URL.matcher(fold(text).replaceAll("\\\\[nrt]", " "));
        while (m.find()) {
            String k = key(m.group());
            if (k == null || callsOwn.contains(k)) continue;
            if (held == null) held = RawCapture.locators(store);
            String as = held.get(k);
            if (as != null) keep(k, as);
        }
    }

    /** Every address in a result the library received: a tool's, a search's, a page's text. */
    public static void received(String text) { received(text, Set.of()); }

    /** The addresses of a text alone, its words not kept as an outside text's are: for the person's own words, which are kept as theirs. */
    private static void receivedAddresses(String text) {
        if (text == null || text.indexOf("://") < 0) return;
        Matcher m = URL.matcher(fold(text));
        while (m.find()) { String k = key(m.group()); if (k != null) keep(k, m.group()); }
    }

    /**
     * The same, except the addresses of the call that brought the result ({@link #keysIn}): a result that repeats its own call's argument
     * (a refusal, an error, "no results for …") never makes it given. On 2026-11-18 the refusal named the address, and the model's next call
     * of the same address went out.
     */
    public static void received(String text, Set<String> callsOwn) { received(text, new Own(callsOwn, null)); }

    /** The same, without anything the call itself held ({@link Own}). */
    public static void received(String text, Own own) {
        GivenWords.outside(text, own.call() == null ? null : own.call().said());   // its words, for the one tool whose words go to hosts it finds
        if (text == null || text.indexOf("://") < 0) return;
        Matcher m = URL.matcher(fold(text));
        while (m.find()) { String k = key(m.group()); if (k != null && !own.holds(k, m.group())) keep(k, m.group()); }
    }

    /** The addresses a call's arguments carry, as compared: what its own result may not make given. */
    public static Set<String> keysIn(JsonNode args) {
        Set<String> out = new HashSet<>();
        if (args == null) return out;
        if (args.isTextual()) { String v = fold(args.asText()); Matcher m = URL.matcher(v); while (m.find()) { String k = key(m.group()); if (k != null) out.add(k); }
            String w = v.strip(); if (bareWhole(w) || NUMBERED.matcher(w).matches() || NUMBERS_ALONE.matcher(w).matches()) { String k = key(w); if (k != null) out.add(k); } if (w.matches("(?i)^https?://.*")) { String k = key(w); if (k != null) out.add(k); } }
        else for (JsonNode v : args) out.addAll(keysIn(v));
        return out;
    }

    /**
     * What a tool's message in a saved conversation carries beside its words, written when the answer was (0.8.1): whether the answer
     * came from outside and so gave its addresses ({@link #OUTSIDE}), and which call of the model's message before it was its own, by
     * its place among that message's calls ({@link #CALL}).
     */
    static final String OUTSIDE = "outside", CALL = "call";

    /**
     * Every tool result of a conversation kept on disk, when it is opened again: each gives what it gave when it was written, without
     * the addresses of the call that brought it. What an answer gave is read from the record on its own message ({@link #OUTSIDE},
     * {@link #CALL}), never worked out again from the calls' ids: a model server's client gives one id to many calls (every call
     * recovered from a reply's text, the calls of every stream that carried none), and matched by id an answer of the shelves, which
     * holds what models wrote, was taken for the answer of a later call that opened a page (0.8.1: two addresses a model wrote were
     * opened on the next turn of a conversation loaded again).
     *
     * <p>A conversation saved before the record existed is read by the order the library wrote it in: the model's message with its
     * calls, then one answer for each call, in the calls' order. An answer whose place and id do not both agree with its call gives
     * nothing but what the library recorded itself ({@link #recorded}); {@code outside}: the tool's name and its arguments → whether
     * its answer came from outside, for those conversations.
     */
    public static void receivedIn(Iterable<? extends JsonNode> messages, LibraryStore store, BiPredicate<String, JsonNode> outside) {
        JsonNode calls = null;   // the calls of the model's last message that made any
        int next = 0;            // a conversation without the record: the place of the next answer among them
        for (JsonNode m : messages) {
            if (!"tool".equals(m.path("role").asText())) {
                if (m.path("tool_calls").isArray() && !m.path("tool_calls").isEmpty()) { calls = m.path("tool_calls"); next = 0; }
                continue;
            }
            String said = m.path("content").asText("");
            boolean recorded = m.has(OUTSIDE) && m.has(CALL);
            int place = recorded ? m.path(CALL).asInt(-1) : next++;
            JsonNode call = calls != null && place >= 0 && place < calls.size() ? calls.get(place) : null;
            String name = call == null ? "" : call.path("function").path("name").asText("");
            // the answer's own call, for certain: by the place the library wrote down; without that, by its place in the order and its id both
            if (call != null && m.has("name") && !m.path("name").asText("").equals(name)) call = null;
            if (call != null && !recorded && !call.path("id").asText("").equals(m.path("tool_call_id").asText("-"))) call = null;
            if (call == null) { calls = recorded ? calls : null; gave(false, store, said, new Own(Set.of(), null)); continue; }   // not certain: nothing but what the library recorded; the answers after it in a conversation without the record have lost their place too
            JsonNode a = call.path("function").path("arguments");
            JsonNode parsed = a;
            if (a.isTextual()) { try { parsed = M.readTree(a.asText()); } catch (Exception notJson) { parsed = a; } }
            boolean from;
            if (recorded) from = m.path(OUTSIDE).asBoolean(false);
            else from = !said.startsWith("NOT ") && !said.startsWith("ERROR") && outside.test(name, parsed);   // a call the library did not run (refused, held for the person's yes, not on the table) answered in the library's own words
            gave(from, store, said, Own.of(parsed));
        }
    }

    /**
     * A machine by its four numbers and nothing else, as it stands in the person's words: "open 192.168.1.1". Not a part of a longer
     * run of numbers and dots (11.1.2.3.4 is no address, with its fifth part before or after), nor of a word, nor numbers with a
     * port or a path after them, which {@link #NUMBERED} reads. A version written with four numbers (1.2.3.4) is taken as one as
     * well, which costs nothing: nothing is asked unless a call names exactly those numbers, and they are the person's.
     */
    static final Pattern NUMBERS_IN_WORDS = Pattern.compile("(?<![\\w@./:%-])(?:\\d{1,3}\\.){3}\\d{1,3}(?![\\w@%-]|:\\d|/|\\.\\d)");

    /**
     * The person's own words: an address written out, or one without its scheme: a name (example.org/page), a machine by its
     * numbers with a port or a path, and a machine's numbers alone (decided on 2026-11-21: "open 192.168.1.1" opens
     * what the person typed). Only here: a text from outside and what the library stored give an address only when it is written
     * with its scheme.
     */
    public static void personSaid(String words) {
        if (words == null || words.isBlank()) return;
        GivenWords.person(words);
        receivedAddresses(words);
        String said = fold(words);
        for (String bare : bareIn(said)) add(bare);
        Matcher m = NUMBERED.matcher(said);   // a machine of the house, typed by its numbers and port
        while (m.find()) addNumbers(m.group());
        m = NUMBERS_IN_WORDS.matcher(said);
        while (m.find()) if (Addresses.numeric(m.group()) != null) addNumbers(m.group());   // four numbers that are an address: each at most 255
    }

    /**
     * A machine the person wrote by its numbers with no scheme before them. They named none, so the address is theirs by http as by
     * https: a machine of the house (a router, a printer, a media server) answers by plain http more often than not, and a request
     * made only by https would reach none of them. Written with no scheme in a call it is asked by https first, as every address
     * without one is; a call that writes http is asked by http. With nothing after the numbers and the port, a slash after them is
     * the same address.
     */
    private static void addNumbers(String written) {
        String k = key(written);
        if (k == null) return;
        String rest = exact(written).substring("https://".length());
        for (String scheme : new String[]{"https://", "http://"}) {
            kept(k, scheme + rest);
            if (rest.indexOf('/') < 0) kept(k, scheme + rest + "/");
        }
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
                if (u.getScheme() != null && (u.getScheme().equalsIgnoreCase("http") || u.getScheme().equalsIgnoreCase("https"))) addWhole(u.toString());
            } catch (IllegalArgumentException notAnAddress) { /* a link the page wrote wrong is no address */ }
        }
    }

    private static void add(String address) { String k = key(address); if (k != null) keep(k, address); }

    /**
     * An address that is a value of its own and not a piece of a sentence (a page's link, where a server sent a request on, an
     * address a feed names): given whole. A full stop or a bracket at its end is part of it, where in a sentence it is the sentence's.
     */
    private static void addWhole(String address) { String k = key(address); if (k != null) kept(k, whole(address)); }

    /** An address a server sent a request on to, or that an answer named as a value of its own: given, whole. */
    public static void receivedWhole(String address) { if (address != null && address.strip().matches("(?is)^https?://\\S+")) addWhole(address); }

    /** The first way an address was given stays its way: a later result that writes it otherwise does not change what is fetched. */
    private static void keep(String key, String address) { kept(key, exact(address)); }

    private static void kept(String key, String spelling) {
        SEEN.merge(key, spelling, (first, later) -> first);
        EXACT.add(spelling);
        Matcher m = SCHEME_AND_HOST.matcher(spelling);
        if (m.find()) { String scheme = m.group(1).toLowerCase(Locale.ROOT); HOSTS.putIfAbsent(site(scheme, m.group(2).toLowerCase(Locale.ROOT)), scheme); }
    }

    /**
     * A site as it is kept: the host, and its port when the address named another than its scheme has anyway. The scheme's own port
     * written out is the same door as none written ({@code https://example.org:443} is {@code example.org}); any other port is
     * another program's door on that machine, and a site of its own (the last reading of 0.8.1: a site given with {@code :443}
     * written out was kept under that spelling, and its front page asked for without the port was refused).
     */
    private static String site(String scheme, String hostAndPort) {
        String own = "http".equals(scheme) ? ":80" : ":443";   // an address with no scheme is asked with https, as the fetch asks it
        if (!hostAndPort.endsWith(own)) return hostAndPort;
        String host = hostAndPort.substring(0, hostAndPort.length() - own.length());
        return !host.isEmpty() && (host.endsWith("]") || host.indexOf(':') < 0) ? host : hostAndPort;   // a colon left in it: no port was written (fe80::443), or a name and a password before the host
    }

    /**
     * A host with "www." before it is the same given address as the host without it, both ways: given without and asked with, given
     * with and asked without (the acceptance pass of 0.8.1: the person typed nps.gov/stli, the model asked for the same page with
     * "https://www." before it, and the library refused an address the person gave). The model chooses nothing by adding or
     * dropping "www.", because the request is made in the given spelling ({@link #asRequested}); where the site sends it on from
     * there is the site's own redirect, followed as any other. Only that one label: m., www2., wwww. and en. are other hosts, and
     * "www." before a host of a single label or one written as numbers (www.localhost, www.127.0.0.1) is compared as written.
     * {@code hostAndPort}: in small letters, a user before it or none, a port after it or none.
     */
    static String withoutWww(String hostAndPort) {
        int at = hostAndPort.lastIndexOf('@') + 1;
        if (!hostAndPort.startsWith("www.", at)) return hostAndPort;
        String rest = hostAndPort.substring(at + 4);
        int colon = rest.lastIndexOf(':');
        String name = colon < 0 ? rest : rest.substring(0, colon);
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || name.substring(dot + 1).matches("\\d*|0x[0-9a-f]*")) return hostAndPort;   // a single label, or a host written as numbers
        return hostAndPort.substring(0, at) + rest;
    }

    private static final Pattern SCHEME_AND_HOST = Pattern.compile("(?i)^(https?)://(?:[^/?#@]*@)?([^/?#]+)");

    /**
     * A site as it was given, with the scheme its first given address had ({@code https://example.org}), or null when no address on
     * it was given; {@code site} as {@link #site} keeps it: a host, with its port when that is not the scheme's own. The site with
     * and without "www." before its host is the same site ({@link #withoutWww}): when only the other spelling was given, the other
     * spelling is what is returned, and a front page or a robots file is asked on it.
     */
    static String siteGiven(String site) {
        if (site == null) return null;
        String s = site.strip().toLowerCase(Locale.ROOT);
        String scheme = HOSTS.get(s);
        if (scheme != null) return scheme + "://" + s;
        String bare = withoutWww(s), twin = !bare.equals(s) ? bare : withoutWww("www." + s).equals(s) ? "www." + s : null;
        scheme = twin == null ? null : HOSTS.get(twin);
        return scheme == null ? null : scheme + "://" + twin;
    }

    /** An address the program itself put together from a name or a number that was given ({@link GivenIdentifiers}): given from here on, as built. */
    static void built(String address) { String k = key(address); if (k != null) kept(k, whole(address)); }

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

    /** An address as a value of its own: folded, the fragment off, https:// before it when it was written without a scheme; nothing else taken off. */
    static String whole(String a) {
        String s = fold(a).strip();
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        return requested(s);
    }

    /**
     * The spelling a request for this address is made in, or null when the address was not given: its own when that is a spelling it
     * was given in, else the first one it was given in. This is what goes on the wire for a given address, whichever tool built the
     * request and wherever in a call's arguments the address stood ({@code Outbound}): never the model's way of writing it, and never
     * with http where it was given with https.
     */
    public static String asRequested(String address) {
        String k = key(address);
        String first = k == null ? null : SEEN.get(k);
        if (first == null) return null;
        String w = whole(address);
        if (EXACT.contains(w)) return w;
        String e = exact(address);
        return EXACT.contains(e) ? e : first;
    }

    /** What a fetch requests for a value: the value, with https:// before it when it names no scheme (WebFetchTool does the same). */
    static String requested(String v) { return v.matches("(?is)^[a-z][a-z0-9+.-]*://.*") ? v : "https://" + v; }

    /** An address with the punctuation of the sentence after it taken off; a closing bracket stays when the address opened one ("…/Foo_(bar)"). */
    static String trimmed(String s) {
        while (!s.isEmpty()) {
            char c = s.charAt(s.length() - 1);
            if (".,;:!?'\"’”>".indexOf(c) >= 0) { s = s.substring(0, s.length() - 1); continue; }   // > closes an address written between < and >, and is in none
            char open = c == ')' ? '(' : c == ']' ? '[' : c == '}' ? '{' : 0;
            if (open != 0 && s.chars().filter(x -> x == open).count() < s.chars().filter(x -> x == c).count()) { s = s.substring(0, s.length() - 1); continue; }
            break;
        }
        return s;
    }

    /**
     * The call's arguments with every address in them put back as it was given: what a tool is handed is a string a search, a page,
     * a tool or the person gave, wherever in the arguments it stands (a value, a list, an object in a list, a line among lines). A
     * value that was not given is left as it is (the check refuses it before this). This is the early half of the rule; the request
     * itself is made in the given spelling whatever a tool was handed ({@code Outbound}).
     */
    public static JsonNode asGiven(JsonNode args) { return asGiven(args, Set.of()); }

    /** The same; {@code addresses}: the arguments this tool takes an address in beside the ones every tool does ({@link #addressKey}). */
    public static JsonNode asGiven(JsonNode args, Set<String> addresses) {
        if (args == null || !args.isObject()) return args;
        return putBack(args.deepCopy(), "", addresses == null ? Set.of() : addresses);
    }

    private static JsonNode putBack(JsonNode v, String key, Set<String> addresses) {
        if (v.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String name = e.getKey().toLowerCase(Locale.ROOT);
                if (QUERY_KEYS.contains(name) && !addressKey(name, addresses)) continue;   // a search's words go as the model wrote them
                e.setValue(putBack(e.getValue(), name, addresses));
            }
            return v;
        }
        if (v.isArray()) { ArrayNode list = (ArrayNode) v; for (int i = 0; i < list.size(); i++) list.set(i, putBack(list.get(i), key, addresses)); return list; }
        if (!v.isTextual()) return v;
        String was = asGiven(v.asText(), heldAsAddress(key, v.asText(), addresses));
        return was == null ? v : TextNode.valueOf(was);
    }

    private static final Pattern PIECE = Pattern.compile("\\S+");

    /** The spelling an address is requested in: as it was given; or, for an address written for a name or a number that was given, as this program builds it ({@link GivenIdentifiers}). */
    private static String asRequestedOrBuilt(String address) {
        String as = asRequested(address);
        return as != null ? as : GivenIdentifiers.built(address);
    }

    /**
     * A value with each address in it as it was given; null when nothing in it is a given address written another way ({@code bare}:
     * the value is an address whatever it looks like, as a fetch's url is, and counts without its scheme).
     */
    private static String asGiven(String value, boolean bare) {
        String s = fold(value).strip();
        if (s.isEmpty()) return null;
        if (!s.matches("(?s).*\\s.*")) {
            if (!bare && !s.matches("(?i)^https?://.*")) return null;
            return asRequestedOrBuilt(requested(s));
        }
        if (bare) return asRequested(requested(s));   // an address with a space in it, compared whole
        // several pieces (lines pasted, a sentence): each piece that is an address, as it was given
        Matcher m = PIECE.matcher(value);
        StringBuilder out = new StringBuilder();
        int at = 0;
        boolean changed = false;
        while (m.find()) {
            String piece = fold(m.group());
            String as = piece.matches("(?i)^https?://.*") ? asRequestedOrBuilt(piece) : null;
            out.append(value, at, m.start()).append(as == null ? m.group() : as);
            if (as != null && !as.equals(m.group())) changed = true;
            at = m.end();
        }
        return changed ? out.append(value, at, value.length()).toString() : null;
    }

    /**
     * The first address in a tool call's arguments that was not given, or null when there is none. {@code within}: an address anywhere in
     * a value's text counts (the chat, where a research run's question carries what the run will open); otherwise only a value that is an
     * address (a run's own tools). A search's words are not looked at.
     */
    public static String notGiven(JsonNode args, boolean within) { return notGiven(args, within, false); }

    /**
     * The same; {@code queries}: a search's words are looked at too. A call that would store its arguments or start work from them (a
     * watch kept and run every night, a source added) is no search, whatever its argument is called: an address in it is asked for
     * later, with nobody looking (0.8.1: {@code query: "podcast <address>"} of a watch is a feed fetched each night).
     */
    public static String notGiven(JsonNode args, boolean within, boolean queries) { return notGiven(args, within, "", queries, Set.of()); }

    /**
     * The tools whose {@code address} is a home's street address, not an address of the web: it is words, read as any other argument's.
     * The same lookup by each of its names: the instrument, and the two forms a program is offered ({@code library_home} with op=records).
     */
    static final Set<String> STREET_ADDRESS = Set.of("records", "library_records");

    /** Whether a call's {@code address} is a street address ({@link #STREET_ADDRESS}). */
    static boolean streetAddress(String tool, JsonNode args) {
        return STREET_ADDRESS.contains(tool) || ("library_home".equals(tool) && args != null && "records".equalsIgnoreCase(args.path("op").asText("").strip()));
    }

    /**
     * The same, for a named tool. Since round F every {@code address} was read as what a fetch requests, so the records of a home, asked
     * for by its street address, were refused every time: no street address is ever given as a web address is (0.8.1). The parts of a
     * street address that go into a request are checked where the request is made ({@link Outbound}).
     */
    public static String notGiven(String tool, JsonNode args, boolean within, boolean queries) { return notGiven(tool, args, within, queries, Set.of()); }

    /**
     * The same; {@code addresses}: the arguments this tool says it takes an address in ({@code Tool.addressArguments}), beside the ones
     * every tool does ({@link #addressKey}). Such an argument is held to the rule a fetch's url is held to, with its scheme or without,
     * alone or in a list, whatever it is called: the archive search's {@code query} for a page's saved copy, a feed's address (0.8.1:
     * an address nobody gave went to the web archives through {@code query}, which was read as a search's words).
     */
    public static String notGiven(String tool, JsonNode args, boolean within, boolean queries, Set<String> addresses) {
        Set<String> declared = addresses == null ? Set.of() : addresses;
        if (streetAddress(tool, args) && args != null && args.isObject() && args.has("address")) {
            // the street goes to the data portals the lookup finds for its place: it has to be one the person gave or a page stated
            String made = GivenWords.streetNotGiven(args.path("address").asText(""));
            if (made != null) return made;
            ObjectNode a = ((ObjectNode) args).deepCopy();
            a.set("street_address", a.remove("address"));
            return notGiven(a, within, "", queries, declared);
        }
        if (args != null && args.isObject() && args.has("address") && (WORDS_UNLESS_WRITTEN_AS_ONE.contains(tool) || ofAKeepersServer(tool))) {
            // an address of the web only when it is written as one: with its scheme (read as every value is), or, for a keeper's server,
            // as a host and a path (example.org/page), which a reader of pages would put https:// before
            String v = args.path("address").asText("");
            String s = fold(v).strip();
            if (ofAKeepersServer(tool) && webShaped(s) && !meant(requested(s))) return v.strip();
            ObjectNode a = ((ObjectNode) args).deepCopy();
            a.set("address_in_words", a.remove("address"));
            return notGiven(a, within, "", queries, declared);
        }
        return notGiven(args, within, "", queries, declared);
    }

    /**
     * The tools whose {@code address} is an address of the web only when it is written as one, with http:// or https://: a source the
     * library is told of is "the source's address, or the words of a search to run again", and the words are a search's words. Until
     * 0.8.1 the words were read as an address nobody gave and a saved search could not be added from the chat.
     */
    static final Set<String> WORDS_UNLESS_WRITTEN_AS_ONE = Set.of("library_sources");

    /**
     * A tool of a keeper's own MCP server, by its name ({@code server.tool}). What its {@code address} means is the server's: a street
     * for a map service, a person's mail address, a page's address for a reader. It is held to the rule when it is written as an
     * address of the web (with its scheme, or as a host and a path); {@code url}, {@code urls} and the other names that say
     * address are held whatever they look like, as in every tool.
     */
    static boolean ofAKeepersServer(String tool) { return tool != null && tool.indexOf('.') > 0; }

    private static String notGiven(JsonNode v, boolean within, String key, boolean queries, Set<String> addresses) {
        if (v == null) return null;
        if (v.isTextual()) return textNotGiven(v.asText(), within, heldAsAddress(key, v.asText(), addresses));
        if (v.isArray()) { for (JsonNode x : v) { String r = notGiven(x, within, key, queries, addresses); if (r != null) return r; } return null; }
        if (v.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                String k = e.getKey().toLowerCase(Locale.ROOT);
                if ((QUERY_KEYS.contains(k) && !queries && !addressKey(k, addresses)) || k.equals("patron")) continue;
                String r = notGiven(e.getValue(), within, k, queries, addresses);
                if (r != null) return r;
            }
        }
        return null;
    }

    /**
     * The arguments that are an address in every tool that has them: a fetch's {@code url} and {@code address}, a reader's list of
     * {@code urls}, a show's {@code feed}. The value is the address that will be requested, whatever it looks like; https:// is put
     * before one without a scheme, by this program's fetcher and by a service that reads pages for it.
     */
    static final Set<String> BARE_KEYS = Set.of("url", "address", "urls", "feed");

    /**
     * An argument whose name says address in a tool this program does not know (a keeper's MCP server's): uri, link, href, and a
     * name that ends in url, uri or link (page_url, start_urls). What such an argument holds is the server's own matter: a page's
     * address for a reader, an id, a locator of the server's own kind (notion://…). So it is held to the rule when its value is
     * written as an address of the web: with http:// or https://, as every value is, or without a scheme as a host and a path
     * ({@link #webShaped}), which a reader of pages puts https:// before. Anything else in it goes to the server as written.
     */
    static final Pattern NAMED_AN_ADDRESS = Pattern.compile("(?:.*_)?(?:url|urls|uri|uris|link|links|href)");

    /** Whether an argument of this name takes an address whatever its value looks like: by its name in every tool, or because the tool whose call this is says so ({@code addresses}). */
    static boolean addressKey(String name, Set<String> addresses) {
        return name != null && !name.isEmpty() && (BARE_KEYS.contains(name) || addresses.contains(name));
    }

    /** A machine by its four numbers and nothing else: 192.0.2.20. */
    static final Pattern NUMBERS_ALONE = Pattern.compile("(?:\\d{1,3}\\.){3}\\d{1,3}");

    /**
     * A value written as an address of the web without its scheme: a host that ends in letters, or a machine by its numbers, a path
     * after it or none, a user before it or none. A machine's numbers with nothing after them are held as an address unless those
     * numbers were given as numbers (they stand in the person's words or in a text from outside, {@link GivenWords#stated}): what
     * such an argument of a keeper's server means is the server's (a page to read, or a machine to say something about), and
     * numbers that somebody wrote go to it as they did before, while numbers a model made up are an address nobody gave (the second
     * reading of 0.8.1: only numbers with a port or a path after them were held, so the same machine written bare went on unread).
     */
    static boolean webShaped(String value) {
        String s = fold(value).strip();
        if (s.isEmpty() || s.matches("(?s).*\\s.*") || FILE_LIKE.matcher(s).matches()) return false;
        int at = s.indexOf('@'), slash = s.indexOf('/');
        if (at > 0 && slash < 0) return false;                  // user@host with nothing after it is a mail address
        if (at > 0 && at < slash) s = s.substring(at + 1);      // user@host/path is a request to the host
        if (NUMBERS_ALONE.matcher(s).matches()) return !GivenWords.stated(s);
        return bareWhole(s) || NUMBERED.matcher(s).matches();
    }

    /** Whether a value under an argument of this name is held as an address: always for an address argument, and for a name that says address when the value is written as one. */
    private static boolean heldAsAddress(String name, String value, Set<String> addresses) {
        return addressKey(name, addresses) || (name != null && NAMED_AN_ADDRESS.matcher(name).matches() && webShaped(value));
    }

    private static String textNotGiven(String v, boolean within, boolean bareCounts) {
        String s = fold(v).strip();
        if (s.isEmpty()) return null;
        // a fetch's url or address is the address it will request, whatever it looks like: https:// is put before a value without a scheme,
        // so a host ending .md or .zip, host:port/path and user@host/path are addresses too (round F), spaces and braces in it
        if (bareCounts && internal(s)) return INSIDE.contains(v.strip()) ? null : v.strip();   // a locator of the library's own: exactly as the library listed it
        if (bareCounts && entryId(s)) return null;   // an entry's id names no host: read from the shelves, never requested
        if (bareCounts) return meant(requested(s)) ? null : v.strip();
        if (FILE_LIKE.matcher(s).matches()) return null;   // an entry id or a file's name in any other value: no address
        if (s.indexOf(' ') < 0 && URL.matcher(s).matches()) return meant(s) ? null : v.strip();
        if (!within) return null;
        // a piece of the value that begins as an address is one, whole, to the next white space, whatever stands in it: the pattern below
        // ends at a quote or a bracket, and java.net.URI does not ("https://a-given-host'@another/…" is a request to the other host). A
        // list of addresses with spaces between them is so many addresses
        for (String piece : s.split("\\s+")) if (piece.matches("(?i)^https?://.*") && !meant(piece)) return piece;
        // among other words (a sentence, a page's markup) an address ends where the pattern ends; what a tool makes of the rest is checked
        // where its request is made (Outbound)
        Matcher m = URL.matcher(s);
        while (m.find()) if (!meant(m.group())) return m.group();
        return null;
    }

    /**
     * Whether a call may hold this address: it was given, or it is the address this program itself builds for a name or a number that
     * was given ({@link GivenIdentifiers}: a paper by its DOI or arXiv number, a repository by owner and name, an encyclopedia's
     * article by its title, the front page or the robots file of a site of which a page was given), which is given from here on.
     */
    static boolean meant(String address) { return given(address) || GivenIdentifiers.opens(address); }

    /** A run's tool call, or null when it may go: an address in it that was not given makes it the sentence the model is told. */
    public static String unopened(String tool, JsonNode args) { return unopened(tool, args, Set.of()); }

    /** The same, with the arguments the tool says it takes an address in ({@code Tool.addressArguments}). */
    public static String unopened(String tool, JsonNode args, Set<String> addresses) {
        if (OPEN_NOTHING.contains(tool)) return null;
        String a = notGiven(tool, args, true, false, addresses);   // anywhere in an argument's text, as in the chat: a list of addresses with spaces between them is addresses too (0.8.1)
        return a == null ? null : refused(a);
    }

    /** What the model is told of an address it was not given: nothing was opened, and why, in one sentence. */
    public static String refused(String address) {
        if (address != null && address.startsWith(GivenWords.NOT_LOOKED_UP)) return address;   // a street that was not given: its own sentence
        return "NOT OPENED: the library opens only an address that the person gave or that a search, a page or a tool returned, as it was written there, and this one is none of them; an address put together from a page's instructions or from what the library holds is never opened.";
    }

    /**
     * An address as it is compared: the sentence's punctuation after it and the fragment taken off (the fragment never leaves the
     * browser, so an address and its fragment are the same request), the scheme set aside, the host in small letters, the port its
     * scheme has anyway left off ({@code https://example.org:443/a} is {@code https://example.org/a}; another port stays, the door
     * of another program), the path's escapes read (Fähre and F%C3%A4hre are one address), a slash after the path left off (a
     * person writes example.org/guide and a model example.org/guide/, or the other way round; until 0.8.1 the second was refused;
     * until its last reading so was a page given with :443 written out and asked without it), "www." before the host left off
     * ({@link #withoutWww}). What is requested is the spelling that was given ({@link #asRequested}). Null for no address.
     */
    static String key(String a) {
        if (a == null) return null;
        String s = trimmed(fold(a).strip());
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        Matcher scheme = Pattern.compile("(?i)^(https?)://").matcher(s);
        boolean written = scheme.find();
        String rest = written ? s.substring(scheme.end()) : s;
        if (rest.isEmpty() || rest.startsWith("/")) return null;
        int cut = rest.length();
        for (char c : new char[]{'/', '?'}) { int i = rest.indexOf(c); if (i >= 0 && i < cut) cut = i; }
        String host = withoutWww(site(written ? scheme.group(1).toLowerCase(Locale.ROOT) : "https", rest.substring(0, cut).toLowerCase(Locale.ROOT)));
        String after = rest.substring(cut);
        int q = after.indexOf('?');
        String path = q < 0 ? after : after.substring(0, q), query = q < 0 ? "" : after.substring(q);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);   // a slash after an address, written or left off, is the same address
        return "//" + host + unescaped(path + query);
    }

    /** The %XX escapes of a path read as UTF-8; anything else as it is. */
    public static String unescaped(String p) {
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
