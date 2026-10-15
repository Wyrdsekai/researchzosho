package org.researchzosho.librarian;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * One-time codes from an authenticator app (RFC 6238: HMAC-SHA-1, six digits, a new code every thirty seconds), the second step a
 * person can put on their account. The shared secret is shown once as a QR code and as letters to type; the app and the library then
 * agree on the code of the moment. A code one step either side is taken too, for a phone whose clock is a little off.
 */
public final class Totp {
    private Totp() { }

    static final int PERIOD = 30, DIGITS = 6, WINDOW = 1;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** A new shared secret: twenty random bytes, as the base32 letters the apps take. */
    public static String newSecret() { byte[] b = new byte[20]; new SecureRandom().nextBytes(b); return base32(b); }

    /** The otpauth address the apps read from a QR code. */
    public static String uri(String issuer, String account, String secret) {
        String enc = URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20"), acc = URLEncoder.encode(account, StandardCharsets.UTF_8).replace("+", "%20");
        return "otpauth://totp/" + enc + ":" + acc + "?secret=" + secret + "&issuer=" + enc + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD;
    }

    /** The code of time step {@code step} (the Unix time divided by the period). */
    public static String code(String secret, long step) {
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) { msg[i] = (byte) (step & 0xff); step >>>= 8; }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32decode(secret), "HmacSHA1"));
            byte[] h = mac.doFinal(msg);
            int off = h[h.length - 1] & 0x0f;
            int bin = ((h[off] & 0x7f) << 24) | ((h[off + 1] & 0xff) << 16) | ((h[off + 2] & 0xff) << 8) | (h[off + 3] & 0xff);
            String s = String.valueOf(bin % 1_000_000);
            return "0".repeat(DIGITS - s.length()) + s;
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    /** The time step this code belongs to, within a step either side of now; -1 when it is no code of this secret. */
    public static long stepOf(String secret, String code, long nowMillis) {
        if (code == null) return -1;
        String c = code.replaceAll("[\\s-]", "");
        if (c.length() != DIGITS || !c.chars().allMatch(Character::isDigit)) return -1;
        long now = nowMillis / 1000 / PERIOD;
        for (long d = 0; d <= WINDOW; d++) {
            for (long step : new long[]{now - d, now + d}) if (code(secret, step).equals(c)) return step;
        }
        return -1;
    }

    static String base32(byte[] b) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte x : b) {
            buffer = (buffer << 8) | (x & 0xff); bits += 8;
            while (bits >= 5) { sb.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) sb.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    static byte[] base32decode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char ch : s.toUpperCase(Locale.ROOT).replaceAll("[\\s=-]", "").toCharArray()) {
            int v = ALPHABET.indexOf(ch);
            if (v < 0) throw new IllegalArgumentException("not base32: " + ch);
            buffer = (buffer << 5) | v; bits += 5;
            if (bits >= 8) { out.write((buffer >> (bits - 8)) & 0xff); bits -= 8; }
        }
        return out.toByteArray();
    }
}
