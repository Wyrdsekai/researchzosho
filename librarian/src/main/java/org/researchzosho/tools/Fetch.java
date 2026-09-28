package org.researchzosho.tools;

import org.researchzosho.Config;
import org.researchzosho.Stopping;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import java.util.Map;

import org.researchzosho.librarian.SourceRules;
/**
 * One HTTP GET for research: the address check, the redirect walk, the size cap, the decompression.
 * Every fetch of a URL a MODEL or a PAGE named goes through here — the web tool, {@code researchzosho add},
 * the Wikipedia title lookup — so the rules live in one place (Wyrdsekai, 2026-09-07: the fetch tool
 * had no address check; any URL the model named was fetched, redirects followed, body fully buffered).
 *
 * <p>Refused, at every hop: loopback, link-local (the cloud metadata address lives there), unspecified,
 * multicast, and the hosts of the library's own services — the drive, the embedder, the reranker, the
 * search backend — so a page cannot send the model to talk to them. Private (site-local) addresses are
 * allowed by default because a research library on a home network may shelve an internal wiki;
 * {@code RESEARCHZOSHO_FETCH_PRIVATE=deny} refuses those too.
 *
 * <p>Left out, at every hop, by the fetch's {@link Policy}: a site on the person's refused-sources list, and a site on the
 * {@link SiteList} of pornography, shock and gore sites. Fetch is shared by every run at once, so each call names its policy; the
 * signatures without one mean {@link Policy#DEFAULT}, both lists, so a caller that says nothing is checked against both.
 */
public final class Fetch {

    /**
     * Which lists a fetch is checked against, besides the address rules every fetch has. {@code siteList}: the {@link SiteList};
     * {@code refusedList}: the person's own refused sources ({@link SourceRules}).
     */
    public record Policy(boolean siteList, boolean refusedList) {
        /** Every address a model or a page names: both lists. */
        public static final Policy DEFAULT = new Policy(true, true);
        /** An address the person gave themselves (add, a reading list, bookmarks): their own act, so neither list. */
        public static final Policy PERSON = new Policy(false, false);
        /** A run whose person said yes to letting in what the site list leaves out, for that question: the refused list only. */
        public static final Policy SITE_LIST_OFF = new Policy(false, true);

        /** Why this address is left out under this policy, as the end of a sentence ("its site is on …"), or null when it is not. */
        public String leftOut(String url) {
            if (url == null || url.isBlank()) return null;
            if (refusedList && SourceRules.live().refused(url)) return "its site is on the person's refused-sources list";
            if (onSiteList(url)) return "its site is on " + SITE_LIST_NAME;
            return null;
        }

        /**
         * Whether the site list takes this address out under this policy. A site the person marked trusted never is: the person's own
         * list outranks somebody else's.
         */
        public boolean onSiteList(String url) { return siteList && url != null && SiteList.current().listed(url) && !SourceRules.live().trusted(url); }

        /** Whether the person's refused list takes this address out under this policy. */
        public boolean refused(String url) { return refusedList && url != null && SourceRules.live().refused(url); }
    }

    /** How the site list is named where a person or the model reads why something was left out. */
    public static final String SITE_LIST_NAME = "the list of pornography, shock and gore sites";

    /** The fetch was not made: the address is on a list its {@link Policy} checks. A refusal, so every caller that catches one catches this. */
    public static final class LeftOut extends IllegalArgumentException {
        private final String url, why;
        public LeftOut(String url, String why) { super("left out: " + url + " was not fetched, because " + why); this.url = url; this.why = why; }
        public String url() { return url; }
        /** The end of the sentence: "its site is on the list of pornography, shock and gore sites". */
        public String why() { return why; }
    }

    public static final String USER_AGENT = "Mozilla/5.0 (compatible; ResearchZosho/0.1; +https://researchzosho.org)";
    static final int MAX_BYTES = Config.getInt("RESEARCHZOSHO_FETCH_MAX_BYTES", 25_000_000);
    /**
     * The slowest a page may come, in bytes a second on average: a scan that comes steadily from a slow archive is read to its end, however
     * long that takes, and a server that sends a byte now and then is given up.
     */
    static volatile long MIN_BYTES_PER_SECOND = Config.getInt("RESEARCHZOSHO_FETCH_MIN_BYTES_PER_SECOND", 4096);
    static final int MAX_HOPS = 5;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** A fetched document: the final URL after redirects, the status, and the decompressed body (capped). */
    public record Result(String url, int status, byte[] body, String contentType) { }

    /** Tests point this at a local server; production never does. */
    static volatile boolean allowLoopback = false;

    private Fetch() { }

