package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import java.time.LocalDate;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.researchzosho.librarian.Acquisitions;
import org.researchzosho.librarian.Fence;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.RawCapture;
import org.researchzosho.librarian.Requests;
/**
 * Fetch a URL and return its readable text — the "read the source" half of research (web_search finds,
 * web_fetch reads). HTML is reduced to text (script/style stripped, tags removed, entities decoded) and
 * truncated, so a small model's context isn't blown by one page.
 */
public final class WebFetchTool implements Tool {

    /** Session-wide count of successful source fetches — the acquisitions gate's "did this run
     *  actually READ anything" evidence (a finding needs ≥1 fetched source; a claim without one
     *  is answered-from-memory, which the librarian refuses at intake). */
    public static final AtomicInteger FETCHES_OK =
            new AtomicInteger();

    // Page excerpts must stay SMALL: a 9B has a ~16K-token window shared with history. 12K-char pages filled
    // the context after a few fetches (out_budget collapsed 16384 -> 1909) leaving no room to WRITE the answer —
    // the loop could only keep making small tool calls and never concluded. Keep excerpts tight.
    private static final int MAX_CHARS = 2_500;
    // REPETITION GUARD (same lever as the ops RemediationLoop): a small model fixates — it re-fetches the same
    // URL instead of synthesizing. Serve each URL once; on a repeat, refuse and push it to move on/conclude.
    private final Set<String> fetched = new HashSet<>();

    @Override public String name() { return "web_fetch"; }

    @Override public String description() {
        return "Fetch a URL and return its readable text content. Use after web_search to actually READ a "
                + "promising source. Long pages are EXCERPTED, not truncated: pass `find` with the specific "
                + "thing you are looking for (e.g. 'sculpture 2009 height') and you get the passages that "
                + "mention it, from anywhere in the page — not just the opening.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("url").put("type", "string");
        props.putObject("find").put("type", "string");
        p.putArray("required").add("url");
        return p;
    }

    /**
     * The question this run is answering, used to CENTER the excerpt when the model doesn't say what it's
     * looking for. Without it a fetch returns the first {@value #MAX_CHARS} characters — for a Wikipedia
     * article, the lead section — so a fact in the body is never seen no matter how many times it is fetched
     * (measured: a run fetched the right page 11 times and still reported it could not find the answer).
     */
    private String focus = "";

    public WebFetchTool focus(String question) {
        this.focus = question == null ? "" : question;
        return this;
    }

    /**
     * The run's content policy: the lists an address is checked against before it is fetched, the page check after it ({@link PageCheck}),
     * and where the page is kept. Without a run's: nothing let in, the library's configured model checking, the configured library keeping.
     */
    private volatile ContentPolicy policy = ContentPolicy.defaults();

    public WebFetchTool policy(ContentPolicy p) { this.policy = p == null ? ContentPolicy.defaults() : p; return this; }

