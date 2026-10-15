package org.researchzosho.librarian;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * A password kept as a salted, stretched hash (PBKDF2 with HMAC-SHA-256, 210,000 rounds, a 16-byte salt), which the JDK has; the
 * password itself is never written anywhere. The stored form names its own rounds, so they can rise later without touching old entries:
 * {@code pbkdf2-sha256$210000$<salt>$<hash>}.
 */
public final class Passwords {
    private Passwords() { }

    static final int ROUNDS = 210_000;
    static final int MIN = 8, MAX = 200;

    /** Why this password will not do, in a sentence; null when it will. */
    public static String why(String password) {
        if (password == null || password.length() < MIN) return "A password needs at least " + MIN + " characters.";
        if (password.length() > MAX) return "Keep the password under " + MAX + " characters.";
        return null;
    }

    public static String hash(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return "pbkdf2-sha256$" + ROUNDS + "$" + b64(salt) + "$" + b64(derive(password, salt, ROUNDS));
    }

    /** Whether the password is the one the stored form was made from; false for anything malformed. Takes the same time right or wrong. */
    public static boolean check(String stored, String password) {
        if (stored == null || password == null) return false;
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2-sha256")) return false;
        try {
            int rounds = Integer.parseInt(p[1]);
            byte[] salt = Base64.getUrlDecoder().decode(p[2]), want = Base64.getUrlDecoder().decode(p[3]);
            return MessageDigest.isEqual(want, derive(password, salt, rounds));
        } catch (IllegalArgumentException e) { return false; }
    }

    private static byte[] derive(String password, byte[] salt, int rounds) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(password.toCharArray(), salt, rounds, 256)).getEncoded();
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    /** A fresh secret of {@code bytes} random bytes, as text. */
    public static String secret(int bytes) { byte[] b = new byte[bytes]; new SecureRandom().nextBytes(b); return b64(b); }
}
