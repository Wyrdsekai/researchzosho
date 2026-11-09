package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpExchange;
import org.researchzosho.Config;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * The proxy in front of the library (0.7.0). In the cloud the main port sits behind a reverse proxy or a load balancer that ends TLS
 * and adds the forwarded headers; on a home network it sits behind nothing. {@code RESEARCHZOSHO_TRUSTED_PROXY} names the proxies,
 * as addresses or networks (10.0.0.0/8, 127.0.0.1, fd00::/8), and only a request from one of them has its X-Forwarded-For,
 * X-Forwarded-Proto and relay identity headers believed; from anywhere else those headers mean nothing, since any program could
 * write them. Behind a trusted proxy the browser's address is the forwarded one, so a proxy on this same machine never passes for
 * the person who keeps the library, and the sign-in cookie is marked Secure when the proxy says the person came over https.
 */
public final class Proxy {
    private Proxy() { }

    public static final String SETTING = "RESEARCHZOSHO_TRUSTED_PROXY";
    /** Request attribute: set when the request came from a trusted proxy. */
    static final String PROXIED = "rz.proxied";
    /** Tests set this; null means read the setting. */
    static volatile String OVERRIDE;

    static String setting() { String o = OVERRIDE; return o != null ? o : Config.live(SETTING); }

    /** Whether this request came from a proxy the setting names. */
    static boolean behindTrusted(HttpExchange x) {
        InetSocketAddress r = x.getRemoteAddress();
        return r != null && r.getAddress() != null && trusted(r.getAddress(), setting());
    }

    static boolean proxied(HttpExchange x) { return Boolean.TRUE.equals(x.getAttribute(PROXIED)); }

    /** Whether an address is in the list: addresses and networks, separated by commas or spaces. */
    static boolean trusted(InetAddress a, String list) {
        if (list == null || list.isBlank()) return false;
        for (String item : list.split("[,\\s]+")) if (!item.isBlank() && matches(a, item.strip())) return true;
        return false;
    }

    /** An address against one entry: a literal address, or a network in CIDR form. A name is not looked up and matches nothing. */
    static boolean matches(InetAddress a, String entry) {
        String host = entry; int bits = -1;
        int slash = entry.indexOf('/');
        if (slash >= 0) {
            host = entry.substring(0, slash);
            try { bits = Integer.parseInt(entry.substring(slash + 1)); } catch (NumberFormatException e) { return false; }
        }
        if (!host.matches("[0-9a-fA-F:.]+")) return false;
        InetAddress n;
        try { n = InetAddress.getByName(host); } catch (UnknownHostException | IllegalArgumentException e) { return false; }
        byte[] x = a.getAddress(), y = n.getAddress();
        if (x.length != y.length) return false;
        if (bits < 0) bits = x.length * 8;
        if (bits < 0 || bits > x.length * 8) return false;
        for (int i = 0; i < bits; i++) { int m = 0x80 >>> (i % 8); if ((x[i / 8] & m) != (y[i / 8] & m)) return false; }
        return true;
    }

    /** The browser's address: behind a trusted proxy the last X-Forwarded-For entry, which that proxy appended; else the connection's. Null when unknown. */
    static InetAddress client(HttpExchange x) {
        if (proxied(x)) {
            String f = x.getRequestHeaders().getFirst("X-Forwarded-For");
            if (f != null && !f.isBlank()) {
                String[] parts = f.split(",");
                String last = parts[parts.length - 1].strip();
                if (last.matches("[0-9a-fA-F:.]+")) { try { return InetAddress.getByName(last); } catch (UnknownHostException | IllegalArgumentException ignored) { } }
            }
            return null;   // a trusted proxy that said nothing: not this machine, whatever the connection says
        }
        InetSocketAddress r = x.getRemoteAddress();
        return r == null ? null : r.getAddress();
    }

    /** Whether the person came over https: what a trusted proxy says in X-Forwarded-Proto; nothing otherwise. */
    static boolean https(HttpExchange x) {
        if (!proxied(x)) return false;
        String p = x.getRequestHeaders().getFirst("X-Forwarded-Proto");
        return p != null && p.strip().equalsIgnoreCase("https");
    }
}
