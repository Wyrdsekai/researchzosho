package org.researchzosho.records;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Profiles;
import org.researchzosho.tools.Fetch;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.text.Normalizer;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.node.MissingNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
/** The record sources this library can search: the built-in list and the owner's own, and the one way all of them are searched. */
public final class RecordSources {

    private RecordSources() { }

    private static final ObjectMapper J = new ObjectMapper();

    /** One hit: what it is, when, where to read it, the words around the match, and how the record is cited. */
    public record Hit(String title, String date, String link, String snippet, String where) { }

    /** An address in, the body out; the live one is {@link Fetch} with its guards. Tests pass their own. */
    public interface Reader {
        String read(String url) throws Exception;
        /** With request headers (a source whose key goes in a header); a reader that has no use for them ignores them. */
        default String read(String url, Map<String, String> headers) throws Exception { return read(url); }
        /** A form sent to an address (the exchange of a key and a secret for a token). */
        default String post(String url, Map<String, String> headers, String body) throws Exception { throw new UnsupportedOperationException("this reader does not send forms"); }
    }

    private static final HttpClient POSTER = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public static final Reader LIVE = new Reader() {
        @Override public String read(String url) throws Exception { return read(url, Map.of()); }
        @Override public String read(String url, Map<String, String> headers) throws Exception {
            Fetch.Result r = Fetch.get(url, Duration.ofSeconds(25), headers);
            if (r.status() == 403 || r.status() == 429) throw new IllegalStateException("HTTP " + r.status() + ": the source's limit on searches was reached; wait a minute");
            if (r.status() >= 400) throw new IllegalStateException("HTTP " + r.status());
            return new String(r.body(), StandardCharsets.UTF_8);
        }
        @Override public String post(String url, Map<String, String> headers, String body) throws Exception {
            if (!url.startsWith("https://")) throw new IllegalStateException("a key and a secret are only sent over https");
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(25)).POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(b::header);
            HttpResponse<String> r = Stopping.send(POSTER, b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), Duration.ofSeconds(25), "the record collection at " + URI.create(url).getHost());
            if (r.statusCode() == 401 || r.statusCode() == 400) throw new IllegalStateException("the key and the secret were refused (HTTP " + r.statusCode() + ")");
            if (r.statusCode() >= 400) throw new IllegalStateException("HTTP " + r.statusCode());
            return r.body();
        }
    };

    /** The owner's file: the same form as the built-in list. An entry with a built-in id replaces it. */
    public static Path ownFile() { return Config.home().resolve("record-sources.json"); }

    private static volatile List<RecordSource> cached = null;
    private static volatile String cachedFor = "";

    /** Read once, and again when the owner's file changes. */
    public static List<RecordSource> all() {
        Path own = ownFile();
        String stamp;
        try { stamp = own + "@" + (Files.exists(own) ? Files.getLastModifiedTime(own).toMillis() : 0); } catch (IOException e) { stamp = own + "@?"; }
        List<RecordSource> now = cached;
        if (now == null || !stamp.equals(cachedFor)) { now = all(own); cached = now; cachedFor = stamp; }
        return now;
    }

    static List<RecordSource> all(Path own) {
        Map<String, RecordSource> out = new LinkedHashMap<>();
        try (InputStream in = RecordSources.class.getResourceAsStream("sources.json")) {
            if (in != null) for (JsonNode n : J.readTree(in)) { RecordSource s = parse(n, true); if (s != null) out.put(s.id(), s); }
        } catch (Exception e) { throw new IllegalStateException("the built-in record sources do not parse: " + e.getMessage(), e); }
        if (own != null && Files.exists(own)) {
            try { for (JsonNode n : J.readTree(Files.readString(own, StandardCharsets.UTF_8))) { RecordSource s = parse(n, false); if (s != null) out.put(s.id(), s); } }
            catch (Exception e) { System.err.println("record sources: " + own + " does not parse (" + e.getMessage() + "); the built-in list is used"); }
        }
        return new ArrayList<>(out.values());
    }

    static RecordSource parse(JsonNode n, boolean builtIn) {
        if ("link".equals(n.path("kind").asText(""))) return null;   // a site the library does not search: see links()
        String id = n.path("id").asText("").strip(), url = n.path("url").asText("").strip();
        if (id.isEmpty() || !url.startsWith("http") || !url.contains("{query")) return null;
        return new RecordSource(id, n.path("name").asText(id), n.path("holds").asText(""), n.path("kind").asText("record"),
                strings(n.path("countries")), strings(n.path("languages")), n.path("from").asInt(0), n.path("to").asInt(0),
                n.path("tier").asText(""), url, n.path("format").asText("json"), n.path("items").asText(""),
                n.path("title").asText(""), n.path("date").asText(""), n.path("link").asText(""), n.path("snippet").asText(""),
                n.path("where").asText(""), n.path("key").asText(""), n.path("key_from").asText(""), n.path("key_optional").asBoolean(false), n.path("key_header").asText(""), n.path("key_login").asText(""), n.path("app").asText(""), n.path("app_from").asText(""), strings(n.path("fields")), hostsOf(n), builtIn,
                n.path("secret").asText(""), n.path("token_url").asText(""), n.path("accept").asText(""), n.path("empty_status").asInt(0));
    }

    /** The hosts a record of this source is read on: the ones the entry names, the link's own when it is written out, and the search address's. */
    private static List<String> hostsOf(JsonNode n) {
        List<String> out = strings(n.path("hosts"));
        for (String field : new String[]{"link", "url"}) {
            Matcher m = Pattern.compile("^https?://([^/{}\\[\\]?]+)").matcher(n.path(field).asText(""));
            if (m.find()) { String h = m.group(1).toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""); if (!out.contains(h)) out.add(h); }
        }
        return out;
    }

    // ---- sites the library does not search: a filled search address for the person to open ----

    /**
     * A record site with no search the library can make (most of the big ones): the address of a search there, filled from what the
     * library holds about a person, for the person to open. Written in the same files with {@code "kind": "link"}; {@code access} is
     * free, registration or subscription. The address takes {@code {name}}, {@code {given}}, {@code {family}}, {@code {born}},
     * {@code {died}} and {@code {place}}; a part in square brackets is dropped when a value inside it is missing.
     */
    public record SearchLink(String id, String name, String holds, String access, List<String> countries, String url, List<String> fields) {
        /** "free", "free with an account", "needs a paid subscription": what opening it asks of the person. */
        public String accessText() { return switch (access) { case "registration" -> "free with an account"; case "subscription" -> "needs a paid subscription"; default -> "free"; }; }
    }

    public static List<SearchLink> links() { return links(ownFile()); }

    static List<SearchLink> links(Path own) {
        Map<String, SearchLink> out = new LinkedHashMap<>();
        try (InputStream in = RecordSources.class.getResourceAsStream("sources.json")) {
            if (in != null) for (JsonNode n : J.readTree(in)) { SearchLink l = parseLink(n); if (l != null) out.put(l.id(), l); }
        } catch (IOException e) { throw new IllegalStateException("the built-in record sources do not parse: " + e.getMessage(), e); }
        if (own != null && Files.exists(own)) {
            try { for (JsonNode n : J.readTree(Files.readString(own, StandardCharsets.UTF_8))) { SearchLink l = parseLink(n); if (l != null) out.put(l.id(), l); } }
            catch (IOException e) { System.err.println("record sources: " + own + " does not parse (" + e.getMessage() + "); the built-in list is used"); }
        }
        return new ArrayList<>(out.values());
    }

    static SearchLink parseLink(JsonNode n) {
        String id = n.path("id").asText("").strip(), url = n.path("url").asText("").strip();
        if (!"link".equals(n.path("kind").asText("")) || id.isEmpty() || !url.startsWith("http")) return null;
        return new SearchLink(id, n.path("name").asText(id), n.path("holds").asText(""), n.path("access").asText("free"), strings(n.path("countries")), url, strings(n.path("fields")));
    }

    private static final Pattern LINK_SLOT = Pattern.compile("\\{(name|given|family|born|died|place)}");

    /** The address with the person's values in it; null when a value the address cannot do without (one outside brackets) is missing. */
    public static String filled(SearchLink l, Map<String, String> values) {
        Function<String, String> at = k -> { String v = values.get(k); return v == null || v.isBlank() ? "" : URLEncoder.encode(v.strip(), StandardCharsets.UTF_8); };
        Matcher required = LINK_SLOT.matcher(OPTIONAL.matcher(l.url()).replaceAll(""));
        while (required.find()) if (at.apply(required.group(1)).isEmpty()) return null;
        return fill(l.url(), LINK_SLOT, at);
    }

    /** Whether this host serves the records of a source that holds the records themselves: such a page is a primary source. */
    public static boolean holdsRecords(String host) {
        if (host == null || host.isBlank()) return false;
        String h = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        for (RecordSource s : all()) if (s.holdsTheRecord()) for (String mine : s.hosts()) if (h.equals(mine) || h.endsWith("." + mine)) return true;
        return false;
    }

    private static List<String> strings(JsonNode a) { List<String> o = new ArrayList<>(); for (JsonNode x : a) o.add(x.asText()); return o; }

    public static RecordSource named(String id) {
        for (RecordSource s : all()) if (s.id().equalsIgnoreCase(id == null ? "" : id.strip())) return s;
        return null;
    }

    /** The token in the address a login lands on ("…#access_token=XXXX&expires_in=86400"), or in a bare token pasted by itself; "" when there is none. */
    public static String tokenFrom(String pasted) {
        String p = pasted == null ? "" : pasted.strip();
        // a browser may hand the address over with its = and & written as %3D and %26: read it both ways
        if (!p.contains("access_token=") && p.toLowerCase(Locale.ROOT).contains("access_token%3d")) { try { p = URLDecoder.decode(p, StandardCharsets.UTF_8); } catch (IllegalArgumentException ignored) { } }
        Matcher m = Pattern.compile("access_token=([^&#\\s]+)").matcher(p);
        if (m.find()) return URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        return p.matches("[A-Za-z0-9._~+/=-]{16,}") ? p : "";
    }

    /** How long that login lasts, in hours, when the address says; 0 when it does not. */
    public static long hoursFrom(String pasted) {
        Matcher m = Pattern.compile("(?i)expires_in(?:=|%3d)(\\d+)").matcher(pasted == null ? "" : pasted);
        return m.find() ? Long.parseLong(m.group(1)) / 3600 : 0;
    }

    /** Whether the source can be searched now: a source that needs a key is usable once the key is in the settings. */
    public static boolean usable(RecordSource s) {
        if (s.exchangesAToken()) { String secret = s.secretName().isBlank() ? "" : settings.apply(s.secretName()); return !key(s).isBlank() && secret != null && !secret.isBlank(); }
        return !s.needsKey() || !key(s).isBlank();
    }

    /** Where a key or a secret is read from: the settings. A test puts its own in. */
    static Function<String, String> settings = Config::get;

    static String key(RecordSource s) { String k = s.keyName() == null || s.keyName().isBlank() ? "" : settings.apply(s.keyName()); return k == null ? "" : k.strip(); }

    /**
     * The usable sources in the order a question wants them: first the ones whose language is one the question is
     * written in or names a place of, then the ones that cover every country, then the rest. Nothing is dropped:
     * the order only decides what the worker reads first.
     */
    /** The order for a worker whose sub-question NAMES collections (the planner assigns them): those first, then as {@link #ordered(Collection)}. */
    public static List<RecordSource> ordered(Collection<String> languagesOfTheQuestion, String focus) {
        List<RecordSource> all = ordered(languagesOfTheQuestion);
        List<RecordSource> named = named(focus, all);
        if (named.isEmpty()) return all;
        List<RecordSource> out = new ArrayList<>(named);
        for (RecordSource s : all) if (!out.contains(s)) out.add(s);
        return out;
    }

    /**
     * The years a question's subject lived: {born, until}, as a field that reads dates can tell them from the question ({@link
     * Profile#yearsOf}): "born about 1850" starts at 1845, and a death "about 1928" ends in 1933. Null when no field can tell.
     */
    public static int[] lived(String question) { return lived(question, Profiles.known()); }

    /** The same, asked only of {@code fields}: the fields of the run the question belongs to. */
    public static int[] lived(String question, Collection<? extends Profile> fields) {
        for (Profile p : fields) { int[] y = p.yearsOf(question); if (y != null) return y; }
        return null;
    }

    /** Records about a person go on being made after their death: obituaries, histories, a who's-who. How long after, at most. */
    static final int AFTER_DEATH = 60;

    /**
     * The collections that can hold anything about a person who lived in these years: a collection with years of its own that
     * end before the person was born, or begin more than a lifetime after they died, is left out. A collection without years stays.
     */
    public static List<RecordSource> forYears(List<RecordSource> sources, int[] lived) {
        if (lived == null) return sources;
        List<RecordSource> out = new ArrayList<>();
        for (RecordSource s : sources) {
            boolean endsBefore = s.toYear() > 0 && s.toYear() < lived[0];
            boolean beginsAfter = s.fromYear() > 0 && s.fromYear() > lived[1] + AFTER_DEATH;
            if (!endsBefore && !beginsAfter) out.add(s);
        }
        return out;
    }

    /** The sources a text names by id, in the order it names them. */
    public static List<RecordSource> named(String text, List<RecordSource> among) {
        List<RecordSource> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        String t = text.toLowerCase(Locale.ROOT);
        among.stream().filter(s -> t.contains(s.id().toLowerCase(Locale.ROOT))).sorted(Comparator.comparingInt(s -> t.indexOf(s.id().toLowerCase(Locale.ROOT)))).forEach(out::add);
        return out;
    }

    /**
     * The collections in a few words each, for the planner: a sub-question that names the collection to search is searched there
     * (measured: a worker whose sub-question said "search Japanese newspapers" called record_search nine times; workers whose
     * sub-questions named no kind of record mostly never called it, and no run ever reached the US newspapers).
     */
    public static String brief(Collection<String> fields) {
        StringBuilder b = new StringBuilder();
        for (RecordSource s : forFields(ordered(List.of()), fields)) {
            if (!s.holdsTheRecord() && !s.id().startsWith("wikipedia")) continue;
            b.append("- ").append(s.id()).append(": ").append(s.kind()).append(s.countries().isEmpty() ? ", every country" : ", " + String.join("/", s.countries())).append(s.years().isEmpty() ? "" : ", " + s.years());
            String h = s.holds(); int cut = h.indexOf(". "); if (cut < 0) cut = h.indexOf(": "); 
            b.append(" — ").append(cut > 0 && cut < 110 ? h.substring(0, cut) : h.length() > 110 ? h.substring(0, 110) + "…" : h).append('\n');
        }
        return b.toString();
    }

    /** Only the sources of the fields this run belongs to (genealogy's newspapers, software's repositories), in the usual order. */
    public static List<RecordSource> forFields(List<RecordSource> sources, Collection<String> fields) {
        return sources.stream().filter(s -> s.forField(fields)).toList();
    }

    public static List<RecordSource> ordered(Collection<String> languagesOfTheQuestion) {
        List<RecordSource> mine = new ArrayList<>(), everywhere = new ArrayList<>(), rest = new ArrayList<>();
        for (RecordSource s : all()) {
            if (!usable(s)) continue;
            if (s.languages().isEmpty() && s.countries().isEmpty()) everywhere.add(s);
            else if (s.languages().stream().anyMatch(l -> languagesOfTheQuestion != null && languagesOfTheQuestion.contains(l))) mine.add(s);
            else rest.add(s);
        }
        mine.addAll(everywhere); mine.addAll(rest);
        return mine;
    }

    // ---- the search ----

    public static List<Hit> search(RecordSource s, String query, int fromYear, int toYear, int limit, Reader reader) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        if (!s.accept().isBlank()) headers.put("Accept", s.accept());
        if (s.exchangesAToken()) headers.put("Authorization", "Bearer " + token(s, reader));
        String k = key(s);
        if (!k.isEmpty() && s.keyHeader().contains(":")) headers.put(s.keyHeader().substring(0, s.keyHeader().indexOf(':')).strip(), s.keyHeader().substring(s.keyHeader().indexOf(':') + 1).strip().replace("{key}", k));
        String body;
        try { body = reader.read(address(s, query, fromYear, toYear, limit), headers); }
        catch (IllegalStateException e) {
            // some sources answer "nothing found" with an error status: that is a search that found nothing, not a source that failed
            if (s.emptyStatus() > 0 && ("HTTP " + s.emptyStatus()).equals(e.getMessage())) return new ArrayList<>();
            // a token that ran out early: one new token, one more try
            if (s.exchangesAToken() && e.getMessage() != null && (e.getMessage().startsWith("HTTP 400") || e.getMessage().startsWith("HTTP 401"))) {
                TOKENS.remove(s.id());
                headers.put("Authorization", "Bearer " + token(s, reader));
                try { body = reader.read(address(s, query, fromYear, toYear, limit), headers); }
                catch (IllegalStateException again) { if (s.emptyStatus() > 0 && ("HTTP " + s.emptyStatus()).equals(again.getMessage())) return new ArrayList<>(); throw again; }
            } else throw e;
        }
        return s.xml() ? hitsFromXml(s, body, limit) : hitsFromJson(s, body, limit);
    }

    private record Token(String value, Instant until) { }
    private static final Map<String, Token> TOKENS = new ConcurrentHashMap<>();

    /** The key and the secret handed in for a token, which is kept until a minute before it runs out. Neither is ever written anywhere. */
    static String token(RecordSource s, Reader reader) throws Exception {
        Token held = TOKENS.get(s.id());
        if (held != null && Instant.now().isBefore(held.until())) return held.value();
        String k = key(s), secret = s.secretName().isBlank() ? "" : settings.apply(s.secretName());
        if (k.isBlank() || secret == null || secret.isBlank()) throw new IllegalStateException(s.name() + " needs a key and a secret: researchzosho records key " + s.id() + " <key> <secret>");
        String basic = Base64.getEncoder().encodeToString((k + ":" + secret.strip()).getBytes(StandardCharsets.UTF_8));
        JsonNode n = J.readTree(reader.post(s.tokenUrl(), Map.of("Authorization", "Basic " + basic, "Content-Type", "application/x-www-form-urlencoded"), "grant_type=client_credentials"));
        String value = n.path("access_token").asText("");
        if (value.isBlank()) throw new IllegalStateException(s.name() + " did not give a token for this key and secret");
        long seconds = Math.max(60, n.path("expires_in").asLong(600));
        TOKENS.put(s.id(), new Token(value, Instant.now().plusSeconds(seconds - 60)));
        return value;
    }

    private static final Pattern OPTIONAL = Pattern.compile("\\[([^\\[\\]]*)]");
    private static final Pattern SLOT = Pattern.compile("\\{(query|limit|from|to|key)(?::(bare|last|rest))?}");

    static String address(RecordSource s, String query, int fromYear, int toYear, int limit) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("query", query == null ? "" : query.strip());
        v.put("limit", String.valueOf(limit));
        v.put("from", fromYear > 0 ? String.valueOf(fromYear) : "");
        v.put("to", toYear > 0 ? String.valueOf(toYear) : "");
        v.put("key", key(s));
        // an optional part goes when any value inside it is missing
        Matcher o = OPTIONAL.matcher(s.url());
        StringBuilder kept = new StringBuilder();
        while (o.find()) {
            boolean whole = true;
            Matcher in = SLOT.matcher(o.group(1));
            while (in.find()) if (shaped(v.get(in.group(1)), in.group(2)).isEmpty()) whole = false;
            o.appendReplacement(kept, Matcher.quoteReplacement(whole ? o.group(1) : ""));
        }
        o.appendTail(kept);
        Matcher m = SLOT.matcher(kept.toString());
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = shaped(v.get(m.group(1)), m.group(2));
            m.appendReplacement(out, Matcher.quoteReplacement(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A slot's value in the shape its mode asks for: quotes off (bare), the family name (last: the last word), the given names (rest). */
    static String shaped(String value, String mode) {
        if (mode == null) return value;
        String v = value.replace("\"", "").replace("“", "").replace("”", "").strip();
        if (mode.equals("last")) return v.contains(" ") ? v.substring(v.lastIndexOf(' ') + 1) : v;
        if (mode.equals("rest")) return v.contains(" ") ? v.substring(0, v.lastIndexOf(' ')).strip() : "";
        return v;
    }

    static List<Hit> hitsFromJson(RecordSource s, String body, int limit) throws Exception {
        List<Hit> out = new ArrayList<>();
        JsonNode items = J.readTree(body).at(s.items().isEmpty() ? "" : s.items());
        // an answer made from XML writes a list of one as the one thing itself
        if (items.isObject()) items = J.createArrayNode().add(items);
        if (!items.isArray()) return out;
        for (JsonNode item : items) {
            if (out.size() >= limit) break;
            Function<String, String> at = p -> { JsonNode n = reach(item, p); return n.isMissingNode() || n.isNull() ? "" : n.isValueNode() ? n.asText() : text(n); };
            Hit h = hit(s, Pattern.compile("\\{(/[^{}]*)}"), at);
            if (h != null) out.add(h);
        }
        return out;
    }

    /**
     * A pointer followed the forgiving way an answer made from XML needs: where the next step is a name and the answer has a list there,
     * the step is taken in the list's entry in English, else in its first entry. A title given in two languages, or once, reads the same.
     */
    static JsonNode reach(JsonNode from, String pointer) {
        JsonNode n = from;
        for (String step : pointer.split("/")) {
            if (step.isEmpty()) continue;
            String name = step.replace("~1", "/").replace("~0", "~");
            if (n.isArray() && !name.matches("\\d+")) n = preferred(n);
            n = n.isArray() && name.matches("\\d+") ? n.path(Integer.parseInt(name)) : n.path(name);
            if (n.isMissingNode()) return n;
        }
        return n;
    }

    private static JsonNode preferred(JsonNode array) {
        for (JsonNode x : array) if (x.path("@lang").asText("").equalsIgnoreCase("en")) return x;
        return array.isEmpty() ? MissingNode.getInstance() : array.get(0);
    }

    /** What a part of the answer says: its own text ("$" in an answer made from XML), or the texts inside it, each once, the first eight. */
    static String text(JsonNode n) {
        if (n.isValueNode()) return n.asText();
        if (n.isObject() && n.path("$").isValueNode()) return n.path("$").asText();
        if (n.isArray() && n.size() > 0 && n.get(0).isValueNode()) return joined(n);
        LinkedHashMap<String, String> seen = new LinkedHashMap<>();
        collect(n.isArray() && n.size() > 0 && n.get(0).has("@lang") ? preferred(n) : n, seen);
        return String.join(", ", seen.values().stream().limit(8).toList());
    }

    private static void collect(JsonNode n, Map<String, String> seen) {
        if (n.isObject()) { if (n.path("$").isValueNode()) { String t = n.path("$").asText().strip(); if (!t.isEmpty()) seen.putIfAbsent(key(t), t); } else n.properties().forEach(e -> { if (!e.getKey().startsWith("@")) collect(e.getValue(), seen); }); }
        else if (n.isArray()) n.forEach(x -> collect(x, seen));
    }

    /** "TAKAMINE JOKICHI JR" and "Jokichi Takamine, Jr." are one name said twice. */
    private static String key(String name) { String[] w = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", " ").trim().split("\\s+"); Arrays.sort(w); return String.join(" ", w); }

    /** A list of plain values (a repository's topics) as one line, the first eight of them. */
    private static String joined(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode v : array) { if (v.isValueNode() && !v.asText().isBlank()) out.add(v.asText()); if (out.size() == 8) break; }
        return String.join(", ", out);
    }

    static List<Hit> hitsFromXml(RecordSource s, String body, int limit) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);   // a record source is somebody else's server
        f.setXIncludeAware(false); f.setExpandEntityReferences(false);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        NodeList items = doc.getElementsByTagName(s.items());
        List<Hit> out = new ArrayList<>();
        for (int i = 0; i < items.getLength() && out.size() < limit; i++) {
            Element item = (Element) items.item(i);
            Function<String, String> at = tag -> {
                NodeList l = item.getElementsByTagName(tag);
                for (int k = 0; k < l.getLength(); k++) { Node n = l.item(k); String t = n.getTextContent(); if (t != null && !t.isBlank()) return t.strip(); }
                return "";
            };
            Hit h = hit(s, Pattern.compile("\\{([^/{}][^{}]*)}"), at);
            if (h != null) out.add(h);
        }
        return out;
    }

    private static Hit hit(RecordSource s, Pattern slot, Function<String, String> at) {
        String title = clean(fill(s.title(), slot, at)), link = fill(s.link(), slot, at).strip();
        if (title.isEmpty() && link.isEmpty()) return null;
        String where = dashed(tidy(clean(fill(s.where(), slot, at))));
        if (title.isEmpty()) title = where;   // an old catalogue entry with no title of its own (a 1902 journal piece) is named by its citation
        if (link.startsWith("//")) link = "https:" + link;
        link = withoutTracking(link);
        return new Hit(title, dashed(clean(fill(s.date(), slot, at)).replaceFirst("[ T]00:00:00.*$", "")), link, clean(fill(s.snippet(), slot, at)).replaceFirst("^[·,;\\s]+", ""), where);
    }

    /** A date written as eight digits in a row (19260914) reads as 1926-09-14. */
    static String dashed(String text) {
        return Pattern.compile("(?<!\\d)(1[5-9]\\d\\d|20\\d\\d)(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])(?!\\d)").matcher(text).replaceAll("$1-$2-$3");
    }

    /**
     * A link without its tracking parameters (utm_…). One archive writes the caller's own API KEY into utm_campaign on every link it
     * returns: left in, the key would be printed in every report's references.
     */
    static String withoutTracking(String link) {
        int q = link.indexOf('?');
        if (q < 0) return link;
        String frag = ""; String query = link.substring(q + 1);
        int h = query.indexOf('#'); if (h >= 0) { frag = query.substring(h); query = query.substring(0, h); }
        List<String> kept = new ArrayList<>();
        for (String part : query.split("&")) if (!part.isEmpty() && !part.toLowerCase(Locale.ROOT).startsWith("utm_")) kept.add(part);
        return link.substring(0, q) + (kept.isEmpty() ? "" : "?" + String.join("&", kept)) + frag;
    }

    /** A template over one hit; a part in square brackets goes when a value inside it is missing, as in the address. */
    private static String fill(String template, Pattern slot, Function<String, String> at) {
        if (template == null || template.isEmpty()) return "";
        Matcher o = OPTIONAL.matcher(template);
        StringBuilder kept = new StringBuilder();
        while (o.find()) {
            boolean whole = true;
            Matcher in = slot.matcher(o.group(1));
            while (in.find()) if (at.apply(in.group(1)).isBlank()) whole = false;
            o.appendReplacement(kept, Matcher.quoteReplacement(whole ? o.group(1) : ""));
        }
        o.appendTail(kept);
        Matcher m = slot.matcher(kept.toString());
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(at.apply(m.group(1))));
        m.appendTail(b);
        return b.toString();
    }

    /** Markup and highlight marks out, whitespace collapsed, a long snippet cut. */
    static String clean(String s) {
        String t = s.replaceAll("<[^>]{1,40}>", "").replace("{{{", "").replace("}}}", "").replace("&amp;", "&").replace("&quot;", "\"").replaceAll("\\s+", " ").strip();
        return t.length() > 420 ? t.substring(0, 420) + "…" : t;
    }

    static String tidy(String where) { return where.replaceAll("^[,\\s]+|[,\\s]+$", "").replaceAll("\\s+,", ","); }

    /**
     * How long ago a recent date was, worked out here: a model given "last push 2026-03-08" called a six-month-old project "inactive for
     * 12+ months". Only for a full date of the last ten years; an 1887 newspaper has no use for it.
     */
    static String age(String date, LocalDate today) {
        Matcher m = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})").matcher(date == null ? "" : date);
        if (!m.find()) return "";
        try {
            LocalDate d = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            long days = ChronoUnit.DAYS.between(d, today);
            if (days < 0 || days > 3660) return "";
            return days < 2 ? " (today or yesterday)" : days < 60 ? " (" + days + " days ago)" : days < 730 ? " (" + (days / 30) + " months ago)" : " (" + (days / 365) + " years ago)";
        } catch (Exception e) { return ""; }
    }

    /** What a worker reads: each hit with its date, its citation, its link and the words around the match. */
    public static String render(RecordSource s, String query, List<Hit> hits) { return render(s, query, hits, List.of()); }

    /**
     * What a question says of its subject besides the name, as the words a record about them might carry: every year, and every
     * word or run of characters from the part of the question that describes the person (after the name, before the question itself).
     * "Endo Haru (also written 遠藤ハル; born 1880 in 広島; child of 遠藤源三郎): what records…" gives 1880, 広島, 遠藤源三郎, and the name forms too,
     * which cost nothing: a record that repeats the name matches the name.
     */
    public static List<String> saidOf(String question) {
        if (question == null || question.isBlank()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        Matcher y = Pattern.compile("(?<!\\d)(1[5-9]\\d\\d|20\\d\\d)(?!\\d)").matcher(question);
        while (y.find()) out.add(y.group(1));
        Matcher d = Pattern.compile("^[^(（]*[(（]([^)）]*)[)）]").matcher(question.strip());
        if (d.find()) {
            for (String w : d.group(1).split("[;；,、，:：]")) {
                String p = w.replaceAll("^\\s*(also written|born|died|child of|parent of|married to|lived in|in|on|and|of)\\s+", "").strip();
                for (String part : p.split("\\s+(and|in|on|of)\\s+")) {
                    String t = part.strip();
                    if (t.length() >= 2 && !t.matches("\\d{1,3}") && !t.matches("(?i)also written|born|died|child of|parent of|married to|lived in")) out.add(t);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Which of the things said of the subject a record also carries: whole words for Latin script and years, runs of characters without spaces for CJK. */
    static List<String> carries(Hit h, List<String> saidOfThem) {
        String text = fold(h.title() + " " + h.snippet() + " " + h.date() + " " + h.where());
        List<String> out = new ArrayList<>();
        for (String said : saidOfThem) {
            String k = fold(said);
            if (k.length() < 2) continue;
            boolean cjk = k.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN || Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA);
            boolean hit = cjk ? text.replace(" ", "").contains(k.replace(" ", "")) : Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(k) + "(?![\\p{L}\\p{N}])").matcher(text).find();
            if (hit) out.add(said);
        }
        return out;
    }

    private static String fold(String s) { return Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip(); }

    /**
     * The records, and under each what it carries of what the question says of the person besides the name. A collection matches
     * names, and a name is shared: the library counts, for each record, the years, places and relatives it also carries, so that the
     * worker reads "this record and the family agree on 1912 and 広島" or "the name only" before it takes the record as the person's.
     */
    public static String render(RecordSource s, String query, List<Hit> hits, List<String> saidOfThem) { return render(s, query, hits, saidOfThem, Fetch.Policy.DEFAULT); }

    /** The records that stay under the run's lists: a record whose link is on the person's refused list or on the site list is left out. */
    public record Kept(List<Hit> hits, int refused, int listed) {
        /** The line that says how many were left out and why; "" when none was. */
        public String note() {
            StringBuilder b = new StringBuilder();
            if (refused > 0) b.append("(").append(refused).append(" record(s) left out: on the person's refused-sources list)");
            if (listed > 0) b.append(b.isEmpty() ? "" : " ").append("(").append(listed).append(" record(s) left out: on ").append(Fetch.SITE_LIST_NAME).append(")");
            return b.toString();
        }
    }

    /** Every record a search found was left out: said as that, never as a search that found nothing. */
    public static String allLeftOut(RecordSource s, String query, Kept kept) {
        return "Every record " + s.name() + " found for: " + query + " was left out " + kept.note() + ". Search another source or other words.";
    }

    /** {@code hits} under {@code policy}: the ones it keeps, and how many each list took out. */
    public static Kept kept(List<Hit> hits, Fetch.Policy policy) {
        Fetch.Policy p = policy == null ? Fetch.Policy.DEFAULT : policy;
        List<Hit> out = new ArrayList<>();
        int refused = 0, listed = 0;
        for (Hit h : hits) {
            if (!h.link().isBlank() && p.refused(h.link())) { refused++; continue; }
            if (!h.link().isBlank() && p.onSiteList(h.link())) { listed++; continue; }
            out.add(h);
        }
        return new Kept(out, refused, listed);
    }

    /** As above, under the run's lists ({@link #kept}): what is left out is counted, never shown. */
    public static String render(RecordSource s, String query, List<Hit> all, List<String> saidOfThem, Fetch.Policy policy) {
        Kept kept = kept(all, policy);
        List<Hit> hits = kept.hits();
        if (hits.isEmpty() && !kept.note().isEmpty()) return allLeftOut(s, query, kept);
        if (hits.isEmpty()) return "no records found in " + s.name() + " for: " + query + ". That is a result: note it (the source, the words and the years searched), then try another spelling, the name in its own script, or another source.";
        StringBuilder b = new StringBuilder(s.name()).append(" — ").append(hits.size()).append(" record(s) for: ").append(query).append("  [").append(s.tier().isEmpty() ? s.kind() : s.tier()).append("]\n");
        if (!kept.note().isEmpty()) b.append(kept.note()).append('\n');
        int i = 1;
        for (Hit h : hits) {
            b.append('\n').append(i++).append(". ").append(h.title().isEmpty() ? "(untitled)" : h.title()).append(h.date().isEmpty() ? "" : " — " + h.date() + age(h.date(), LocalDate.now())).append('\n');
            if (!h.link().isEmpty()) b.append("   ").append(h.link()).append('\n');
            if (!h.where().isEmpty()) b.append("   cite as: ").append(h.where()).append('\n');
            if (!h.snippet().isEmpty()) b.append("   ").append(h.snippet()).append('\n');
            if (!saidOfThem.isEmpty()) {
                List<String> agree = carries(h, saidOfThem);
                b.append("   ").append(agree.isEmpty() ? "matches the name only" : "also carries what the question says of this person: " + String.join(", ", agree)).append('\n');
            }
        }
        b.append("\nThe words shown are machine-read from a scan and may be wrong: web_fetch the link and read the record before you note a fact from it.");
        if (!saidOfThem.isEmpty()) b.append(" A name is shared by many people, and a collection matches the name. Take a record as this person's when it also carries something the question says of them "
                + "(a year, a place, a relative, their work), or when the record you fetch says so. A record that matches the name only is a record of somebody of that name: "
                + "note what it says as being about somebody who may be this person, with what agrees and what does not.");
        return b.toString();
    }
}
