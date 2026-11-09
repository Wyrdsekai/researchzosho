package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.researchzosho.drive.ContentJudge;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.ImageText;
import org.researchzosho.tools.SearchTally;
import org.researchzosho.tools.WebSearchTool;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pictures as a capability of the library (0.7.0, the first increment): candidates found on the web for words, kept on the shelves
 * with the page they came from, and read by the model that sees ({@link ModelEntries#seeing()}) — described in words a later search can
 * be built from, or judged against a description, one typed verdict per picture. The three uses the person asked for: find a picture or
 * a set of them; use pictures as evidence for or against what is asked; and start from pictures the person gives. This increment builds
 * the first two by words; the third, likeness to a given picture, has its seam here ({@link Likeness}) and nothing behind it yet.
 *
 * <p>A research run uses this the same way (the second increment, {@link RunPictures}): a worker builds the words from what the run knows
 * (an event's name, a person's name), has the candidates judged against what it is looking for, and files a picture that passes as a
 * finding's evidence — so a {@link Found} carries the page, the engine and when it was found, a {@link Judged} the verdict and its
 * reason, and both go onto the kept entry's head. The pictures inside a document the person reads in are kept the same way
 * ({@link DocPictures}, {@link #keepBytes}), and a search asks the shelves before the web ({@link #shelves}).
 *
 * <p>Two engines answer a search: Brave's image search when its key is kept and the month's limit allows, and the SearXNG the library
 * has, with images as the category. The results are merged rank by rank and told apart by the picture's address and by page and title.
 * A kept picture is fetched once, up to 8 MB, a picture format only; before it is written the one question every picture from the web
 * is asked goes to the seeing model when there is one ({@link ImageText#CHILD_QUESTION}); then the bytes go under {@code catalog/images/}
 * and an entry on the shelves says where it came from. The pages serve it at {@code /images/<file>}.
 */
public final class Images {
    private Images() { }

    private static final ObjectMapper M = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();

    /** The head field that marks a kept picture's entry, and who fetched it. */
    public static final String KIND = "image", BY = "researchzosho-images";
    /** The most a kept picture may weigh. */
    public static final long MAX_BYTES = 8L * 1024 * 1024;
    /** How many candidates a search gives when nothing says, and the most it ever gives. */
    public static final int DEFAULT_COUNT = 8, MOST = 40;
    static final Duration FETCH_TIMEOUT = Duration.ofSeconds(20), SEARCH_TIMEOUT = Duration.ofSeconds(25);

    // ---- what is found ----

    /**
     * One candidate: the picture's address, the page it stands on, the page's title, the engine that found it ({@code source}), its
     * size when the engine said, a small copy's address when the engine gave one, and when it was found (an instant). Enough to be
     * cited as evidence: the page, the engine and the time.
     */
    public record Found(String image, String page, String title, String source, int width, int height, String thumbnail, String found, String caption) {
        public Found(String image, String page, String title, String source, int width, int height, String thumbnail) { this(image, page, title, source, width, height, thumbnail, Instant.now().toString(), ""); }
        public Found(String image, String page, String title, String source, int width, int height, String thumbnail, String found) { this(image, page, title, source, width, height, thumbnail, found, ""); }
        public Found withSize(int w, int h) { return w > 0 && h > 0 && (width <= 0 || height <= 0) ? new Found(image, page, title, source, w, h, thumbnail, found, caption) : this; }
        /** The page's host, for a sentence. */
        public String host() { try { String h = URI.create(page == null || page.isBlank() ? image : page).getHost(); return h == null ? "" : h.replaceFirst("^www\\.", ""); } catch (RuntimeException e) { return ""; } }
        /** "1920×1080" or "". */
        public String size() { return width > 0 && height > 0 ? width + "×" + height : ""; }
        /** One sentence a person reads: the title, where it stands, its size and who found it. */
        public String said() {
            String t = title == null || title.isBlank() ? "A picture" : "\"" + title + "\"";
            return t + (host().isEmpty() ? "" : " from " + host()) + (size().isEmpty() ? "" : ", " + size()) + (source == null || source.isBlank() ? "" : ", found through " + source) + ".";
        }
        /** The Markdown image line the chat shows as a small picture: the small copy when there is one, else the picture itself. */
        public String show() { String src = thumbnail != null && !thumbnail.isBlank() ? thumbnail : image; return "![" + (title == null ? "" : title.replace("]", ")").replace("\n", " ")) + "](" + src + ")"; }
        public ObjectNode json(ObjectMapper m) {
            ObjectNode o = m.createObjectNode();
            o.put("image", image); o.put("page", page == null ? "" : page); o.put("title", title == null ? "" : title); o.put("source", source == null ? "" : source);
            if (width > 0) o.put("width", width); if (height > 0) o.put("height", height);
            if (thumbnail != null && !thumbnail.isBlank()) o.put("thumbnail", thumbnail);
            if (caption != null && !caption.isBlank()) o.put("caption", caption);
            o.put("found", found == null ? "" : found); o.put("said", said());
            return o;
        }
        public static Found from(JsonNode n) {
            return new Found(n.path("image").asText(""), n.path("page").asText(""), n.path("title").asText(""), n.path("source").asText(""), n.path("width").asInt(0), n.path("height").asInt(0), n.path("thumbnail").asText(""), n.path("found").asText(Instant.now().toString()), n.path("caption").asText(""));
        }
    }

    /** The answer to "does this picture show …?": yes or no when the model said so, unsure when it said that, unjudged when nothing could answer. */
    public enum Verdict {
        YES, NO, UNSURE, UNJUDGED;
        public String word() { return name().toLowerCase(Locale.ROOT); }
        public static Verdict of(String w) { try { return valueOf(w.strip().toUpperCase(Locale.ROOT)); } catch (RuntimeException e) { return UNJUDGED; } }
    }

    /**
     * One picture judged against a description: the verdict, the model's one sentence why, which model, the description it was judged
     * against and when. A worker files this as a finding's evidence; a kept entry carries it in its head ({@link #more()}).
     */
    public record Judged(Found found, Verdict verdict, String why, String model, String criteria, String judged) {
        public Judged(Found found, Verdict verdict, String why, String model, String criteria) { this(found, verdict, why, model, criteria, Instant.now().toString()); }
        public boolean yes() { return verdict == Verdict.YES; }
        /** One line a person reads: the title, the verdict, the reason. */
        public String said() {
            String t = found.title() == null || found.title().isBlank() ? found.image() : "\"" + found.title() + "\"";
            return t + ": " + (verdict == Verdict.UNJUDGED ? "not judged" : verdict.word()) + (why == null || why.isBlank() ? "." : " — " + why);
        }
        /** The head lines a kept entry carries for it. */
        public Map<String, String> more() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("verdict", verdict.word()); if (why != null && !why.isBlank()) m.put("verdict_why", why); if (model != null && !model.isBlank()) m.put("verdict_model", model);
            if (criteria != null && !criteria.isBlank()) m.put("criteria", criteria); m.put("judged", judged);
            return m;
        }
        public ObjectNode json(ObjectMapper m) {
            ObjectNode o = found.json(m);
            o.put("verdict", verdict.word()); o.put("why", why == null ? "" : why); o.put("model", model == null ? "" : model); o.put("criteria", criteria == null ? "" : criteria); o.put("judged", judged); o.put("said", said());
            return o;
        }
    }

    /** A picture kept on the shelves: its entry, the file under the library and the address the pages serve it at. */
    public record Kept(Path raw, String id, String file, String url, Found found) { }

    // ---- the seams ----

    /** One engine: the candidates for words, best first, at most {@code count}; an empty list when it cannot answer. */
    @FunctionalInterface public interface Searcher { List<Found> search(String words, int count) throws Exception; }
    public record Engine(String name, Searcher searcher) { }

    /** The engines, in the order their results are merged; a test stands in two that read saved answers. */
    static volatile Supplier<List<Engine>> ENGINES = Images::liveEngines;

    /** A picture fetched: its bytes and the content type the server named ("" when it named none). */
    public record Fetched(byte[] bytes, String contentType) { }
    @FunctionalInterface public interface Fetcher { Fetched fetch(String url) throws Exception; }
    /** How a picture's bytes are fetched; a test stands in one that serves bytes of its own. */
    static volatile Fetcher FETCHER = Images::fetchNow;

    /** The drive for the seeing model; the same seam the probe uses, so one stand-in serves both. */
    static BiFunction<String, String, Researcher.Drive> drives() { return Vision.DRIVES; }

    /**
     * The seam for the third use, searching from a picture the person gives: the candidates of word searches ranked by their likeness to
     * the given picture, by an image embedding, since no open reverse-image search is at hand. Nothing stands behind it in this increment;
     * {@link #alike} says so.
     */
    @FunctionalInterface public interface Likeness { List<Found> alike(byte[] reference, String contentType, List<Found> candidates) throws Exception; }
    static volatile Likeness LIKENESS = null;
    public static final String NO_LIKENESS = "No likeness helper is set up: the library finds pictures by words, keeps them and reads them with the seeing model; researchzosho images likeness install sets the helper up, and the candidates are then measured against your picture first.";
    public static boolean likenessAvailable() { return LikenessHelper.scorer() != null; }
    public static List<Found> alike(byte[] reference, String contentType, List<Found> candidates) throws Exception {
        Likeness l = LIKENESS;
        if (l == null) throw new UnsupportedOperationException(NO_LIKENESS);
        return l.alike(reference, contentType, candidates);
    }

    // ---- search ----

    /**
     * What a search gave: the merged candidates, the engines that answered and the ones that did not (with why, in a few words), and how
     * many answers were skipped as not pictures the model can look at (an SVG, a PDF: the model server answered HTTP 400 to four of eight once, 2026-11-08).
     */
    public record Result(List<Found> found, List<String> answered, List<String> silent, int skipped) {
        public Result(List<Found> found, List<String> answered, List<String> silent) { this(found, answered, silent, 0); }
        /** "5 pictures found through Brave and SearXNG" / "No picture was found: Brave (no key kept), SearXNG (did not answer)"; the skipped said after. */
        public String said(String words) {
            String through = answered.isEmpty() ? "" : " through " + String.join(" and ", answered);
            String skip = skipped == 0 ? "" : " " + skipped + (skipped == 1 ? " candidate was" : " candidates were") + " skipped as not " + (skipped == 1 ? "a picture" : "pictures") + " the model can look at (an SVG, a PDF and the like).";
            if (found.isEmpty()) return "No picture was found for \"" + words + "\"" + through + (silent.isEmpty() ? "" : "; " + String.join(", ", silent)) + "." + skip;
            return found.size() + (found.size() == 1 ? " picture" : " pictures") + " found for \"" + words + "\"" + through + (silent.isEmpty() ? "" : " (" + String.join(", ", silent) + ")") + "." + skip;
        }
    }

    /** The picture files the model can look at, by their extension, and the files it cannot; an address with neither is fetched and judged by its bytes. */
    static final Pattern RASTER = Pattern.compile("(?i)\\.(jpe?g|png|gif|webp|bmp|tiff?|avif)$"), NOT_RASTER = Pattern.compile("(?i)\\.(svgz?|pdf|ico|eps|ai|psd|heic|heif|mp4|webm|mov|mkv|html?|js|css|json|xml|txt)$");

    /** Whether an address names, by its extension, a file the model cannot look at (the path alone, the query string aside). */
    static boolean notRaster(String url) {
        if (url == null) return false;
        String path = url.replaceAll("[?#].*$", "");
        return NOT_RASTER.matcher(path).find() && !RASTER.matcher(path).find();
    }

    /** Whether fetched bytes are a picture the model can look at. */
    static boolean looksAtable(Fetched got) { return got != null && got.bytes() != null && extension(got.contentType(), got.bytes()) != null; }
    /** "not a picture the model can look at (image/svg+xml)". */
    static String notLookable(Fetched got) { return "not a picture the model can look at (" + (got == null || got.contentType() == null || got.contentType().isBlank() ? "no type named" : got.contentType().split(";")[0].strip()) + ")"; }

    /** The candidates for words: every engine asked, merged rank by rank and told apart, at most {@code count}. */
    public static List<Found> search(LibraryStore store, String words, int count) { return find(store, words, count).found(); }

    /** The engine's name for the pictures already on the shelves, which answer a search first when the library has any for the words. */
    public static final String SHELVES = "the shelves";

    /**
     * The same, with which engines answered. The shelves come first: a picture the library already keeps whose title or description
     * carries every word of the search stands before anything the web engines give ({@link #shelves}); it is silent, never named,
     * when it has none.
     */
    public static Result find(LibraryStore store, String words, int count) {
        int n = Math.min(Math.max(count, 1), MOST);
        List<List<Found>> ranked = new ArrayList<>(); List<String> answered = new ArrayList<>(), silent = new ArrayList<>();
        if (store != null) {
            List<Found> held = shelves(store, words, n);
            if (!held.isEmpty()) { ranked.add(held); answered.add(SHELVES); }
        }
        for (Engine e : ENGINES.get()) {
            try {
                List<Found> r = e.searcher().search(words, n);
                if (r == null) { silent.add(e.name() + " (not set up)"); continue; }
                if (r.isEmpty()) { silent.add(e.name() + " (nothing found)"); continue; }
                ranked.add(r); answered.add(e.name());
            } catch (Stopping.Requested stop) { throw stop; }
            catch (Exception x) { silent.add(e.name() + " (did not answer" + (x.getMessage() == null || x.getMessage().isBlank() ? "" : ": " + cut(x.getMessage(), 80)) + ")"); }
        }
        int skipped = 0;   // an SVG, a PDF: not a candidate, said as skipped
        List<List<Found>> lookable = new ArrayList<>();
        for (List<Found> l : ranked) { List<Found> keep = new ArrayList<>(); for (Found f : l) { if (notRaster(f.image())) skipped++; else keep.add(f); } lookable.add(keep); }
        List<Found> merged = merge(lookable, n);
        SourceRules rules = SourceRules.live();
        merged.removeIf(f -> (f.page() != null && !f.page().isBlank() && rules.refused(f.page())) || rules.refused(f.image()));   // the person's own refused sources, never shown
        return new Result(merged, answered, silent, skipped);
    }

    /** Rank by rank across the engines (the first engine's first, the second's first, the first's second…), each picture once. */
    static List<Found> merge(List<List<Found>> ranked, int count) {
        List<Found> out = new ArrayList<>(); Set<String> seen = new LinkedHashSet<>();
        int longest = 0; for (List<Found> l : ranked) longest = Math.max(longest, l.size());
        for (int i = 0; i < longest && out.size() < count; i++) {
            for (List<Found> l : ranked) {
                if (i >= l.size() || out.size() >= count) continue;
                Found f = l.get(i);
                if (f.image() == null || f.image().isBlank() || !(f.image().matches("(?i)^https?://.+") || f.image().startsWith("/images/"))) continue;   // a web address, or a picture the shelves hold
                if (!seen.add("i:" + imageKey(f.image()))) continue;
                String pt = "p:" + key(f.page()) + "|" + (f.title() == null ? "" : f.title().strip().toLowerCase(Locale.ROOT));
                if (!f.page().isBlank() && !f.title().isBlank() && !seen.add(pt)) continue;
                out.add(f);
            }
        }
        return out;
    }

    /** An address as the dedup sees it: lower-case scheme and host, no fragment, no trailing slash. */
    static String key(String url) {
        if (url == null) return "";
        String u = url.strip();
        int hash = u.indexOf('#'); if (hash >= 0) u = u.substring(0, hash);
        Matcher m = Pattern.compile("^(https?://[^/]+)(.*)$", Pattern.CASE_INSENSITIVE).matcher(u);
        if (m.matches()) u = m.group(1).toLowerCase(Locale.ROOT) + m.group(2);
        return u.replaceAll("/+$", "");
    }

    /**
     * A picture's address as the dedup sees it: {@link #key}, and without its query string when the path names a picture file (a size or a
     * tracking string after {@code .jpg} is the same picture — one coin came through two engines, once with a utm string, and was judged and
     * kept twice, 2026-11-08); a path with no file extension keeps its query, which may be the picture's own name.
     */
    static String imageKey(String url) {
        String k = key(url);
        int q = k.indexOf('?');
        if (q < 0) return k;
        String path = k.substring(0, q);
        return path.matches("(?i).*\\.(jpe?g|png|gif|webp|bmp|tiff?|avif|svg)$") ? path : k;
    }

    static List<Engine> liveEngines() { return List.of(new Engine("Brave", Images::brave), new Engine("SearXNG", Images::searx)); }

    /** Brave's image search, when its key is kept and the month's limit allows; null when it is not set up, empty when it found nothing. */
    static List<Found> brave(String words, int count) throws Exception {
        String key = Config.get("RESEARCHZOSHO_BRAVE_KEY");
        if (key == null || key.isBlank()) return null;
        if (!SearchTally.reserve(WebSearchTool.BRAVE)) throw new IOException(SearchTally.usedUp(WebSearchTool.BRAVE, WebSearchTool.BRAVE));
        boolean made = false;
        try {
            HttpResponse<String> resp = Stopping.send(HTTP, HttpRequest.newBuilder(URI.create(braveUrl(words, count))).timeout(SEARCH_TIMEOUT)
                    .header("Accept", "application/json").header("X-Subscription-Token", key.strip()).GET().build(), HttpResponse.BodyHandlers.ofString(), SEARCH_TIMEOUT, "Brave's image search");
            if (resp.statusCode() != 200) throw new IOException("Brave answered HTTP " + resp.statusCode());
            made = true;
            return parseBrave(resp.body());
        } finally { SearchTally.settle(WebSearchTool.BRAVE, made, 0, 0); }
    }

    /** Brave's address for an image search: strict safe search, the query's language when its script says. */
    static String braveUrl(String words, int count) {
        return WebSearchTool.BRAVE_API.get("images") + "?count=" + Math.min(Math.max(count, 1), 50) + "&safesearch=strict&q=" + URLEncoder.encode(words, StandardCharsets.UTF_8) + WebSearchTool.braveLang(WebSearchTool.languageOf(words));
    }

    /** Brave's image results: {@code results[] {title, url (the page), source, thumbnail {src, width, height}, properties {url (the picture), width, height}}}. */
    static List<Found> parseBrave(String json) throws IOException {
        List<Found> out = new ArrayList<>();
        for (JsonNode r : M.readTree(json).path("results")) {
            String image = r.path("properties").path("url").asText("");
            if (image.isBlank()) continue;
            out.add(new Found(image, r.path("url").asText(""), r.path("title").asText(""), "Brave", r.path("properties").path("width").asInt(0), r.path("properties").path("height").asInt(0), r.path("thumbnail").path("src").asText("")));
        }
        return out;
    }

    /** The library's SearXNG with images as the category; empty when it found nothing; an exception when it does not answer. */
    static List<Found> searx(String words, int count) throws Exception {
        HttpResponse<String> resp = Stopping.send(HTTP, HttpRequest.newBuilder(URI.create(searxUrl(words))).timeout(SEARCH_TIMEOUT).header("Accept", "application/json").GET().build(),
                HttpResponse.BodyHandlers.ofString(), SEARCH_TIMEOUT, "the search service at " + WebSearchTool.endpoint());
        if (resp.statusCode() != 200) throw new IOException("the search service answered HTTP " + resp.statusCode());
        List<Found> all = parseSearx(resp.body());
        return all.size() > count ? new ArrayList<>(all.subList(0, count)) : all;
    }

    static String searxUrl(String words) {
        String lang = WebSearchTool.languageOf(words);
        return WebSearchTool.endpoint() + "/search?format=json&categories=images&q=" + URLEncoder.encode(words, StandardCharsets.UTF_8) + (lang == null ? "" : "&language=" + lang) + "&safesearch=1";
    }

    /** SearXNG's image results: {@code results[] {url (the page), title, img_src, thumbnail_src | thumbnail, engine, resolution}}. */
    static List<Found> parseSearx(String json) throws IOException {
        List<Found> out = new ArrayList<>();
        for (JsonNode r : M.readTree(json).path("results")) {
            String image = r.path("img_src").asText("");
            if (image.isBlank()) continue;
            if (image.startsWith("//")) image = "https:" + image;
            String thumb = r.path("thumbnail_src").asText(""); if (thumb.isBlank()) thumb = r.path("thumbnail").asText("");
            if (thumb.startsWith("//")) thumb = "https:" + thumb;
            int[] wh = resolution(r.path("resolution").asText(""));
            String engine = r.path("engine").asText("");
            out.add(new Found(image, r.path("url").asText(""), r.path("title").asText(""), engine.isBlank() ? "SearXNG" : "SearXNG (" + engine + ")", wh[0], wh[1], thumb, Instant.now().toString(), r.path("content").asText("")));   // content: the engine's caption, when it gives one
        }
        return out;
    }

    /** "3805 x 2750", "2664×2131" → {w, h}; {0, 0} when the engine said nothing readable. */
    static int[] resolution(String s) {
        Matcher m = Pattern.compile("(\\d{2,5})\\s*[x×X*]\\s*(\\d{2,5})").matcher(s == null ? "" : s);
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : new int[]{0, 0};
    }

    // ---- the shelves ----

    /** The engine's name on a picture kept from a document the person read in, and on one the person gave (attached in the chat, named at the command line, sent over the protocol). */
    public static final String DOCUMENT = "document", GIVEN = "the person";

    /**
     * A picture on the shelves: its entry, its file, the address the pages serve it at, its title, the page it came from (a web page, or
     * the document it was read out of, by that document's locator), the engine that found it ({@link #DOCUMENT} for one read out of a
     * document), when it was found, its verdict when it was judged, the description the seeing model gave it ("" for none), and its size.
     */
    public record Shelved(Path raw, String id, String file, String url, String title, String page, String source, String found, Verdict verdict, String why, String criteria, String description, int width, int height, String hash, String locator, String caption) {
        /**
         * As a candidate a search gives: the served address stands as the picture's, so a judge or a keep reads the library's own copy; the
         * caption is the engine's own, kept at keep time — never the library's description, the search words or the criteria, which are our
         * words about the picture and not the picture's own text (the naming that outranks the face reading reads the picture's own text only).
         */
        public Found asFound() { return new Found(url, page, title, SHELVES, width, height, url, found, caption == null ? "" : caption); }
        /**
         * As the entry is cited: its own locator (the picture's address on the web, or the document's and its path inside it) as the address,
         * its page and title, the served address as the small copy — the reference a run writes for it is then the one a fresh picture gets,
         * and a note's source resolves to the entry (a shelf picture was cited as "[1] /images/…" on a live run, 2026-11-08).
         */
        public Found cited(Found came) {
            String t = title == null || title.isBlank() ? (came == null ? "" : came.title()) : title;
            String p = page == null || page.isBlank() ? (came == null ? "" : came.page()) : page;
            return new Found(locator == null || locator.isBlank() ? url : locator, p, t, came == null || came.source() == null || came.source().isBlank() ? source : came.source(), width, height, url, found, caption == null ? "" : caption);
        }
        /** "a picture read out of a document" / "given by you" / "found through Brave". */
        public String origin() { return DOCUMENT.equals(source) ? "read out of a document" : GIVEN.equals(source) ? "given by you" : source == null || source.isBlank() ? "kept" : "found through " + source; }
    }

    /** Every picture on the shelves, newest first. A scan of the entries' heads, as the lookup of a locator is. */
    public static List<Shelved> onShelves(LibraryStore store) {
        List<Shelved> out = new ArrayList<>();
        if (store == null || !Files.isDirectory(store.rawDir())) return out;
        List<Path> files;
        try (var s = Files.list(store.rawDir())) { files = s.filter(p -> p.getFileName().toString().endsWith(".md")).sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString())).toList(); }
        catch (IOException e) { return out; }
        for (Path p : files) {
            if (!KIND.equals(RawCapture.field(p, "kind"))) continue;
            Shelved s = shelved(store, p);
            if (s != null) out.add(s);
        }
        return out;
    }

    /** One kept picture read from its entry; null when the entry is not a kept picture or its file is gone. */
    static Shelved shelved(LibraryStore store, Path raw) {
        try {
            String rel = RawCapture.field(raw, "image");
            if (rel.isEmpty()) return null;
            String file = rel.substring(rel.lastIndexOf('/') + 1);
            if (kept(store, file) == null) return null;
            String[] parts = RawCapture.read(raw);
            String body = parts[2] == null ? "" : parts[2].strip();
            int cut = body.indexOf("\n\n![");
            String description = cut >= 0 ? body.substring(0, cut).strip() : body.startsWith("![") ? "" : body;
            if (description.equals(parts[1].strip())) description = "";   // the title stood in for a description
            int w = 0, h = 0;
            try { w = Integer.parseInt(RawCapture.field(raw, "width")); h = Integer.parseInt(RawCapture.field(raw, "height")); } catch (NumberFormatException ignored) { }
            return new Shelved(raw, raw.getFileName().toString(), rel, "/images/" + file, parts[1], RawCapture.field(raw, "page"), RawCapture.field(raw, "source"), RawCapture.field(raw, "found"),
                    Verdict.of(RawCapture.field(raw, "verdict").isEmpty() ? "unjudged" : RawCapture.field(raw, "verdict")), RawCapture.field(raw, "verdict_why"), RawCapture.field(raw, "criteria"), description, w, h, RawCapture.field(raw, "hash"), parts[0], RawCapture.field(raw, "caption"));
        } catch (IOException e) { return null; }
    }

    /** The kept picture served at {@code /images/<file>}, by the file's name; null when no entry holds it. */
    public static Shelved entryFor(LibraryStore store, String file) {
        String name = file == null ? "" : file.strip();
        if (name.startsWith("/images/")) name = name.substring("/images/".length());
        if (name.startsWith("catalog/images/")) name = name.substring("catalog/images/".length());
        for (Shelved s : onShelves(store)) if (s.file().endsWith("/" + name)) return s;
        return null;
    }

    /** The kept picture whose bytes have this SHA-256, newest first; null when the shelves hold none. The same bytes under another address are one picture. */
    public static Shelved byHash(LibraryStore store, String sha) {
        if (sha == null || sha.isBlank()) return null;
        for (Shelved s : onShelves(store)) if (sha.equals(s.hash())) return s;
        return null;
    }

    /** The shelves' pictures whose title or description carries every word of the search, newest first, at most {@code count}. */
    public static List<Found> shelves(LibraryStore store, String words, int count) {
        List<String> terms = new ArrayList<>();
        for (String w : (words == null ? "" : words).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) if (w.length() >= 2) terms.add(w);
        List<Found> out = new ArrayList<>();
        if (terms.isEmpty()) return out;
        for (Shelved s : onShelves(store)) {
            String hay = (s.title() + " " + s.description() + " " + s.criteria()).toLowerCase(Locale.ROOT);
            boolean all = true; for (String t : terms) if (!hay.contains(t)) { all = false; break; }
            if (all) out.add(s.asFound());
            if (out.size() >= count) break;
        }
        return out;
    }

    // ---- keep ----

    /** Where kept pictures lie: catalog/images/ under the library. */
    static Path imagesDir(LibraryStore store) { return store.root().resolve("catalog").resolve("images"); }

    /** A kept picture by its file name, or null when the name is not one of catalog/images/'s files. */
    public static Path kept(LibraryStore store, String name) {
        Path p = LibraryStore.under(imagesDir(store), name == null ? "" : name.strip());
        return p != null && Files.isRegularFile(p) ? p : null;
    }

    /** The content type of a kept file, by its extension. */
    public static String contentType(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".tif") || n.endsWith(".tiff")) return "image/tiff";
        if (n.endsWith(".avif")) return "image/avif";
        return "application/octet-stream";
    }

    /** Whether a file on this machine is a picture the library keeps, by what it begins with. */
    public static boolean isPictureFile(Path p) {
        try { if (p == null || !Files.isRegularFile(p) || Files.size(p) > MAX_BYTES) return false; byte[] head = new byte[16]; try (var in = Files.newInputStream(p)) { int n = in.read(head); if (n < 12) return false; } return extension("", head) != null; }
        catch (IOException e) { return false; }
    }

    /** The extension for a picture's bytes, from what they begin with first and the server's type second; null when they are not a picture the library keeps. */
    static String extension(String contentType, byte[] b) {
        if (b != null && b.length >= 12) {
            if ((b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8) return "jpg";
            if ((b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "png";
            if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "gif";
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "webp";
            if (b[0] == 'B' && b[1] == 'M') return "bmp";
            if ((b[0] == 'I' && b[1] == 'I' && b[2] == 42 && b[3] == 0) || (b[0] == 'M' && b[1] == 'M' && b[2] == 0 && b[3] == 42)) return "tif";
            if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p' && b[8] == 'a' && b[9] == 'v' && b[10] == 'i' && b[11] == 'f') return "avif";
        }
        String t = contentType == null ? "" : contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        return switch (t) { case "image/jpeg", "image/jpg" -> "jpg"; case "image/png" -> "png"; case "image/gif" -> "gif"; case "image/webp" -> "webp"; case "image/bmp" -> "bmp"; case "image/tiff" -> "tif"; case "image/avif" -> "avif"; default -> null; };
    }

    /** A picture's width and height read from its header, without decoding it; {0, 0} when the format gives none. */
    static int[] dimensions(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) return new int[]{0, 0};
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return new int[]{0, 0};
            ImageReader r = readers.next();
            try { r.setInput(in); return new int[]{r.getWidth(0), r.getHeight(0)}; } finally { r.dispose(); }
        } catch (Exception e) { return new int[]{0, 0}; }
    }

    /** The live fetch: the address rules every fetch has, the person's refused list, the site list; the body capped by the fetch's own limit. */
    static Fetched fetchNow(String url) throws Exception {
        Fetch.Result r = Fetch.get(url, FETCH_TIMEOUT, Map.of("Accept", "image/*,*/*;q=0.5"), Fetch.Policy.DEFAULT);
        if (r.status() != 200) throw new IOException("the server answered HTTP " + r.status());
        return new Fetched(r.body(), r.contentType());
    }

    /** The one question every picture from the web is asked before it is kept, on the seeing model; a test stands in the answer. */
    static volatile BiFunction<ModelEntries.Entry, Fetched, ContentJudge.Reading> CHECK = (e, got) -> ContentJudge.of(new DriveClient(e.drive(), e.model()).decisionTimeout(ContentJudge.REQUEST_TIMEOUT)).askImage(Vision.dataUrl(got.bytes(), got.contentType()), ImageText.CHILD_QUESTION);

    /** A candidate kept on the shelves: fetched, checked, written under catalog/images/ and given an entry; {@code description} the body (the title when blank). */
    public static Kept keep(LibraryStore store, Found f, String description, String collection) throws IOException { return keep(store, f, description, collection, null); }

    /** The same, with the verdict it was judged by, kept in the entry's head. */
    public static Kept keep(LibraryStore store, Found f, String description, String collection, Judged judged) throws IOException {
        if (f != null && f.image() != null && f.image().startsWith("/images/")) {   // a picture the shelves already hold: its entry stands, nothing is fetched or written again
            Shelved s = entryFor(store, f.image());
            if (s == null) throw new IOException("No picture is kept at " + f.image() + ".");
            return new Kept(s.raw(), s.id(), s.file(), s.url(), s.cited(f));
        }
        if (f == null || f.image() == null || !f.image().matches("(?i)^https?://.+")) throw new IOException("A picture is kept by its web address; this candidate has none.");
        Fetched got;
        try { got = FETCHER.fetch(f.image()); }
        catch (Stopping.Requested stop) { throw stop; }
        catch (Exception e) { throw new IOException("The picture at " + f.image() + " could not be fetched: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "."); }
        return keep(store, f, got, description, collection, judged);
    }

    /** A candidate's bytes: a picture the shelves hold ({@code /images/<file>}) from the library's copy, else fetched; the fetch's own limits apply. */
    public static Fetched fetch(LibraryStore store, Found f) throws IOException {
        if (f == null || f.image() == null || f.image().isBlank()) throw new IOException("No picture was named.");
        Fetched got;
        if (store != null && !f.image().matches("(?i)^https?://.+")) return bytesOf(store, f.image(), false);
        try { got = FETCHER.fetch(f.image()); }
        catch (Stopping.Requested stop) { throw stop; }
        catch (Exception e) { throw new IOException("the picture could not be fetched: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); }
        if (got == null || got.bytes() == null || got.bytes().length == 0) throw new IOException("nothing came back");
        if (got.bytes().length > MAX_BYTES) throw new IOException("it weighs more than 8 MB");
        return got;
    }

    /**
     * The same, for a candidate already fetched: a run judges a picture on its bytes and keeps it on the same bytes. A picture whose
     * SHA-256 is already on the shelves is the existing entry, whatever address it came by this time: nothing is written or checked again.
     */
    public static Kept keep(LibraryStore store, Found f, Fetched got, String description, String collection, Judged judged) throws IOException {
        if (got == null || got.bytes() == null || got.bytes().length == 0) throw new IOException("Nothing came back from " + f.image() + ".");
        if (got.bytes().length > MAX_BYTES) throw new IOException("The picture at " + f.image() + " weighs " + String.format(Locale.ROOT, "%.1f", got.bytes().length / 1048576.0) + " MB; the library keeps pictures up to 8 MB.");
        String ext = extension(got.contentType(), got.bytes());
        if (ext == null) throw new IOException("What is at " + f.image() + " is not a picture the library keeps (" + (got.contentType() == null || got.contentType().isBlank() ? "no type named" : got.contentType()) + "): JPEG, PNG, GIF, WebP, BMP or TIFF.");
        Kept same = existing(store, got.bytes(), f);
        if (same != null) return same;
        ModelEntries.Entry eyes = ModelEntries.seeing();
        if (eyes != null) {
            ContentJudge.Reading check;
            try { check = CHECK.apply(eyes, got); } catch (Stopping.Requested stop) { throw stop; } catch (RuntimeException e) { check = null; }
            if (check != null && check.leansYes()) throw new IOException("The picture at " + f.image() + " was not kept: the check every picture from the web goes through before it is kept said no.");
        }
        return write(store, got.bytes(), ext, titled(f, judged), f.image(), description, collection, judged, Map.of());
    }

    /**
     * The candidate with a title when the engine gave none: what it was judged to show ("a diagram of the gear train", the criteria's own
     * entity), else its page's host — never the file name, which a live report showed as the alt text (J-0014, 2026-11-08). A judgement
     * beside a reference ("the same person as …") names nothing a reader wants as a title, so the host stands in then.
     */
    static Found titled(Found f, Judged judged) {
        if (f.title() != null && !f.title().isBlank()) return f;
        String alt = judged == null || judged.criteria() == null ? "" : RunPictures.entityOf(judged.criteria());
        if (alt.startsWith("the same ") || alt.startsWith("like the picture") || alt.startsWith("something like this")) alt = "";
        if (alt.isEmpty()) alt = f.host();
        if (alt.isEmpty()) return f;
        return new Found(f.image(), f.page(), alt, f.source(), f.width(), f.height(), f.thumbnail(), f.found(), f.caption());
    }

    /** The entry already holding these bytes, as a Kept of this candidate (its size read from the bytes when the engine gave none); null when the shelves hold none. */
    static Kept existing(LibraryStore store, byte[] bytes, Found f) {
        Shelved same = byHash(store, sha256(bytes));
        if (same == null) return null;
        return new Kept(same.raw(), same.id(), same.file(), same.url(), same.cited(f));   // cited by the entry's own address, whatever address it came by this time
    }

    /** The entry's own locator for a picture served at {@code /images/<file>} (a note may name the served address); null when none holds it. */
    public static String locatorOf(LibraryStore store, String served) {
        Shelved s = entryFor(store, served);
        return s == null || s.locator() == null || s.locator().isBlank() ? null : s.locator();
    }

    /**
     * A picture that came as bytes, not from the web: one read out of a document the person added. {@code locator} names it uniquely
     * (the document's locator and the picture's path inside it); {@code page} is the document's locator, so the entry says which
     * document it was read out of; the engine is {@link #DOCUMENT}. The check every web picture goes through is not asked of it: the
     * person chose the document. Null when the bytes are not a picture the library keeps.
     */
    public static Kept keepBytes(LibraryStore store, byte[] bytes, String contentType, String title, String description, String locator, String page, String collection, Map<String, String> more) throws IOException {
        return keepBytes(store, bytes, contentType, title, description, locator, page, collection, more, DOCUMENT);
    }

    /** The same, with the engine's name: {@link #DOCUMENT} for a document's picture, {@link #GIVEN} for one the person gave. */
    public static Kept keepBytes(LibraryStore store, byte[] bytes, String contentType, String title, String description, String locator, String page, String collection, Map<String, String> more, String source) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return null;
        String ext = extension(contentType, bytes);
        if (ext == null) return null;
        Found f = new Found(locator, page, title, source, 0, 0, "");
        Kept same = existing(store, bytes, f);
        if (same != null) return same;
        return write(store, bytes, ext, f, locator, description, collection, null, more == null ? Map.of() : more);
    }

    /**
     * A picture the person gave — attached in the chat, named at the command line, sent over the protocol — kept on the shelves as a
     * picture of the person's own ({@link #GIVEN}), so a later ask can cite it by its entry; its locator is its bytes' hash, so the same
     * picture given twice is one entry. Null when the bytes are not a picture the library keeps.
     */
    public static Kept keepGiven(LibraryStore store, byte[] bytes, String contentType, String name, String description, String collection) throws IOException {
        String title = name == null || name.isBlank() ? "A picture you gave" : name.strip();
        return keepBytes(store, bytes, contentType, title, description, "given:" + sha256(bytes), "", collection, Map.of(), GIVEN);
    }

    /** The bytes under catalog/images/ by their hash, and the entry on the shelves with its head; the one writing every kept picture goes through. */
    private static Kept write(LibraryStore store, byte[] bytes, String ext, Found f, String locator, String description, String collection, Judged judged, Map<String, String> extra) throws IOException {
        String sha = sha256(bytes), name = sha.substring(0, 16) + "." + ext;
        Path dir = imagesDir(store); Files.createDirectories(dir);
        Path file = dir.resolve(name);
        if (!Files.exists(file)) AtomicWrite.bytes(file, bytes);
        int[] wh = f.width() > 0 && f.height() > 0 ? new int[]{f.width(), f.height()} : dimensions(bytes);
        Found kept = f.withSize(wh[0], wh[1]);
        String title = f.title() == null || f.title().isBlank() ? name : f.title().strip();
        boolean document = DOCUMENT.equals(f.source()), given = GIVEN.equals(f.source());
        String body = (description == null || description.isBlank() ? title : description.strip()) + "\n\n![" + title.replace("]", ")") + "](/images/" + name + ")\n\n"
                + (document ? "Read out of the document " + f.page() : given ? "Given by the person who keeps this library, as a reference" : "From " + (f.page() == null || f.page().isBlank() ? f.image() : f.page()) + (f.source() == null || f.source().isBlank() ? "" : ", found through " + f.source()))
                + (judged == null ? "" : "; judged against \"" + judged.criteria() + "\": " + judged.verdict().word() + (judged.why().isBlank() ? "" : " — " + judged.why())) + ".";
        Map<String, String> more = new LinkedHashMap<>();
        more.put("kind", KIND); more.put("image", "catalog/images/" + name); more.put("page", f.page()); more.put("source", f.source()); more.put("found", f.found());
        if (wh[0] > 0 && wh[1] > 0) { more.put("width", Integer.toString(wh[0])); more.put("height", Integer.toString(wh[1])); }
        more.put("bytes", Integer.toString(bytes.length)); more.put("hash", sha);   // a head line's name is letters and underscores: "sha256" would be dropped unsaid
        if (f.caption() != null && !f.caption().isBlank()) more.put("caption", cut(f.caption(), 500));   // the engine's own words about the picture, kept apart from ours
        if (judged != null) more.putAll(judged.more());
        more.putAll(extra);
        Path raw = RawCapture.capture(store, locator, body, title, BY, collection, "", more);
        if (raw == null) throw new IOException("The picture was fetched, but its entry could not be written on the shelves.");
        return new Kept(raw, raw.getFileName().toString(), "catalog/images/" + name, "/images/" + name, kept);
    }

    /** SHA-256 of bytes, hex. */
    static String sha256(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /**
     * A picture named in a request, as bytes: a web address is fetched; a kept file by its name, its served address ({@code /images/<file>})
     * or its entry on the shelves is read from the library; a path on this machine is read when {@code paths} says the caller may name one.
     */
    public static Fetched bytesOf(LibraryStore store, String ref, boolean paths) throws IOException {
        String r = ref == null ? "" : ref.strip();
        if (r.isEmpty()) throw new IOException("No picture was named.");
        if (r.matches("(?i)^https?://.+")) {
            try { Fetched got = FETCHER.fetch(r); if (got == null || got.bytes() == null || got.bytes().length == 0) throw new IOException("nothing came back"); if (got.bytes().length > MAX_BYTES) throw new IOException("it weighs more than 8 MB"); return got; }
            catch (Stopping.Requested stop) { throw stop; }
            catch (Exception e) { throw new IOException("The picture at " + r + " could not be fetched: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + "."); }
        }
        String name = r.startsWith("/images/") ? r.substring("/images/".length()) : r;
        Path kept = kept(store, name);
        if (kept == null) {
            Path raw = RawCapture.find(store, r);
            if (raw != null) {
                String rel = RawCapture.field(raw, "image");   // catalog/images/<file>: the file's name is what the images folder is asked for
                Path p = rel.isEmpty() ? null : kept(store, rel.substring(rel.lastIndexOf('/') + 1));
                if (p != null) kept = p;
                else throw new IOException("The entry " + r + " is not a kept picture" + (rel.isEmpty() ? "." : ": its file " + rel + " is gone."));
            }
        }
        if (kept != null) return new Fetched(Files.readAllBytes(kept), contentType(kept.getFileName().toString()));
        if (paths) {
            Path p = Path.of(r.replaceFirst("^~", System.getProperty("user.home"))).toAbsolutePath().normalize();
            if (!Files.isRegularFile(p)) throw new IOException("Nothing is at " + p + ".");
            if (Files.size(p) > MAX_BYTES) throw new IOException(p.getFileName() + " weighs more than 8 MB; the library reads pictures up to 8 MB.");
            byte[] b = Files.readAllBytes(p);
            if (extension("", b) == null && !ImageText.isImage(b)) throw new IOException(p.getFileName() + " is not a picture (JPEG, PNG, GIF, WebP, BMP or TIFF).");
            return new Fetched(b, contentType(p.getFileName().toString()));
        }
        throw new IOException("No picture is " + r + ": give a web address, a kept picture's name or its entry on the shelves.");
    }

    // ---- seeing ----

    /** What the seeing model is asked when it describes a picture: words a later search can be built from. */
    public static final String DESCRIBE = "Describe this picture for a research library in a few plain sentences: what it shows; the people in it, named when you know them; the place; the time or period it appears to show; any writing in it, quoted. Use the names things are known by, so that a later search by words can find them. Where you are not sure, say so.";

    /** The seeing model's words about a picture; {@code question} adds what the person wants to know, "" for the plain description. "" with nothing answered. */
    public static String describe(ModelEntries.Entry eyes, byte[] bytes, String contentType, String question) {
        String ask = DESCRIBE + (question == null || question.isBlank() ? "" : " Then answer this: " + question.strip());
        String said = see(eyes, bytes, contentType, ask, 600, true);
        return said == null ? "" : said.strip();
    }

    /** Every candidate judged against the description, one call each; a picture that cannot be fetched is unjudged with the reason. */
    public static List<Judged> judge(ModelEntries.Entry eyes, String criteria, List<Found> images) { return judge(null, eyes, criteria, images); }

    /** The same, reading a candidate the shelves hold ({@code /images/<file>}) from the library's own copy when a store is given. */
    public static List<Judged> judge(LibraryStore store, ModelEntries.Entry eyes, String criteria, List<Found> images) { return judge(store, eyes, criteria, images, false); }

    /** The same; {@code strict} asks as a research run asks ({@link #SHOWS_RULE}). */
    public static List<Judged> judge(LibraryStore store, ModelEntries.Entry eyes, String criteria, List<Found> images, boolean strict) {
        List<Judged> out = new ArrayList<>();
        for (Found f : images) {
            Fetched got;
            try { got = fetch(store, f); }
            catch (Stopping.Requested stop) { throw stop; }
            catch (Exception e) { out.add(new Judged(f, Verdict.UNJUDGED, e.getMessage() == null ? "the picture could not be fetched" : e.getMessage().startsWith("the picture") ? e.getMessage() : "the picture could not be fetched: " + e.getMessage(), eyes.model(), criteria)); continue; }
            if (!looksAtable(got)) { out.add(new Judged(f, Verdict.UNJUDGED, notLookable(got), eyes.model(), criteria)); continue; }
            out.add(judge(eyes, criteria, f, got.bytes(), got.contentType(), strict));
        }
        return out;
    }

    /**
     * What a research run adds to the question (0.7.0): a yes is the thing itself, not its culture or period — on a live run a coin from
     * Lykosura was judged to show Athens by its motif, and a page's label made a museum director a prime minister (2026-11-08) — and the
     * judge says what it relied on.
     */
    public static final String SHOWS_RULE = "A yes means the picture shows this itself — the person's own likeness, the object itself, the scene of the event — not something of the same culture, period or style; a guess from the style, the period or a motif is a no. "
            + "The page's title and a caption written in the picture are evidence you may rely on; say which you relied on: the page's title, the picture's own words, or what you see.";

    /** One picture judged against the description: "Does this picture show …? Answer: yes, no or unsure, then one sentence why." */
    public static Judged judge(ModelEntries.Entry eyes, String criteria, Found f, byte[] bytes, String contentType) { return judge(eyes, criteria, f, bytes, contentType, false); }

    /** The same; {@code strict} adds the page's title and {@link #SHOWS_RULE}, as a research run asks. */
    public static Judged judge(ModelEntries.Entry eyes, String criteria, Found f, byte[] bytes, String contentType, boolean strict) {
        String title = f.title() == null ? "" : f.title().strip().replaceAll("\\s+", " ");
        String ask = "Does this picture show " + criteria.strip().replaceAll("[.?!]+$", "") + "?"
                + (strict ? (title.isEmpty() ? "" : " The page it stands on is titled \"" + cut(title, 120) + "\".") + " " + SHOWS_RULE : "")
                + " Answer: yes, no or unsure, then one sentence why.";
        String said = see(eyes, bytes, contentType, ask, strict ? 160 : 120, false);
        Judged j = verdict(f, criteria, eyes.model(), said);
        if (unread(j, said)) {   // once more, told to begin with the word; only then not judged
            String said2 = see(eyes, bytes, contentType, ask + ONE_WORD, strict ? 160 : 120, false);
            Judged again = verdict(f, criteria, eyes.model(), said2);
            j = again.verdict() == Verdict.UNJUDGED ? noVerdictTwice(j, said2) : again;
        }
        return j;
    }

    private static final Pattern FIRST_WORD = Pattern.compile("^[^\\p{L}]*(\\p{L}+)");

    // ---- a picture as the ask ----

    /**
     * What the seeing model is asked when a picture is the reference for a search: what or who it shows, the words to search by, and for a
     * person the visible traits and never a guessed name — a name only when the picture itself carries one.
     */
    public static final String DESCRIBE_REFERENCE = "This picture is the reference for finding more pictures of what it shows. Say in plain words what or who it shows. For a person: the visible traits "
            + "(apparent age, hair, face, dress, build) and a name only if the picture itself carries one in writing or a caption — never a guessed name. For a place or an object: its kind and the features "
            + "that tell it from others. For a scene: what happens and where. End with one line that begins \"Search words:\" followed by three to eight words a picture search would use.";

    /** A picture that is the ask: its bytes, what the seeing model said of it, the words to search by, and its entry on the shelves when it is kept. */
    public record Reference(byte[] bytes, String contentType, String description, String searchWords, Kept kept) {
        /** The entry's id, "" when it is not kept. */
        public String id() { return kept == null ? "" : kept.id(); }
        /** The description without its "Search words:" line. */
        public String said() { return description == null ? "" : SEARCH_WORDS.matcher(description).replaceAll("").strip(); }
        public ObjectNode json(ObjectMapper m) {
            ObjectNode o = m.createObjectNode();
            o.put("id", id()); o.put("url", kept == null ? "" : kept.url()); o.put("title", kept == null ? "" : kept.found().title()); o.put("description", said()); o.put("search_words", searchWords == null ? "" : searchWords);
            return o;
        }
    }

    private static final Pattern SEARCH_WORDS = Pattern.compile("(?im)^\\s*\\**\\s*search words\\s*\\**\\s*:\\s*(.+?)\\s*$");

    /** The reference described by the seeing model, with the words to search by read off its last line (the first sentence's words when it gave none). */
    public static Reference describeReference(ModelEntries.Entry eyes, byte[] bytes, String contentType, Kept kept) {
        String said = see(eyes, bytes, contentType, DESCRIBE_REFERENCE, 500, true);
        String d = said == null ? "" : said.strip();
        Matcher m = SEARCH_WORDS.matcher(d);
        String words = m.find() ? m.group(1).replaceAll("[\"“”.]+$", "").replaceAll("[,;/|]+", " ").replaceAll("\\s+", " ").strip() : "";
        if (words.isEmpty() && !d.isEmpty()) {
            StringBuilder w = new StringBuilder();
            for (String t : d.split("[.!?\\n]")[0].split("[^\\p{L}\\p{N}'-]+")) { if (t.length() > 3 && !STOP.contains(t.toLowerCase(Locale.ROOT))) { if (w.length() > 0) w.append(' '); w.append(t); } if (w.toString().split(" ").length >= 6) break; }
            words = w.toString();
        }
        return new Reference(bytes, contentType, d, words, kept);
    }

    private static final Set<String> STOP = Set.of("this", "that", "with", "from", "picture", "photo", "image", "shows", "showing", "there", "which", "their", "about", "what", "some", "into", "over", "under", "have", "been", "were", "person", "photograph", "appears", "seems", "likely");

    /** The two ways a reference is used: the same thing as the reference, or like it in an aspect the words name. */
    public static final String SAME = "same", CONCEPT = "concept";

    /** What the judge is told when it sees the reference and a candidate together. */
    public static final String SAME_RULE = "Two pictures follow: the first is the reference, the second a candidate. Does the second picture show the SAME %s as the first? Compare what the pictures show — a person by face and build, an object by its shape and marks, a place by its features — allowing for age, pose, dress and the picture's quality. "
            + "A caption or a label written in the candidate, and its page's title, are evidence you may rely on too. Answer: yes when they are the same; no when they plainly are not; unsure when they could be the same person at another age or from another angle; then one sentence why, saying what you relied on: the faces, the writing in the picture, or the page's title. A likeness of style, period or setting alone is a no.";
    public static final String CONCEPT_RULE = "Two pictures follow: the first is the reference, the second a candidate. Is the second like the first in this: %s? Answer: yes, no or unsure, then one sentence why, naming what the two share or do not.";

    // ---- named by text: the deterministic signal, which outranks the model's face reading ----

    /** Where a candidate's own text names the person: its page's title, its address, the engine's caption; "none" when none does. */
    public static final String BY_TITLE = "title", BY_ADDRESS = "address", BY_CAPTION = "caption", BY_NONE = "none";

    /** Letters and digits alone, lower-cased, the diacritics folded off (Στάης is σταης, Émile is emile), every other character a space; Greek and Latin stay as given. */
    static String fold(String s) {
        if (s == null) return "";
        String d = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return " " + d.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip() + " ";
    }

    /** An address's path as words: percent-escapes undone, hyphens, underscores and dots as spaces. */
    static String addressWords(String url) {
        if (url == null) return "";
        String path = url.replaceAll("^[a-z]+://[^/]+", "").replaceAll("[?#].*$", "");
        try { path = URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8); } catch (RuntimeException ignored) { }
        return path.replace('/', ' ').replace('-', ' ').replace('_', ' ').replace('.', ' ');
    }

    /** Whether every word of the name stands as a whole word in the text, both folded. */
    static boolean namesIn(String text, String name) {
        String t = fold(text);
        if (t.isBlank()) return false;
        int words = 0;
        for (String w : fold(name).strip().split("\\s+")) { if (w.length() < 2) continue; words++; if (!t.contains(" " + w + " ")) return false; }
        return words > 0;
    }

    /**
     * Where the candidate's own text names the person: {@link #BY_TITLE} when its page's title does, else {@link #BY_ADDRESS} when its
     * picture's address does (the path's words: a live run's candidate was titled "Antikythera Mechanism - Doyle's Space" and named him only
     * in its file name, …/Antikythera-mechanism-archaeologist-Valerios-Stais.jpg, 2026-11-08), else {@link #BY_CAPTION} when the engine's
     * caption does; {@link #BY_NONE} otherwise, and for no name. Every word of the name must be there: a surname alone is not the name.
     */
    public static String namedBy(Found f, String name) {
        if (f == null || name == null || name.isBlank()) return BY_NONE;
        if (namesIn(f.title(), name)) return BY_TITLE;
        if (namesIn(addressWords(f.image()), name) || namesIn(addressWords(f.page()), name)) return BY_ADDRESS;
        if (namesIn(f.caption(), name)) return BY_CAPTION;
        return BY_NONE;
    }

    /** "its page title" / "its address" / "its caption". */
    static String namedWords(String by) { return BY_TITLE.equals(by) ? "its page title" : BY_ADDRESS.equals(by) ? "its address" : "its caption"; }

    /**
     * Whether a naming is the picture's own name: its page's title or its address say what the picture is; a caption (a Commons file
     * description, an engine's snippet) names everyone connected with it — a coin from Lykosoura carried the name of the man who dug there
     * and was ranked unsure against his portrait (J-0008, 2026-11-08). A caption naming is told to the judge and said in the why; it does
     * not outrank the face reading.
     */
    static boolean strongNaming(String by) { return BY_TITLE.equals(by) || BY_ADDRESS.equals(by); }

    /** Whether words look like a person's name as a search would carry it: two to four words, each beginning with a capital (Greek or Latin), no digit. */
    public static boolean looksLikeAName(String words) {
        if (words == null) return false;
        String[] w = words.strip().split("\\s+");
        if (w.length < 2 || w.length > 4) return false;
        for (String x : w) if (!x.matches("^\\p{Lu}[\\p{L}'’.-]+$")) return false;
        return true;
    }

    /** The name an ask carries, for the signal: the person's words when they look like a name, else the reference entry's title when it does; null for none. */
    public static String nameOf(String words, Reference ref) {
        if (looksLikeAName(words)) return words.strip();
        String t = ref == null || ref.kept() == null ? "" : ref.kept().found().title();
        return looksLikeAName(t) ? t.strip() : null;
    }

    /** The model's "writing: yes" or "writing: no" in an answer; null when it gave neither. */
    static Boolean writingNames(String said) {
        if (said == null) return null;
        Matcher m = Pattern.compile("(?i)writing\\s*:\\s*(yes|no)").matcher(said);
        return m.find() ? m.group(1).equalsIgnoreCase("yes") : null;
    }

    /**
     * The verdict ranked, the machine-read naming above the model's face reading (the project's rule: machine-computed evidence outranks the
     * model's judgement). Faces yes → yes. Writing in the picture names the person, by the model's reading → yes. Named by its text and faces
     * no or unsure → UNSURE, with the why "the page names <name>; the faces look different to the model": listed, counted, not kept — the 27B
     * refused the seated photograph with the man's name written on it for "a thinner mustache", the same man decades apart (2026-11-08).
     * Not named and faces no → no. The why says the components, so a reader sees what decided it.
     */
    public static Judged ranked(Judged faces, Boolean writing, String namedBy, String name) {
        if (faces == null) return null;
        String why = faces.why() == null ? "" : faces.why().replaceAll("(?i)[;,.]?\\s*writing\\s*:\\s*(?:yes|no)[;,.]?", "").strip();
        boolean hasName = name != null && !name.isBlank(), named = hasName && namedBy != null && strongNaming(namedBy), captioned = hasName && BY_CAPTION.equals(namedBy);
        // the model answered the word and "writing: yes" and nothing else (Doyle's photograph, live, 2026-11-08): the why says what carried the verdict, never nothing
        if (why.isEmpty() || why.equals(NO_REASON)) why = Boolean.TRUE.equals(writing) ? "writing in the picture names " + (hasName ? name : "the person") : "the faces, by the model";
        if (faces.verdict() == Verdict.UNJUDGED) {   // no verdict could be read: a candidate named by its own name is still unsure, not "not judged"
            if (!named) return faces;
            return new Judged(faces.found(), Verdict.UNSURE, "named by " + namedWords(namedBy) + "; faces: not read → unsure: the page names " + name + "; the model gave no verdict" + (why.isEmpty() ? "" : " (" + why + ")"), faces.model(), faces.criteria(), faces.judged());
        }
        if (faces.verdict() == Verdict.YES) {
            // the naming the yes also rests on, said, so a face measure below the threshold cannot demote it (the picture's own words outrank a biometric guess)
            String textWhy = !hasName || why.contains("names " + name) ? "" : Boolean.TRUE.equals(writing) ? "; writing in the picture names " + name : named ? "; " + namedWords(namedBy) + " names " + name : captioned ? "; its caption names " + name : "";
            return new Judged(faces.found(), Verdict.YES, "faces: yes — " + why + textWhy, faces.model(), faces.criteria(), faces.judged());
        }
        if (Boolean.TRUE.equals(writing) && hasName) return new Judged(faces.found(), Verdict.YES, "writing in the picture names " + name + "; faces: " + faces.verdict().word() + " → yes — " + why, faces.model(), faces.criteria(), faces.judged());
        if (named) {
            String look = faces.verdict() == Verdict.NO ? "the faces look different to the model" : "the faces are unsure to the model";
            return new Judged(faces.found(), Verdict.UNSURE, "named by " + namedWords(namedBy) + "; faces: " + faces.verdict().word() + " → unsure: the page names " + name + "; " + look + (why.isEmpty() ? "" : " (" + why + ")"), faces.model(), faces.criteria(), faces.judged());
        }
        return new Judged(faces.found(), faces.verdict(), (captioned ? "its caption names " + name + "; " : "") + "faces: " + faces.verdict().word() + " — " + why, faces.model(), faces.criteria(), faces.judged());
    }

    /** The criteria a judgement against a reference is recorded under. */
    public static String referenceCriteria(String mode, String what, String referenceId) {
        String against = referenceId == null || referenceId.isBlank() ? "the reference picture" : "the picture kept as " + referenceId;
        return CONCEPT.equals(mode) ? "like " + against + " in " + what : "the same " + what + " as " + against;
    }

    /**
     * One candidate judged against the reference with both pictures in one call: {@code mode} {@link #SAME} asks whether the candidate shows the
     * same {@code what} (person, object, place, thing); {@link #CONCEPT} whether it is like the reference in the aspect {@code what} names.
     * The reference's description, when it has one, goes with the question so the judge knows what it is looking at.
     */
    public static Judged judgeAgainst(ModelEntries.Entry eyes, Reference ref, String mode, String what, Found f, byte[] bytes, String contentType) { return judgeAgainst(eyes, ref, mode, what, null, f, bytes, contentType); }

    /**
     * The same, with the person's {@code name} when the ask carries one: the question then says where the candidate's own text names the
     * person and asks besides whether writing in the picture does, and the verdict is ranked ({@link #ranked}) so that the naming outranks a
     * face reading of no.
     */
    public static Judged judgeAgainst(ModelEntries.Entry eyes, Reference ref, String mode, String what, String name, Found f, byte[] bytes, String contentType) {
        String w = what == null || what.isBlank() ? (CONCEPT.equals(mode) ? "its kind" : "thing") : what.strip().replaceAll("[.?!]+$", "");
        String by = CONCEPT.equals(mode) ? BY_NONE : namedBy(f, name);
        boolean named = !BY_NONE.equals(by);
        String ask = String.format(Locale.ROOT, CONCEPT.equals(mode) ? CONCEPT_RULE : SAME_RULE, w)
                + (ref.said().isEmpty() ? "" : " The reference was described as: " + cut(ref.said(), 300))
                + (f.title() == null || f.title().isBlank() ? "" : " The candidate's page is titled \"" + cut(f.title().replaceAll("\\s+", " "), 120) + "\".")
                + (named ? " The candidate's " + (BY_TITLE.equals(by) ? "page title" : BY_ADDRESS.equals(by) ? "address" : "caption") + " names " + name + "." : "")
                + (name != null && !name.isBlank() && !CONCEPT.equals(mode) ? " After your verdict, say \"writing: yes\" if writing in the picture itself names " + name + ", else \"writing: no\"." : "");
        String said = seeTwo(eyes, ref.bytes(), ref.contentType(), bytes, contentType, ask, 180);
        Judged faces = verdict(f, referenceCriteria(mode, w, ref.id()), eyes.model(), said);
        Boolean writing = writingNames(said);
        if (unread(faces, said)) {   // once more, with the same two pictures, told to begin with the word; only then not judged
            String said2 = seeTwo(eyes, ref.bytes(), ref.contentType(), bytes, contentType, ask + ONE_WORD, 180);
            Judged again = verdict(f, referenceCriteria(mode, w, ref.id()), eyes.model(), said2);
            faces = again.verdict() == Verdict.UNJUDGED ? noVerdictTwice(faces, said2) : again;
            if (writing == null) writing = writingNames(said2);
        }
        return CONCEPT.equals(mode) || name == null || name.isBlank() ? faces : ranked(faces, writing, by, name);
    }

    static String capital(String s) { return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    /** The likeness helper's measure of a candidate against the reference; null when no helper is set up, or it could not measure. */
    public static LikenessHelper.Measure measure(Reference ref, byte[] bytes, String contentType) {
        LikenessHelper.Scorer s = LikenessHelper.scorer();
        if (s == null || ref == null || bytes == null) return null;
        try { return s.score(ref.bytes(), ref.contentType(), bytes, contentType); } catch (IOException | RuntimeException e) { return null; }
    }

    /**
     * The verdict with the measure folded in, the number first (machine-computed evidence outranks the model's judgement): for the same
     * person, a face score at or above {@link LikenessHelper#FACE_SAME} turns the model's no into unsure and one below
     * {@link LikenessHelper#FACE_DIFFERENT} turns its yes into unsure, the why naming the number; otherwise the number joins the why. The
     * concept mode and things that are not people get the number said only, since the picture embedding does not separate (measured, 2026-11-08).
     */
    public static Judged measured(Judged j, LikenessHelper.Measure m, String mode, boolean person) { return measured(j, m, mode, person, null); }

    /** The same, with the person's {@code name} for the why. A yes that rests on writing in the picture, the page's title, its address or a caption naming the person is not demoted by a low face measure: the picture's own words outrank a biometric guess. A face too small or unclear to measure is not used ({@link LikenessHelper.Measure#faceUsable}). */
    public static Judged measured(Judged j, LikenessHelper.Measure m, String mode, boolean person, String name) {
        if (j == null || m == null || j.verdict() == Verdict.UNJUDGED) return j;
        String why = j.why() == null ? "" : j.why();
        if (SAME.equals(mode) && person && m.faceUsable()) {
            String f = LikenessHelper.Measure.fmt(m.face());
            if (j.verdict() == Verdict.NO && m.face() >= LikenessHelper.FACE_SAME) return new Judged(j.found(), Verdict.UNSURE, "the faces measure the same (" + f + ") while the model says no → unsure: " + why, j.model(), j.criteria(), j.judged());
            if (j.verdict() == Verdict.YES && m.face() < LikenessHelper.FACE_DIFFERENT) {
                if (TEXT_NAMES.matcher(why).find()) return new Judged(j.found(), Verdict.YES, why + "; faces measure " + f + ", but the picture's own words name " + (name == null || name.isBlank() ? "the person" : name), j.model(), j.criteria(), j.judged());
                return new Judged(j.found(), Verdict.UNSURE, "the faces measure different (" + f + ") while the model says yes → unsure: " + why, j.model(), j.criteria(), j.judged());
            }
        }
        return new Judged(j.found(), j.verdict(), why + (why.isEmpty() ? "" : "; ") + m.said(), j.model(), j.criteria(), j.judged());
    }

    /** A why that says the picture's own text names the person: writing in it, its page title, its address or its caption ({@link #ranked} writes these). */
    static final Pattern TEXT_NAMES = Pattern.compile("(?i)\\b(?:writing in the picture names|page title names|its address names|its caption names|the page names|named by its)\\b");

    /** A candidate fetched, and measured against the reference when the helper is up. */
    public record Scored(Found found, Fetched got, LikenessHelper.Measure measure) { }

    /** The candidates in the order the model should see them: the best-measured first, the unmeasured after in their own order. */
    public static List<Scored> inMeasuredOrder(List<Scored> in) {
        List<Scored> out = new ArrayList<>(in);
        out.sort((x, y) -> { if (x.measure() == null && y.measure() == null) return 0; if (x.measure() == null) return 1; if (y.measure() == null) return -1; return Double.compare(y.measure().rank(), x.measure().rank()); });
        return out;
    }

    /** The candidate judged by the reference's words alone, for a model that takes one picture at a time: the description stands in for the reference. */
    public static Judged judgeByWords(ModelEntries.Entry eyes, Reference ref, String mode, String what, Found f, byte[] bytes, String contentType) { return judgeByWords(eyes, ref, mode, what, null, f, bytes, contentType); }

    /** The same, ranked by the naming when the ask carries a name. */
    public static Judged judgeByWords(ModelEntries.Entry eyes, Reference ref, String mode, String what, String name, Found f, byte[] bytes, String contentType) {
        String w = what == null || what.isBlank() ? "thing" : what.strip().replaceAll("[.?!]+$", "");
        String criteria = CONCEPT.equals(mode) ? "something like this in " + w + ": " + cut(ref.said(), 200) : "the same " + w + " as this: " + cut(ref.said(), 200);
        Judged j = judge(eyes, criteria, f, bytes, contentType, true);
        Judged faces = new Judged(f, j.verdict(), j.why(), j.model(), referenceCriteria(mode, w, ref.id()) + " (by its description)", j.judged());
        return CONCEPT.equals(mode) || name == null || name.isBlank() ? faces : ranked(faces, null, namedBy(f, name), name);
    }

    /**
     * What a likeness search gave: the reference, the words searched and why those, the mode and why, whether the judge saw both pictures,
     * the candidates and their verdicts, and how many candidates were skipped after the fetch as not pictures the model can look at.
     */
    public record Liked(Reference reference, String words, String wordsWhy, String mode, String modeWhy, boolean twoPictures, Result found, List<Judged> judged, int skipped, List<Found> sameAsReference, Map<String, LikenessHelper.Measure> measures) {
        public Liked(Reference reference, String words, String wordsWhy, String mode, String modeWhy, boolean twoPictures, Result found, List<Judged> judged, int skipped) { this(reference, words, wordsWhy, mode, modeWhy, twoPictures, found, judged, skipped, List.of(), Map.of()); }
        public Liked(Reference reference, String words, String wordsWhy, String mode, String modeWhy, boolean twoPictures, Result found, List<Judged> judged, int skipped, List<Found> sameAsReference) { this(reference, words, wordsWhy, mode, modeWhy, twoPictures, found, judged, skipped, sameAsReference, Map.of()); }
        /** "Your picture appears also on doyles.space (https://…) and on x.example (https://…)." — the candidates that were the reference's own bytes; "" when none. */
        public String appearsAlso() {
            if (sameAsReference.isEmpty()) return "";
            List<String> places = new ArrayList<>();
            for (Found f : sameAsReference) places.add((f.host().isEmpty() ? f.image() : f.host()) + " (" + (f.page() == null || f.page().isBlank() ? f.image() : f.page()) + ")");
            return "Your picture appears also on " + String.join(" and on ", places) + ".";
        }
        /** How the judging was done, one sentence, and whether the likeness helper measured first. */
        public String how(ModelEntries.Entry eyes) {
            return (twoPictures ? "The model that sees (" + eyes.name() + ", " + eyes.model() + ") compared each candidate with your picture, both in one look."
                    : "The model that sees (" + eyes.name() + ", " + eyes.model() + ") takes one picture at a time, so your picture was described in words and each candidate judged against those words.")
                    + " " + capital(LikenessHelper.said()) + ".";
        }
        public long yes() { return judged.stream().filter(Judged::yes).count(); }
        public long unsure() { return judged.stream().filter(j -> j.verdict() == Verdict.UNSURE).count(); }
        /** "2 of 6 are the same, 1 unsure." / "… are like it." */
        public String tally(String what) {
            long y = yes(), u = unsure();
            return y + " of " + judged.size() + (y == 1 ? " is" : " are") + (CONCEPT.equals(mode) ? " like it" : " the same" + (what == null || what.isBlank() ? "" : " " + what)) + (u == 0 ? "." : ", " + u + " unsure.");
        }
        /** The skipped, said; "" when none were. */
        public String skippedSaid() { int n = skipped + found.skipped(); return n == 0 ? "" : " " + n + (n == 1 ? " candidate was" : " candidates were") + " skipped as not " + (n == 1 ? "a picture" : "pictures") + " the model can look at (an SVG, a PDF and the like)."; }
    }

    /** The word for a kind of thing a search adds to a name: "portrait" for a person, nothing for the rest. */
    static String kindWord(String what) { return what == null ? "" : switch (what.strip().toLowerCase(Locale.ROOT)) { case "person", "man", "woman", "face" -> "portrait"; default -> ""; }; }

    /**
     * The search from a picture: the reference described, the candidates found for the description's search words with the person's words
     * beside them, each judged against the reference — both pictures in one call when the entry takes two ({@link Vision#seesTwo}), else by
     * the description's words — and the reference's own bytes passed over. {@code what}: the thing the words name (person, place, object; the
     * aspect in {@link #CONCEPT} mode), "" when they name none.
     */
    public static Liked like(LibraryStore store, ModelEntries.Entry eyes, Reference ref, String mode, String what, String words, int count, boolean strict) {
        String m = CONCEPT.equals(mode) ? CONCEPT : SAME;
        // the words rule: the person's words are the search, with at most the kind's word ("portrait" for a person); the description's words only when they gave none —
        // a name joined to the description's words made a precise search a generic one (2026-11-08: "Valerios Stais vintage man portrait mustache suit" found stock portraits)
        String given = words == null ? "" : words.strip(), kind = kindWord(what);
        String search, wordsWhy;
        if (!given.isEmpty()) { search = (given + (kind.isEmpty() || given.toLowerCase(Locale.ROOT).contains(kind) ? "" : " " + kind)).strip(); wordsWhy = "your words" + (search.equals(given) ? "" : ", with \"" + kind + "\" for a " + what.strip().toLowerCase(Locale.ROOT)); }
        else { search = ref.searchWords() == null ? "" : ref.searchWords().strip(); wordsWhy = "the description's words, since you gave none"; }
        if (search.isEmpty()) { search = what == null ? "" : what; wordsWhy = "the kind of thing asked for, since the picture's description gave no words"; }
        Result r = search.isEmpty() ? new Result(List.of(), List.of(), List.of("no words to search by")) : find(store, search, Math.min(Math.max(count, 1), MOST));
        boolean two = Vision.seesTwo(eyes);
        String refSha = sha256(ref.bytes());
        String name = CONCEPT.equals(m) ? null : nameOf(given, ref);   // the person's name, for the signal that outranks the model's face reading
        List<Judged> out = new ArrayList<>();
        List<Found> sameAsReference = new ArrayList<>();
        Map<String, LikenessHelper.Measure> measures = new LinkedHashMap<>();
        int skipped = 0;
        // every candidate fetched first; with the likeness helper up each is measured against the reference and the model sees the best-measured first (0.7.0)
        List<Scored> toJudge = new ArrayList<>();
        for (Found f : r.found()) {
            Fetched got;
            try { got = fetch(store, f); }
            catch (Stopping.Requested stop) { throw stop; }
            catch (Exception e) { out.add(new Judged(f, Verdict.UNJUDGED, e.getMessage() == null ? "the picture could not be fetched" : e.getMessage().startsWith("the picture") ? e.getMessage() : "the picture could not be fetched: " + e.getMessage(), eyes.model(), referenceCriteria(m, what, ref.id()))); continue; }
            if (!looksAtable(got)) { skipped++; continue; }   // not a candidate: nothing goes to the model
            if (sha256(got.bytes()).equals(refSha)) { sameAsReference.add(f); continue; }   // the reference's own bytes on another page: another place it appears, said as that, not a verdict
            toJudge.add(new Scored(f, got, measure(ref, got.bytes(), got.contentType())));
        }
        boolean person = !CONCEPT.equals(m) && (name != null || !kindWord(what).isEmpty());
        for (Scored s : inMeasuredOrder(toJudge)) {
            Judged j = two ? judgeAgainst(eyes, ref, m, what, name, s.found(), s.got().bytes(), s.got().contentType()) : judgeByWords(eyes, ref, m, what, name, s.found(), s.got().bytes(), s.got().contentType());
            if (s.measure() != null) measures.put(s.found().image(), s.measure());
            out.add(measured(j, s.measure(), m, person, name));
        }
        String why = CONCEPT.equals(m) ? "the words named an aspect to match, " + what : (what == null || what.isBlank() ? "the words asked for more of what the picture shows" : "the words asked for more pictures of this " + what + ", so the same one is looked for");
        return new Liked(ref, search, wordsWhy, m, why, two, r, out, skipped, sameAsReference, measures);
    }

    /** The two pictures and one question to the seeing model; null when nothing answered. */
    static String seeTwo(ModelEntries.Entry eyes, byte[] first, String firstType, byte[] second, String secondType, String question, int maxTokens) {
        Researcher.Drive d;
        try { d = Vision.drive(eyes); } catch (RuntimeException e) { return null; }
        ArrayNode msgs = M.createArrayNode();
        ArrayNode content = msgs.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", question);
        content.addObject().put("type", "image_url").putObject("image_url").put("url", Vision.dataUrl(first, firstType));
        content.addObject().put("type", "image_url").putObject("image_url").put("url", Vision.dataUrl(second, secondType));
        DriveClient.clearClassifyProblem();
        DriveClient.callerSays(true);
        try { return d.classify(msgs, maxTokens); }
        catch (Stopping.Requested stop) { throw stop; }
        catch (RuntimeException unanswered) { return null; }
        finally { DriveClient.callerSays(false); }
    }

    /** The phrases a sentence says a verdict with when it does not begin with the word: the no-phrases are read first and taken out, so "not the same person" is a no and not both. */
    private static final Pattern SAYS_NO = Pattern.compile("(?i)\\b(not the same|a different (?:person|man|woman|face|object|place|thing|individual)|different (?:person|people|individuals|faces)|do(?:es)? not (?:show|depict) the same|is not (?:him|her|the same)|are not the same|another (?:person|man|woman))\\b");
    private static final Pattern SAYS_YES = Pattern.compile("(?i)\\b((?:show|shows|showing|depict|depicts|is|are|be) the same|the same (?:person|man|woman|individual|face|object|place|building|thing|item|coin|painting|statue)|same individual|clearly the same)\\b");
    private static final Pattern SAYS_UNSURE = Pattern.compile("(?i)\\b(could be|possibly|unsure|uncertain|cannot tell|can't tell|cannot be sure|hard to tell|difficult to tell|may be the same|might be the same|not certain)\\b");

    /** The verdict a sentence carries when it is unambiguous: a yes-phrase alone, a no-phrase alone, an unsure-phrase; null when it carries both, or none. */
    static Verdict readVerdict(String said) {
        if (said == null || said.isBlank()) return null;
        String s = said.replaceAll("\\s+", " ");
        boolean unsure = SAYS_UNSURE.matcher(s).find();
        boolean saysNo = SAYS_NO.matcher(s).find();
        String rest = saysNo ? SAYS_NO.matcher(s).replaceAll(" ") : s;
        boolean saysYes = SAYS_YES.matcher(rest).find();
        if (unsure && !saysYes && !saysNo) return Verdict.UNSURE;
        if (saysYes && !saysNo && !unsure) return Verdict.YES;
        if (saysNo && !saysYes && !unsure) return Verdict.NO;
        return null;
    }

    /**
     * The model's words read as a verdict: the first word yes, no or unsure, the rest the reason; else the verdict the sentence says when
     * it says one plainly ({@link #readVerdict}: "the two pictures show the same person" is a yes — a real yes was lost to the first-word
     * rule on a live run, 2026-11-08), the whole sentence the reason; else not judged, with the words.
     */
    static Judged verdict(Found f, String criteria, String model, String said) {
        String s = said == null ? "" : said.strip();
        if (s.isEmpty()) { String p = DriveClient.lastClassifyProblem(); return new Judged(f, Verdict.UNJUDGED, p == null || p.isBlank() ? "the model gave no answer" : p, model, criteria); }
        Matcher m = FIRST_WORD.matcher(s.toLowerCase(Locale.ROOT));
        String first = m.find() ? m.group(1) : "";
        Verdict v = switch (first) { case "yes" -> Verdict.YES; case "no" -> Verdict.NO; case "unsure", "uncertain", "maybe" -> Verdict.UNSURE; default -> Verdict.UNJUDGED; };
        if (v == Verdict.UNJUDGED) {
            Verdict read = readVerdict(s);
            if (read != null) { String why = s.replaceAll("\\s+", " ").strip(); if (why.endsWith(".")) why = why.substring(0, why.length() - 1); return new Judged(f, read, why, model, criteria); }
            return new Judged(f, v, "the model answered \"" + cut(s, 120) + "\" instead of yes, no or unsure", model, criteria);
        }
        String why = s.substring(m.end()).replaceFirst("^[\\s,.:;—–-]+", "").strip();
        if (why.endsWith(".")) why = why.substring(0, why.length() - 1);
        return new Judged(f, v, why.isBlank() ? NO_REASON : why, model, criteria);
    }

    /** The why when the model gave the word alone: a verdict is never printed with nothing after it. */
    public static final String NO_REASON = "the model gave the word and no reason";

    /** The one more ask when the first answer carried no verdict. */
    static final String ONE_WORD = " Begin your answer with one word: yes, no or unsure.";
    /** Whether a judgement is one the model answered without a verdict that could be read (an empty answer is not asked again: the problem is the server's). */
    static boolean unread(Judged j, String said) { return j.verdict() == Verdict.UNJUDGED && said != null && !said.isBlank(); }
    /** The verdict after the second ask, when that too carried none. */
    static Judged noVerdictTwice(Judged j, String said2) { return new Judged(j.found(), Verdict.UNJUDGED, "the model gave no verdict twice: \"" + cut(said2 == null ? "" : said2, 120) + "\"", j.model(), j.criteria(), j.judged()); }

    /** One picture and one question to the seeing model; null when nothing answered. {@code prose}: the answer is itself the work. */
    static String see(ModelEntries.Entry eyes, byte[] bytes, String contentType, String question, int maxTokens, boolean prose) {
        Researcher.Drive d;
        try { d = Vision.drive(eyes); } catch (RuntimeException e) { return null; }
        ArrayNode msgs = M.createArrayNode();
        ArrayNode content = msgs.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", question);
        content.addObject().put("type", "image_url").putObject("image_url").put("url", Vision.dataUrl(bytes, contentType));
        DriveClient.clearClassifyProblem();
        DriveClient.callerSays(true);   // a verdict's line carries the reason when the model did not answer: the client's own sentence stays off the terminal
        try { return prose ? d.prose(msgs, maxTokens) : d.classify(msgs, maxTokens); }
        catch (Stopping.Requested stop) { throw stop; }
        catch (RuntimeException unanswered) { return null; }
        finally { DriveClient.callerSays(false); }
    }

    static String cut(String s, int n) { String t = s == null ? "" : s.replaceAll("\\s+", " ").strip(); return t.length() > n ? t.substring(0, n) + "…" : t; }
}
