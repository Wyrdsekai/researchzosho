package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The names this computer goes by, for the two places the library trusts a browser for being on it (2026-11-04). A page on another site
 * can point a name of its own at 127.0.0.1; the browser then loads the library under that name, from this computer, and lets that page
 * read what it loads. So a library that listens on the loopback only answers to the loopback's own names, and a browser on this computer
 * is the person who keeps the library only when it asks by one of this computer's names. A request with no Host is no browser's, and goes
 * on as a program's call always did.
 */
final class LocalName {
    private LocalName() { }

    /** What a request under another name is answered with. */
    static final String REFUSED = "This library was asked for under a name that is not this computer's, so it was not served. Open it at "
            + "http://127.0.0.1 or http://localhost. A proxy on this computer that forwards to the library is named in RESEARCHZOSHO_TRUSTED_PROXY.";

    private static final Pattern LOOPBACK_V4 = Pattern.compile("127(?:\\.(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

    /** The name part of a Host header, lower-cased, without its port or a trailing dot: "[::1]" keeps its brackets; "" for none. */
    static String nameOf(String host) {
        if (host == null) return "";
        String h = host.strip().toLowerCase(Locale.ROOT);
        String n;
        if (h.startsWith("[")) { int close = h.indexOf(']'); n = close > 0 ? h.substring(0, close + 1) : h; }
        else { int colon = h.indexOf(':'); n = colon >= 0 ? h.substring(0, colon) : h; }
        return n.endsWith(".") ? n.substring(0, n.length() - 1) : n;
    }

    /** Whether a Host names the loopback: localhost, an address in 127.0.0.0/8, or [::1]. */
    static boolean loopback(String host) {
        String n = nameOf(host);
        return n.equals("localhost") || n.equals("[::1]") || LOOPBACK_V4.matcher(n).matches();
    }

    /** Whether a Host names this computer: a loopback name, or this computer's own host name, with or without ".local". */
    static boolean thisComputer(String host) {
        if (loopback(host)) return true;
        String n = nameOf(host), own = ownName();
        return !n.isEmpty() && !own.isEmpty() && (n.equals(own) || n.equals(own + ".local"));
    }

    /** Whether a request may be served by a library that listens on the loopback only: no Host (a program), or a loopback name. */
    static boolean servedHere(String host) { return host == null || host.isBlank() || loopback(host); }

    /** Whether an address a server listens on is a loopback one. */
    static boolean loopbackOnly(InetSocketAddress a) { return a != null && a.getAddress() != null && a.getAddress().isLoopbackAddress(); }

    private static volatile String own;
    /** This computer's own host name, lower-cased, "" when it cannot be read; read once. */
    static String ownName() {
        String o = own;
        if (o != null) return o;
        try { o = InetAddress.getLocalHost().getHostName().strip().toLowerCase(Locale.ROOT); } catch (IOException | RuntimeException e) { o = ""; }
        if (o.endsWith(".local")) o = o.substring(0, o.length() - ".local".length());
        own = o;
        return o;
    }

    static void refuse(HttpExchange x) throws IOException {
        byte[] b = (REFUSED + "\n").getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(403, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }
}