    @Override public String execute(JsonNode args) throws Exception {
        String url = args.path("url").asText("");
        if (url.isBlank()) return "ERROR: empty url";
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        // The guard keys on url+find: re-reading the SAME page for a DIFFERENT thing is legitimate (the
        // excerpt shown is question-relevant, so a new question genuinely shows new text) — what we refuse
        // is the identical fetch, which is fixation.
        String findArg = args.path("find").asText("").strip().toLowerCase();
        if (!fetched.add(url + "|" + findArg))
            return "ALREADY FETCHED: you have already read " + url
                    + (findArg.isBlank() ? "" : " looking for \"" + findArg + "\"")
                    + " in this session — its content is above in your history. Do NOT repeat this fetch. "
                    + "Fetch a DIFFERENT source, or re-read this one with a different `find` if you need "
                    + "another part of it, or write the answer now and call task_done.";
        String terms = args.path("find").asText("");
        if (terms.isBlank()) terms = focus;
        PageCheck.Page page;
        try {
            // fetched under the run's lists, converted, and checked before anything of it is shown or saved
            page = PageCheck.fetch(url, Duration.ofSeconds(30), policy, terms);
        } catch (Fetch.LeftOut e) {
            // on a list the run checks: not fetched, not saved, and the address stays out of the notes
            return "ERROR: " + e.url() + " was left out of this research, because " + e.why() + ". Read another source.";
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage() + " — " + url + " is not a source this tool will read.";
        } catch (Exception e) {
            return "ERROR: could not fetch " + url + " (" + e + ")";
        }
        Fetch.Result resp = page.fetched();
        url = resp.url();   // after redirects — what was actually read is what gets cited
        if (resp.status() >= 400) {
            // WIKIPEDIA 404 → real titles. A model GUESSES plausible article titles and a near-miss 404s
            // (measured: "List of state constitutions of the United States" — the page exists as
            // "List of U.S. state constitutions"; three fetch attempts, task lost). Wikipedia's own
            // title-search API resolves the guess; enrich the error with the top real titles.
            String didYouMean = wikiTitleSuggestions(url);
            return "ERROR: HTTP " + resp.status() + " for " + url + didYouMean;
        }
        // Bytes → sniff → text: PDF, DOCX/PPTX/ODT/EPUB and HTML all arrive here as bytes and are
        // converted by what they ARE (DocText), never by what the URL or content-type claims.
        // Before 2026-09-01 a PDF body went through the HTML path and came out as "binary,
        // unreadable" — two JA academic papers lost that way on the keigo shelf.
        // left out by the page check: nothing of it is shown, saved or cited; the run's log and report name the category and the address
        if (!page.kept()) return "ERROR: " + url + " was left out of this research (" + page.leftOut() + "). Read another source.";
        byte[] bytes = resp.body();
        DocText.Doc doc = page.doc();
        if (doc.kind().startsWith("pdf-unreadable")) {
            return "ERROR: PDF at " + url + " could not be converted (" + doc.kind() + ")";
        }
        String text = doc.text();
        String wall = Fetch.wall(doc.title(), text);
        if (wall != null || resp.status() == 401 || resp.status() == 402 || resp.status() == 403 || resp.status() == 451) {
            // measured live (2026-09-07): "Error - Cookies Turned Off", "Making sure you're not a bot!" and a reCAPTCHA
            // page were numbered references in a report. A wall is not a source; it is a fetch that failed — and a
            // request to the person, who may hold the paper or the access.
            String what = wall != null ? wall : "HTTP " + resp.status();
            request(url, what);
            return "ERROR: " + url + " answered with " + what + " instead of the page — recorded as a source request for the person. "
                    + "Try an open version (arXiv, a repository, the author's page) or another source.";
        }
        FETCHES_OK.incrementAndGet();
        // Raw tier: the FULL text is preserved with provenance at capture time (the model sees
        // the excerpt below; the library keeps the source). No library → no-op.
        String published = publishedDate(new String(bytes, 0, Math.min(bytes.length, 200_000), StandardCharsets.UTF_8));
        LibraryStore into = policy.store();
        if (into != null) RawCapture.capture(into, url, text, doc.title(), "researchzosho-web-fetch", "", published);
        else if (Acquisitions.libraryExists()) RawCapture.capture(LibraryStore.open(), url, text, doc.title(), "researchzosho-web-fetch", "", published);
        // The document's own title rides on the source line: the person watching the turn sees
        // WHAT was read, not just that a read happened, and the model cites by name, not URL.
        String title = doc.title();
        String kindNote = "html".equals(doc.kind()) || "text".equals(doc.kind()) ? "" : " [" + doc.kind() + "]";
        String head = "source: " + url + (title.isEmpty() ? "" : " — " + title) + kindNote + "\n";
        String shown = text.length() <= MAX_CHARS ? text : excerpt(text, terms);
        // the page's pictures, with their alt text, caption and address, so that the model can have one read by the model that reads pictures
        List<Picture> pics = "html".equals(doc.kind()) ? pictures(new String(bytes, 0, Math.min(bytes.length, 2_000_000), StandardCharsets.UTF_8), url) : List.of();
        if (!pics.isEmpty()) shown = shown + "\n\n" + pictureList(pics);
        // The page's text is FENCED with a per-process nonce: it is quoted evidence, and a page cannot
        // forge the closing marker to speak in the harness's voice (Wyrdsekai, 2026-09-07).
        return head + Fence.wrap("SOURCE TEXT", shown) + "\n" + Fence.rule("SOURCE TEXT")
                + (pics.isEmpty() ? "" : "\nThe page has " + pics.size() + " picture(s), listed at the end of its text. To have one read, call web_fetch with its address.");
    }

