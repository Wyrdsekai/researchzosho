package org.researchzosho.tools;

import org.researchzosho.Config;

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
 */
public final class Fetch {

    public static final String USER_AGENT = "Mozilla/5.0 (compatible; ResearchZosho/0.1; +https://researchzosho.org)";
    static final int MAX_BYTES = Config.getInt("RESEARCHZOSHO_FETCH_MAX_BYTES", 25_000_000);
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

    /** GET {@code url}, walking redirects with the check at every hop. Throws on refusal or transport failure. */
    public static Result get(String url, Duration timeout) throws Exception {
        String current = url;
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            URI uri = URI.create(current);
            String why = refusal(uri);
            if (why != null) throw new IllegalArgumentException("refused: " + why);
            HttpResponse<InputStream> resp = HTTP.send(HttpRequest.newBuilder(uri)
                            .timeout(timeout)
                            .header("User-Agent", USER_AGENT)
                            .header("Accept-Encoding", "gzip")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            int status = resp.statusCode();
            if (status >= 300 && status < 400) {
                String loc = resp.headers().firstValue("Location").orElse(null);
                try (InputStream in = resp.body()) { in.skip(Long.MAX_VALUE); }
                if (loc == null) return new Result(current, status, new byte[0], "");
                current = uri.resolve(loc).toString();
                if (hop == MAX_HOPS) throw new IllegalStateException("too many redirects from " + url);
                continue;
            }
            byte[] body;
            try (InputStream in = resp.body()) {
                body = in.readNBytes(MAX_BYTES + 1);
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
