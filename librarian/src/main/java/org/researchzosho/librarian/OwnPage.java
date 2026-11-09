package org.researchzosho.librarian;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a form came from the library's own page (0.7.0). The pages act for whoever has them open: the person who keeps the library
 * while the pages are open, or while a browser on this computer is the keeper, or the person signed in. A page on another site that such
 * a person opens could post a form here, and their browser would carry it in their name: add an MCP server that starts a program on this
 * computer, change a setting, make a key, file a run. So every form posted to a page is checked before anything else is read or done:
 * the browser says where it came from, in Sec-Fetch-Site when it sends that, else in Origin, else in Referer, and it must be the library's
 * own address as the browser sees it, which behind a trusted proxy is the host and scheme the proxy forwards. A request that says none of
 * them is no browser's form from another site, since a browser names the origin of every form it posts across sites, and goes on as
 * before; so does a program's call that carries a bearer key or token, which no browser adds on its own. A form from elsewhere is
 * answered 403 with one sentence, and nothing of it is done.
 */
final class OwnPage {
    private OwnPage() { }

    /** What a form from another site is answered with. */
    static final String REFUSED = "This form came from another site, so it was not carried out.";

    private static final Pattern ORIGIN = Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*)://([^/?#\\s]*)");

    /** Whether the request may go on: a read, a program's call with a bearer, or a form from the library's own page. */
    static boolean allowed(HttpExchange x) {
        String method = x.getRequestMethod();
        if (method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) return true;
        Headers h = x.getRequestHeaders();
        String auth = h.getFirst("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) return true;   // a program's key or token: a browser never adds one on its own
        String site = h.getFirst("Sec-Fetch-Site");
        if (site != null && !site.isBlank()) {   // the browser's own word on where the request came from, which no page can change
            String s = site.strip().toLowerCase(Locale.ROOT);
            return s.equals("same-origin") || s.equals("none");
        }
        String origin = h.getFirst("Origin");
        if (origin != null && !origin.isBlank()) return own(origin, x);
        String referer = h.getFirst("Referer");
        if (referer != null && !referer.isBlank()) return own(referer, x);
        return true;
    }

    /** Whether an origin, or the address of the page a form was on, is the library's own as the browser sees it. "null" never is. */
    static boolean own(String address, HttpExchange x) {
        Matcher m = ORIGIN.matcher(address.strip());
        if (!m.lookingAt()) return false;
        String scheme = m.group(1).toLowerCase(Locale.ROOT), authority = m.group(2).toLowerCase(Locale.ROOT);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        if (authority.isEmpty()) return false;
        String[] here = here(x);
        if (here[0].isEmpty()) return false;
        if (!here[1].isEmpty() && !here[1].equals(scheme)) return false;   // the scheme counts where a trusted proxy or the door says it
        String s = here[1].isEmpty() ? scheme : here[1];
        return withoutDefaultPort(here[0], s).equals(withoutDefaultPort(authority, scheme));
    }

    /**
     * The library's own address as the browser sees it: {host[:port], scheme}, lower-cased, the scheme "" when nothing trustworthy says it.
     * Behind a proxy that RESEARCHZOSHO_TRUSTED_PROXY names, and on the shared door, the forwarded host and scheme when they are sent;
     * else the Host the request came with.
     */
    static String[] here(HttpExchange x) {
        Headers h = x.getRequestHeaders();
        String host = h.getFirst("Host"), scheme = "";
        if (Proxy.proxied(x) || Pages.onDoor(x)) {
            String fh = last(h.getFirst("X-Forwarded-Host")), fp = last(h.getFirst("X-Forwarded-Proto"));
            if (!fh.isEmpty()) host = fh;
            if (!fp.isEmpty()) scheme = fp.toLowerCase(Locale.ROOT);
        }
        return new String[]{host == null ? "" : host.strip().toLowerCase(Locale.ROOT), scheme};
    }

    /** The last of a header's comma-separated values: the one the nearest proxy wrote. */
    static String last(String v) {
        if (v == null || v.isBlank()) return "";
        String[] parts = v.split(",");
        return parts[parts.length - 1].strip();
    }

    static String withoutDefaultPort(String hostPort, String scheme) {
        String port = scheme.equals("https") ? ":443" : scheme.equals("http") ? ":80" : null;
        return port != null && hostPort.endsWith(port) ? hostPort.substring(0, hostPort.length() - port.length()) : hostPort;
    }

    /** The answer to a form from another site: 403 and the one sentence. */
    static void refuse(HttpExchange x) throws IOException {
        byte[] b = (REFUSED + "\n").getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(403, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }
}
