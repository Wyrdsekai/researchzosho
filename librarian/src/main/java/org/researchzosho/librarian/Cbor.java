package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough CBOR (RFC 8949) to read what a passkey hands over: the attestation object and the credential's public key. Integers,
 * byte and text strings, arrays, maps, the simple values and tags (passed through). Nothing is written.
 */
final class Cbor {
    private final byte[] in;
    private int at;

    private Cbor(byte[] in) { this.in = in; }

    /** The one value the bytes hold. */
    static Object decode(byte[] bytes) throws IOException {
        Cbor c = new Cbor(bytes);
        Object v = c.next();
        return v;
    }

    /** The one value, and where it ended (the credential's public key is followed by extensions). */
    record Read(Object value, int end) { }

    static Read decodeFrom(byte[] bytes, int offset) throws IOException {
        Cbor c = new Cbor(bytes);
        c.at = offset;
        Object v = c.next();
        return new Read(v, c.at);
    }

    private Object next() throws IOException {
        int b = u8();
        int major = b >> 5, info = b & 31;
        switch (major) {
            case 0: return length(info);
            case 1: return -1 - length(info);
            case 2: { long n = length(info); byte[] out = new byte[(int) n]; System.arraycopy(in, at, out, 0, (int) n); at += n; return out; }
            case 3: { long n = length(info); String s = new String(in, at, (int) n, StandardCharsets.UTF_8); at += n; return s; }
            case 4: { long n = length(info); List<Object> l = new ArrayList<>(); for (long i = 0; i < n; i++) l.add(next()); return l; }
            case 5: { long n = length(info); Map<Object, Object> m = new LinkedHashMap<>(); for (long i = 0; i < n; i++) { Object k = next(); m.put(k instanceof Long ? k : String.valueOf(k), next()); } return m; }
            case 6: { length(info); return next(); }   // a tag: the value it marks
            default: {
                switch (info) {
                    case 20: return Boolean.FALSE;
                    case 21: return Boolean.TRUE;
                    case 22: case 23: return null;
                    case 24: u8(); return null;
                    case 25: at += 2; return null;
                    case 26: at += 4; return null;
                    case 27: at += 8; return null;
                    default: throw new IOException("CBOR: a simple value the library does not read (" + info + ")");
                }
            }
        }
    }

    private long length(int info) throws IOException {
        if (info < 24) return info;
        switch (info) {
            case 24: return u8();
            case 25: return (u8() << 8) | u8();
            case 26: return ((long) u8() << 24) | (u8() << 16) | (u8() << 8) | u8();
            case 27: { long v = 0; for (int i = 0; i < 8; i++) v = (v << 8) | u8(); return v; }
            default: throw new IOException("CBOR: an indefinite length, which a passkey never sends");
        }
    }

    private int u8() throws IOException {
        if (at >= in.length) throw new IOException("CBOR: the bytes end early");
        return in[at++] & 0xff;
    }
}