    /**
     * Why {@code uri} may not be fetched, or null when it may. Pure over the resolved address and the
     * configured service hosts, so a test can pin every rule without a network.
     */
    public static String refusal(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) return "no host in " + uri;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return "only http(s) is fetched, not " + scheme;
        for (String own : ownServiceHosts()) {
            if (own.equalsIgnoreCase(host)) return host + " is one of the library's own services";
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (Exception e) {
            return "cannot resolve " + host;
        }
        boolean denyPrivate = "deny".equalsIgnoreCase(Config.get("RESEARCHZOSHO_FETCH_PRIVATE", "allow"));
        for (InetAddress a : addrs) {
            if (a.isLoopbackAddress() && !allowLoopback) return host + " resolves to a loopback address";
            if (a.isLinkLocalAddress()) return host + " resolves to a link-local address";
            if (a.isAnyLocalAddress() || a.isMulticastAddress()) return host + " resolves to an unusable address";
            if (denyPrivate && a.isSiteLocalAddress()) return host + " resolves to a private address (RESEARCHZOSHO_FETCH_PRIVATE=deny)";
        }
        return null;
    }

    /**
     * {@code host:port} of each configured service, for a check that must tell one program on a
     * machine from another. A host that resolves to loopback is written {@code loopback}, so
     * {@code localhost:8888} and {@code 127.0.0.1:8888} are the same service; the port is the
     * scheme's default when the address names none.
     */
    public static List<String> ownServiceAuthorities() {
        List<String> out = new ArrayList<>();
        for (String key : new String[]{"RESEARCHZOSHO_DRIVE", "RESEARCHZOSHO_EMBED", "RESEARCHZOSHO_RERANK", "RESEARCHZOSHO_SEARXNG", "RESEARCHZOSHO_JOB_DRIVES"}) {
            String v = Config.get(key);
            if (v == null || v.isBlank() || v.equalsIgnoreCase("off")) continue;
            for (String one : v.split(",")) {
                try {
                    String a = authority(URI.create(one.strip()));
                    if (a != null) out.add(a);
                } catch (Exception ignored) {
                    // not a URL; nothing to protect
                }
            }
        }
        return out;
    }

    /** {@code host:port} of {@code uri} as {@link #ownServiceAuthorities()} writes it, or null without a host. */
    public static String authority(URI uri) {
        String h = uri.getHost();
        if (h == null || h.isBlank()) return null;
        int port = uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        try {
            if (InetAddress.getByName(h).isLoopbackAddress()) h = "loopback";
        } catch (Exception ignored) {
            // an unresolvable host is compared by name
        }
        return h.toLowerCase(Locale.ROOT) + ":" + port;
    }

    /** The hosts of the configured drive, embedder, reranker and search backend. */
    public static List<String> ownServiceHosts() {
        List<String> out = new ArrayList<>();
        for (String key : new String[]{"RESEARCHZOSHO_DRIVE", "RESEARCHZOSHO_EMBED", "RESEARCHZOSHO_RERANK", "RESEARCHZOSHO_SEARXNG", "RESEARCHZOSHO_JOB_DRIVES"}) {
            String v = Config.get(key);
            if (v == null || v.isBlank() || v.equalsIgnoreCase("off")) continue;
            for (String one : v.split(",")) {
                try {
                    String h = URI.create(one.strip()).getHost();
                    if (h != null && !h.isBlank()) out.add(h);
                } catch (Exception ignored) {
                    // not a URL; nothing to protect
                }
            }
        }
        return out;
    }

    /** GET {@code url}, walking redirects with the check at every hop, under {@link Policy#DEFAULT}. Throws on refusal or transport failure. */
    public static Result get(String url, Duration timeout) throws Exception { return get(url, timeout, Map.of(), Policy.DEFAULT); }

    /** GET {@code url} under {@code policy}: an address on a list it checks, at any hop, throws {@link LeftOut}. */
    public static Result get(String url, Duration timeout, Policy policy) throws Exception { return get(url, timeout, Map.of(), policy); }

    /** With extra request headers, sent to the first host only: a redirect to another host does not get somebody's token. */
    public static Result get(String url, Duration timeout, Map<String, String> headers) throws Exception { return get(url, timeout, headers, Policy.DEFAULT); }