    /** A wall becomes a request to the person, when there is a library to record it in. */
    private void request(String url, String what) {
        try {
            if (Acquisitions.libraryExists()) {
                Requests.note(LibraryStore.open(), url, what, focus);
            }
        } catch (Exception ignored) {
            // the request is a courtesy; the fetch already failed
        }
        WALLS.add(url + " — " + what);
    }

    /** The walls hit in this process, for an investigation's "Source requests" section. */
    public static final Set<String> WALLS = Collections.synchronizedSet(new LinkedHashSet<>());

    private static final Pattern[] PUBLISHED = {
        Pattern.compile("(?is)<meta[^>]+(?:property|name)=[\"'](?:article:published_time|og:article:published_time|datePublished|date|dc\\.date|dcterms\\.(?:created|issued)|pubdate|publish[_-]?date|sailthru\\.date|citation_publication_date|citation_date)[\"'][^>]+content=[\"']([^\"']{8,40})[\"']"),
        Pattern.compile("(?is)<meta[^>]+content=[\"']([^\"']{8,40})[\"'][^>]+(?:property|name)=[\"'](?:article:published_time|datePublished|date|dc\\.date|pubdate|citation_publication_date)[\"']"),
        Pattern.compile("(?is)\"datePublished\"\\s*:\\s*\"([^\"]{8,40})\""),
        Pattern.compile("(?is)<time[^>]+datetime=[\"']([^\"']{8,40})[\"']"),
    };

    /** The date a page says it was published, as YYYY-MM-DD, or "" — from its meta tags, its JSON-LD, or a dated time element. */
    public static String publishedDate(String html) {
        if (html == null) return "";
        for (var p : PUBLISHED) {
            var m = p.matcher(html);
            while (m.find()) {
                var d = Pattern.compile("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})").matcher(m.group(1));
                if (d.find()) {
                    try {
                        var date = LocalDate.of(Integer.parseInt(d.group(1)), Integer.parseInt(d.group(2)), Integer.parseInt(d.group(3)));
                        if (date.getYear() >= 1990 && !date.isAfter(LocalDate.now().plusDays(1))) return date.toString();
                    } catch (Exception ignored) { }
                }
            }
        }
        return "";
    }

    /** The page's <title>, entity-decoded and trimmed, or "". Read from the RAW body — readable()
     *  strips the head, so this must run before it. */
    public static String pageTitle(String html) {
        if (html == null) return "";
        var m = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (!m.find()) return "";
        String t = Entities.decode(m.group(1)).replaceAll("\\s+", " ").strip();
        return t.length() > 120 ? t.substring(0, 117) + "..." : t;
    }

