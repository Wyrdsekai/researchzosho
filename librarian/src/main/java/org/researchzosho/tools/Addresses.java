package org.researchzosho.tools;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Which addresses a library does not reach on the web: the one list, for the fetcher ({@link Fetch}), the webhooks, and the gate a
 * library in a container reaches the web through (0.8.0). An address is sorted into what it is, and each caller says which kinds
 * it refuses: the gate refuses all of them; the fetcher at home allows the private ones.
 */
public final class Addresses {
    private Addresses() { }

    /** What an address is, when it is one a library may be kept from. */
    public enum Kind {
        /** 127.0.0.0/8, ::1: this machine. */
        LOOPBACK,
        /** 169.254.0.0/16 and fe80::/10, where the cloud's metadata service answers (169.254.169.254; fd00:ec2::254 is PRIVATE). */
        LINK_LOCAL,
        /** Nowhere to go: 0.0.0.0/8, ::, multicast (224.0.0.0/4, ff00::/8), 240.0.0.0/4 and the broadcast address. */
        UNUSABLE,
        /** A private network: 10/8, 172.16/12, 192.168/16, the shared 100.64/10 carriers and tailnets use, fec0::/10, fc00::/7. */
        PRIVATE
    }

    /**
     * What {@code a} is, or null for an address on the open internet. An IPv6 address that carries an IPv4 one (::a.b.c.d, NAT64's
     * 64:ff9b::/96, 6to4's 2002::/16; ::ffff:a.b.c.d Java gives as IPv4 already) is what the IPv4 address in it is.
     */
    public static Kind kind(InetAddress a) {
        if (a == null) return Kind.UNUSABLE;
        if (a instanceof Inet6Address && embedded(a.getAddress()) != null) return kind(embedded(a.getAddress()));
        if (a.isLoopbackAddress()) return Kind.LOOPBACK;
        if (a.isLinkLocalAddress()) return Kind.LINK_LOCAL;
        if (a.isAnyLocalAddress() || a.isMulticastAddress()) return Kind.UNUSABLE;
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff;
            if (first == 0 || first >= 240) return Kind.UNUSABLE;
            if (a.isSiteLocalAddress() || (first == 100 && (b[1] & 0xc0) == 64)) return Kind.PRIVATE;
            return null;
        }
        if (a.isSiteLocalAddress() || (b[0] & 0xfe) == 0xfc) return Kind.PRIVATE;
        return null;
    }

    /** The IPv4 address an IPv6 address carries, or null. */
    static InetAddress embedded(byte[] b) {
        if (b.length != 16) return null;
        boolean compatible = true;
        for (int i = 0; i < 12; i++) if (b[i] != 0) { compatible = false; break; }
        if (compatible && !(b[12] == 0 && b[13] == 0 && b[14] == 0 && (b[15] == 0 || b[15] == 1))) return v4(Arrays.copyOfRange(b, 12, 16));   // ::a.b.c.d, not :: or ::1
        boolean nat64 = (b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b;
        for (int i = 4; nat64 && i < 12; i++) if (b[i] != 0) nat64 = false;
        if (nat64) return v4(Arrays.copyOfRange(b, 12, 16));
        if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) return v4(Arrays.copyOfRange(b, 2, 6));
        return null;
    }

    private static InetAddress v4(byte[] four) {
        try { return Inet4Address.getByAddress(four); } catch (UnknownHostException e) { return null; }
    }

    /**
     * The address a host stands for when it is written as numbers, read off its letters with no name looked up; null for anything
     * else (a name, numbers that are no address). The forms: four numbers with dots, each at most 255; one number of up to 32 bits,
     * as a browser reads one; an IPv6 address, in brackets or not, a zone after {@code %} left off. A caller that must never ask a
     * resolver (a page being written) reads a host with this and with nothing that takes a name.
     */
    public static InetAddress numeric(String host) {
        if (host == null) return null;
        String h = host.strip();
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        try {
            if (h.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                String[] parts = h.split("\\.");
                byte[] b = new byte[4];
                for (int i = 0; i < 4; i++) { int v = Integer.parseInt(parts[i]); if (v > 255) return null; b[i] = (byte) v; }
                return InetAddress.getByAddress(b);
            }
            if (h.matches("\\d{1,10}")) {
                long v = Long.parseLong(h);
                return v > 0xffffffffL ? null : InetAddress.getByAddress(new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v});
            }
            int zone = h.indexOf('%');
            String six = zone >= 0 ? h.substring(0, zone) : h;
            // hexadecimal digits, colons and the dots of an IPv4 address at its end, beginning with a digit or a colon: such a text
            // the JDK reads as an address or refuses, and never looks up
            if (six.indexOf(':') < 0 || !six.matches("[0-9A-Fa-f:][0-9A-Fa-f:.]*")) return null;
            return InetAddress.getByName(six);
        } catch (UnknownHostException | RuntimeException notAnAddress) { return null; }
    }

    /**
     * Whether a host is written as an address: four numbers with dots, each at most 255, or an IPv6 address, in brackets or not, a
     * zone after it or none; read off its letters as {@link #numeric} reads it. Such a host is checked without asking DNS, and a
     * caller hands it to the JDK to be read, which looks none of these up. Anything else is a name: also a text that holds a colon
     * and is no address, and numbers that are none (until the last reading of 0.8.1 every text of letters with a colon in it
     * counted, as did four numbers over 255, and the JDK looks such a text up when it is handed one; no caller had one to hand
     * over, a request's host being a name without a colon or an address its own reader checked).
     */
    public static boolean literal(String host) {
        if (host == null) return false;
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.isEmpty() || h.indexOf('[') >= 0 || h.indexOf(']') >= 0 || !h.equals(h.strip())) return false;
        return (h.matches("\\d{1,3}(\\.\\d{1,3}){3}") || h.indexOf(':') >= 0) && numeric(h) != null;
    }

    /** The setting a library in a container is given: its web goes through its node's gate, which checks every address itself. */
    public static final String THROUGH_GATE = "RESEARCHZOSHO_WEB_GATE";
}