    /** The same, under {@code policy}. */
    public static Result get(String url, Duration timeout, Map<String, String> headers, Policy policy) throws Exception {
        Policy p = policy == null ? Policy.DEFAULT : policy;
        String current = url;
        String firstHost = URI.create(url).getHost();
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            URI uri = URI.create(current);
            String left = p.leftOut(current);
            if (left != null) throw new LeftOut(current, left);
            String why = refusal(uri);
            if (why != null) throw new IllegalArgumentException("refused: " + why);
            HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", USER_AGENT).header("Accept-Encoding", "gzip");
            if (uri.getHost() != null && uri.getHost().equalsIgnoreCase(firstHost) && "https".equalsIgnoreCase(uri.getScheme())) for (var h : headers.entrySet()) rb.header(h.getKey(), h.getValue());
            // the answer is waited for under the run's stop and {@code timeout}; its body is read under the same stop for as long as it keeps
            // coming: nothing for {@code timeout}, or less than MIN_BYTES_PER_SECOND on average, ends the reading. Before, the body had no
            // limit at all, and a site that sent the head of a page and then nothing held a run for hours
            String site = "the site " + uri.getHost();
            HttpResponse<InputStream> resp = Stopping.send(HTTP, rb.GET().build(), HttpResponse.BodyHandlers.ofInputStream(), timeout, site);
            int status = resp.statusCode();
            if (status >= 300 && status < 400) {
                String loc = resp.headers().firstValue("Location").orElse(null);
                try (InputStream in = resp.body(); Stopping.Guard g = Stopping.body(in, timeout, MIN_BYTES_PER_SECOND, MAX_BYTES)) {
                    try { g.stream().skip(Long.MAX_VALUE); } finally { g.rethrow(site); }
                }
                if (loc == null) return new Result(current, status, new byte[0], "");
                current = uri.resolve(loc).toString();
                if (hop == MAX_HOPS) throw new IllegalStateException("too many redirects from " + url);
                continue;
            }
            byte[] body;
            try (InputStream in = resp.body(); Stopping.Guard g = Stopping.body(in, timeout, MIN_BYTES_PER_SECOND, MAX_BYTES)) {
                try { body = g.stream().readNBytes(MAX_BYTES + 1); } finally { g.rethrow(site); }
            }
            if (body.length > MAX_BYTES) throw new IllegalStateException("the body exceeds " + MAX_BYTES + " bytes; not read");
            String enc = resp.headers().firstValue("Content-Encoding").orElse("").toLowerCase(Locale.ROOT);
            return new Result(canonical(current), status, decode(body, enc), resp.headers().firstValue("Content-Type").orElse(""));
        }
        throw new IllegalStateException("too many redirects from " + url);
    }

    /** What a page IS when it is not the page: a cookie wall, a bot wall, a login wall, a not-found — or null. */
    public static String wall(String title, String text) {
        String t = (title == null ? "" : title).toLowerCase(Locale.ROOT);
        String head = (text == null ? "" : text).substring(0, Math.min(1200, text == null ? 0 : text.length())).toLowerCase(Locale.ROOT);
        String[][] walls = {
                {"cookies turned off", "a cookie wall"}, {"cookies not supported", "a cookie wall"}, {"enable cookies", "a cookie wall"},
                {"not a bot", "a bot wall"}, {"checking your browser", "a bot wall"}, {"recaptcha", "a bot wall"}, {"just a moment", "a bot wall"},
                {"verify you are human", "a bot wall"}, {"access denied", "an access-denied page"}, {"enable javascript", "a script wall"},
                {"please log in", "a login wall"}, {"sign in to continue", "a login wall"}, {"403 forbidden", "a 403 page"}, {"page not found", "a not-found page"}};
        for (String[] w : walls) {
            if (t.contains(w[0])) return w[1];
            if (head.contains(w[0]) && (text == null || text.length() < 2500)) return w[1];
        }
        return null;
    }

    /** A URL without its tracking and error parameters — the form two fetches of one page share. */
    public static String canonical(String url) {
        if (url == null) return null;
        try {
            URI u = URI.create(url.strip());
            String q = u.getRawQuery();
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            path = path.replaceFirst("^(/publication/\\d+)_.*$", "$1");   // researchgate: the id names the record, the slug is decoration
            String base = u.getScheme() + "://" + (u.getRawAuthority() == null ? "" : u.getRawAuthority()) + path;   // file:///… has no authority
            if (q == null || q.isBlank()) return base;
            StringBuilder keep = new StringBuilder();
            for (String p : q.split("&")) {
                String k = p.contains("=") ? p.substring(0, p.indexOf('=')).toLowerCase(Locale.ROOT) : p.toLowerCase(Locale.ROOT);
                if (k.startsWith("utm_") || k.equals("error") || k.equals("code") || k.equals("fbclid") || k.equals("gclid") || k.equals("ref") || k.equals("ref_src") || k.equals("cookies") || k.equals("cookieset")) continue;
                if (keep.length() > 0) keep.append('&');
                keep.append(p);
            }
            return keep.length() == 0 ? base : base + "?" + keep;
        } catch (Exception e) {
            return url.strip();
        }
    }

    /** Inflate a compressed body (some CDNs gzip even to a client that did not ask). Capped like the raw body. */
    static byte[] decode(byte[] body, String enc) {
        try {
            if (enc.contains("gzip") || (body.length > 2 && body[0] == (byte) 0x1f && body[1] == (byte) 0x8b)) {
                try (var in = new GZIPInputStream(new ByteArrayInputStream(body))) { return in.readNBytes(MAX_BYTES); }
            } else if (enc.contains("deflate")) {
                try (var in = new InflaterInputStream(new ByteArrayInputStream(body))) { return in.readNBytes(MAX_BYTES); }
            }
        } catch (Exception e) {
            // fall through with the raw bytes — worse than decoded, better than an exception
        }
        return body;
    }
}