    /** On a wikipedia.org/wiki/<title> 404, ask Wikipedia's title-search API for the real titles and
     *  return a "did you mean" block (empty for non-wiki URLs or when the lookup itself fails). */
    private static String wikiTitleSuggestions(String url) {
        Matcher m = Pattern
                .compile("https?://([a-z]{2,3})\\.(?:m\\.)?wikipedia\\.org/wiki/([^?#]+)").matcher(url);
        if (!m.matches()) return "";
        String lang = m.group(1), title = URLDecoder.decode(m.group(2), StandardCharsets.UTF_8);
        try {
            String api = "https://" + lang + ".wikipedia.org/w/rest.php/v1/search/page?limit=5&q="
                    + URLEncoder.encode(title.replace('_', ' '), StandardCharsets.UTF_8);
            Fetch.Result r = Fetch.get(api, Duration.ofSeconds(15));
            if (r.status() != 200) return "";
            var pages = new ObjectMapper().readTree(new String(r.body(), StandardCharsets.UTF_8)).path("pages");
            if (!pages.isArray() || pages.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("\nThat exact article title does not exist. Real articles matching it:\n");
            for (JsonNode p : pages) {
                sb.append("- ").append(p.path("title").asText("")).append(" → https://").append(lang)
                  .append(".wikipedia.org/wiki/").append(p.path("key").asText("")).append('\n');
            }
            return sb.append("Fetch one of THESE URLs instead of guessing further titles.").toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static final int LEAD_CHARS = 600;   // always keep the opening — it says what the page IS
    private static final Set<String> STOP = Set.of(
            "the", "a", "an", "of", "in", "on", "at", "to", "for", "and", "or", "is", "was", "were", "are",
            "what", "which", "who", "whom", "whose", "when", "where", "why", "how", "did", "does", "do",
            "that", "this", "it", "its", "his", "her", "their", "he", "she", "they", "by", "with", "from",
            "as", "be", "been", "has", "have", "had", "name", "named", "called", "many", "much", "first");

    /**
     * A query-RELEVANT window instead of the first N characters. Paragraphs are scored by how many of the
     * question's distinctive terms they contain; the best-scoring ones are returned in document order,
     * under the same character budget. The lead is always kept so the model can tell what the page is.
     * With no terms to go on this degrades to the old head-of-page behaviour.
     */
    static String excerpt(String text, String terms) {
        Set<String> want = new LinkedHashSet<>();
        for (String w : terms.toLowerCase().split("[^a-z0-9]+"))
            if (w.length() > 2 && !STOP.contains(w)) want.add(w);
        String lead = text.substring(0, Math.min(LEAD_CHARS, text.length()));
        if (want.isEmpty()) return text.substring(0, MAX_CHARS) + "\n\n…[truncated]";

        String[] paras = text.substring(lead.length()).split("\n+");
        record Scored(int idx, int hits, String body) { }
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < paras.length; i++) {
            String p = paras[i].strip();
            if (p.length() < 40) continue;
            String low = p.toLowerCase();
            int hits = 0;
            for (String w : want) if (low.contains(w)) hits++;
            // Data rows outrank prose ABOUT the data: a page's table usually IS what a data-seeking fetch
            // came for, but its rows echo few of the question's words — prose paragraphs out-scored the
            // rows and ate the whole budget (measured: the state-constitutions table survived readable()
            // and still never reached the model).
            if (hits > 0 && p.contains(" | ")) hits += 2;
            if (hits > 0) scored.add(new Scored(i, hits, p));
        }
        if (scored.isEmpty()) return text.substring(0, MAX_CHARS) + "\n\n…[truncated]";
        scored.sort((x, y) -> y.hits() - x.hits());   // best matches first, then restore document order

        List<Scored> keep = new ArrayList<>();
        Set<Integer> kept = new HashSet<>();
        int budget = MAX_CHARS - lead.length();
        for (Scored s : scored) {
            if (budget - s.body().length() < 0) continue;
            keep.add(s);
            kept.add(s.idx());
            budget -= s.body().length() + 2;
            // TABLE PULL-THROUGH: a matched line with ` | ` cells is a table header/row — the rows AFTER
            // it hold the data but rarely contain the question's words (a row says "Alabama | 1901 | 402",
            // not "constitution effective date"). Extend through the contiguous table block.
            if (s.body().contains(" | ")) {
                for (int i = s.idx() + 1; i < paras.length && budget > 80; i++) {
                    String p = paras[i].strip();
                    if (!p.contains(" | ")) break;
                    if (p.length() > budget || !kept.add(i)) break;
                    keep.add(new Scored(i, 0, p));
                    budget -= p.length() + 1;
                }
            }
            if (budget <= 80) break;
        }
        keep.sort((x, y) -> x.idx() - y.idx());
        StringBuilder sb = new StringBuilder(lead);
        int prev = -1;
        for (Scored s : keep) {
            sb.append(s.idx() == prev + 1 ? "\n" : "\n\n…\n\n").append(s.body());
            prev = s.idx();
        }
        sb.append("\n\n…[excerpted: the passages of this page matching your question, not the whole page. "
                + "Re-fetch with a different `find` to look for something else.]");
        return sb.toString();
    }

    /** The page's own part of its HTML: script, style and comments gone; its main content when it marks it; no navigation or footer. */
    static String contentHtml(String body) {
        String s = body;
        s = s.replaceAll("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?is)<!--.*?-->", " ");
        // the page's own content when it marks it: what is outside <main> is the site's menus, and so is a nav, a header or a footer inside it
        Matcher main = Pattern.compile("(?is)<main\\b[^>]*>(.*)</main>").matcher(s);
        if (main.find() && main.group(1).replaceAll("(?s)<[^>]+>", "").strip().length() > 200) s = main.group(1);
        return s.replaceAll("(?is)<(nav|footer)\\b[^>]*>.*?</\\1>", " ");
    }

    // ---- the pictures in a page ----

    /** A picture of the page's own content: its address, its alt text and the caption of the figure it sits in ("" for none). */
    public record Picture(String url, String alt, String caption) { }

    /** A picture drawn smaller than this, by its own width or height, is an icon or a tracking pixel. */
    static final int SMALLEST_SIDE = 48;
    /** A picture drawn at least this large on both sides is the page's content, whatever its file or its class is named. */
    static final int CONTENT_SIDE = 150;
    /** At most this many pictures are listed for the model, each alt text and caption cut to {@value #PICTURE_TEXT} characters. */
    static final int PICTURES = 6, PICTURE_TEXT = 120;

    /**
     * The words a site's furniture is named with, in a picture's class, id or file name: its logo, icons, avatars, badges, the ads and
     * banners, tracking pixels, spacers, share buttons. These are defaults of one rule: a picture that is part of the site rather than of
     * what the page says is not the page's content. They are the weaker signal: they decide only for a picture that the stronger ones
     * (how large it is drawn, its role, a caption) leave open, and only whether it is listed; its alt text is kept in the page's text
     * whatever it is named (a newspaper's advertisement of 1890 in a file named {@code ad-1890.jpg} is content).
     */
    static final Pattern FURNITURE = Pattern.compile("(?i)(?:^|[^a-z])(logos?|icons?|favicon|avatars?|sprites?|badges?|emoji|emoticons?|banners?|adverts?|advertisement|ads?|sponsor(?:ed)?|tracking|tracker|pixel|spacer|blank|spinner|loader|button|social|share)(?:[^a-z]|$)");

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("(?is)[\\s<]" + name + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").matcher(tag);
        if (!m.find()) return null;
        String v = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
        return Entities.decode(v).replaceAll("\\s+", " ").strip();
    }

    /** The address a picture tag loads: src, else the lazy loaders' data-src, else the first of its srcset; null for none. */
    private static String source(String tag) {
        String src = attr(tag, "src");
        if (src == null || src.isBlank() || src.startsWith("data:")) src = attr(tag, "data-src");
        if ((src == null || src.isBlank()) && attr(tag, "srcset") != null) src = attr(tag, "srcset").split("[\\s,]+")[0];
        return src == null || src.isBlank() ? null : src;
    }

    /**
     * Whether a picture tag is the site's furniture rather than the page's content, for the list of the page's pictures. The main signals:
     * hidden from readers (role presentation, aria-hidden), marked decorative by an empty alt text, drawn smaller than
     * {@value #SMALLEST_SIDE} pixels a side, or inline data. A picture drawn at least {@value #CONTENT_SIDE} pixels on both sides, or one
     * in a figure with a caption ({@code captioned}), is content. Only for the rest do the names decide: furniture words
     * ({@link #FURNITURE}) in its class, id or file name.
     */
    static boolean furniture(String tag) { return furniture(tag, false); }

    static boolean furniture(String tag, boolean captioned) {
        String role = attr(tag, "role");
        if (role != null && (role.equalsIgnoreCase("presentation") || role.equalsIgnoreCase("none"))) return true;
        if ("true".equalsIgnoreCase(attr(tag, "aria-hidden"))) return true;
        String alt = attr(tag, "alt");
        if (alt != null && alt.isBlank()) return true;   // an empty alt text is how a page marks a picture as decoration
        int w = side(tag, "width"), h = side(tag, "height");
        if ((w >= 0 && w < SMALLEST_SIDE) || (h >= 0 && h < SMALLEST_SIDE)) return true;
        String src = source(tag);
        if (src == null || src.startsWith("data:")) return true;
        if (captioned || (w >= CONTENT_SIDE && h >= CONTENT_SIDE)) return false;   // drawn large, or captioned: the page's content, whatever it is named
        for (String name : new String[]{"class", "id"}) { String v = attr(tag, name); if (v != null && FURNITURE.matcher(v).find()) return true; }
        String file = src.replaceAll("[?#].*$", "");
        file = file.substring(file.lastIndexOf('/') + 1);
        return FURNITURE.matcher(file).find() || file.matches("(?i).*\\b1x1\\b.*");
    }

    /** A side a picture tag draws it at, in pixels; -1 when it does not say, or says it in another unit. */
    private static int side(String tag, String name) {
        String v = attr(tag, name);
        if (v == null || !v.matches("\\d{1,6}(?:px)?")) return -1;
        return Integer.parseInt(v.replace("px", ""));
    }

    private static final Pattern IMG = Pattern.compile("(?is)<img\\b(?:\"[^\"]*\"|'[^']*'|[^>\"'])*>");

    /**
     * The pictures of a page's own content, for the model to have one read ({@code web_fetch} on its address, the same path a person's own
     * pictures take): each with its alt text and the caption of its figure. The site's furniture is skipped ({@link #furniture}), and so is
     * a picture this build cannot read (SVG, WebP). At most {@value #PICTURES}, in the page's order.
     */
    public static List<Picture> pictures(String html, String pageUrl) {
        List<Picture> out = new ArrayList<>();
        if (html == null || !html.contains("<")) return out;
        String s = contentHtml(html);
        Map<Integer, String> captionAt = new HashMap<>();
        Matcher fig = Pattern.compile("(?is)<figure\\b[^>]*>(.*?)</figure>").matcher(s);
        while (fig.find()) {
            Matcher cap = Pattern.compile("(?is)<figcaption\\b[^>]*>(.*?)</figcaption>").matcher(fig.group(1));
            String caption = cap.find() ? Entities.decode(cap.group(1).replaceAll("(?s)<[^>]+>", " ")).replaceAll("\\s+", " ").strip() : "";
            Matcher in = IMG.matcher(fig.group(1));
            while (in.find()) captionAt.put(fig.start(1) + in.start(), caption);
        }
        Set<String> seen = new HashSet<>();
        Matcher m = IMG.matcher(s);
        while (m.find() && out.size() < PICTURES) {
            String tag = m.group();
            String caption = captionAt.getOrDefault(m.start(), "");
            if (furniture(tag, !caption.isBlank())) continue;
            String src = source(tag);
            String url;
            try { url = pageUrl == null ? src : URI.create(pageUrl).resolve(src.replace(" ", "%20")).toString(); } catch (Exception e) { continue; }
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
            if (url.replaceAll("[?#].*$", "").matches("(?i).*\\.(svg|webp)$")) continue;   // this build cannot read these
            if (!seen.add(url)) continue;
            String alt = attr(tag, "alt");
            out.add(new Picture(url, cut(alt == null ? "" : alt), cut(caption)));
        }
        return out;
    }

    private static String cut(String t) { return t.length() <= PICTURE_TEXT ? t : t.substring(0, PICTURE_TEXT - 1).strip() + "…"; }

    /** The pictures as the model reads them at the end of the page's text: alt text, caption and address, one per line. */
    static String pictureList(List<Picture> pics) {
        StringBuilder b = new StringBuilder("PICTURES IN THIS PAGE:");
        int i = 1;
        for (Picture p : pics) {
            b.append('\n').append(i++).append(". ");
            if (!p.alt().isEmpty()) b.append("alt text: ").append(p.alt()).append("; ");
            if (!p.caption().isEmpty()) b.append("caption: ").append(p.caption()).append("; ");
            b.append("address: ").append(p.url());
        }
        return b.toString();
    }

    /** Crude but effective HTML → text: drop script/style/head-noise, strip tags, decode common entities. A content picture's alt text stays, as [picture: …]. */
    static String readable(String body) {
        if (body == null) return "";
        String s = body;
        if (s.contains("<")) {
            s = contentHtml(s);
            // every picture keeps its alt text in the text, where it stands: what a picture is named never takes its words away
            Matcher img = IMG.matcher(s);
            StringBuilder kept = new StringBuilder();
            while (img.find()) {
                String alt = attr(img.group(), "alt");
                img.appendReplacement(kept, Matcher.quoteReplacement(alt == null || alt.isBlank() ? " " : " [picture: " + alt.replace("<", "‹").replace(">", "›") + "] "));
            }
            img.appendTail(kept);
            s = kept.toString();
            // Keep table STRUCTURE: cells become ` | `-separated, rows become lines. Without this a wiki
            // table collapses into undifferentiated prose — the row for "Alabama" no longer looks like a
            // data row, the relevance excerpt can't match it, and the model reports a page that holds the
            // entire gold table as "does not contain the data" (measured: ws_en_064 fetched the right
            // list page three times and never saw a row).
            s = s.replaceAll("(?i)</t[dh]>", " | ");
            s = s.replaceAll("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>", "\n");
            // a tag ends at the first > outside its quoted attributes: a page that keeps its source in an attribute has > inside one
            s = s.replaceAll("(?s)<[a-zA-Z/!?](?:\"[^\"]*\"|'[^']*'|[^>\"'])*>", " ");
        }
        s = Entities.decode(s);
        s = s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n");
        return s.strip();
    }
}
