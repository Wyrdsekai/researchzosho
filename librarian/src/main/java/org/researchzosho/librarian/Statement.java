package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * The statement the account service makes for one request (0.8.0): who is asking, which library, at what level, for a minute. A compact
 * token of three parts, signed ES256 with the service's key; the library checks the signature against the service's published key, that
 * the statement names this library and that its minute has not passed. It travels from the service to the library in the header
 * {@link #HEADER} and nowhere else: no browser and no program ever holds one.
 */
public final class Statement {
    private Statement() { }

    static final ObjectMapper M = new ObjectMapper();
    /** The header the statement rides in, from the account service to a library. */
    public static final String HEADER = "X-RZ-Statement";
    /** How long a statement stands, in seconds, and how far two machines' clocks may differ. */
    public static final long LIFE_S = 60, SKEW_S = 10;
    /** The three levels a statement can give, and the three ways the caller proved who they are. */
    public static final String READ = "read", WRITE = "write", KEEPER = "keeper", SESSION = "session", KEY = "key", KEY_READ = "key-read";
    /** How the service itself signs in to a library it runs: for the calls that are its own (is it still held, its night), never a person's. */
    public static final String SERVICE = "service";

    /** What a statement says: the service that made it, the library it is for, the account, its name, the level, how the caller signed in, and its minute. */
    public record Claims(String iss, String aud, String sub, String name, String lvl, String via, long iat, long exp, long epo) {
        /** A statement that says no epoch. */
        public Claims(String iss, String aud, String sub, String name, String lvl, String via, long iat, long exp) { this(iss, aud, sub, name, lvl, via, iat, exp, 0); }
        /** A statement for now, standing {@link #LIFE_S} seconds. */
        public static Claims now(String iss, String aud, String sub, String name, String lvl, String via) {
            long t = System.currentTimeMillis() / 1000;
            return new Claims(iss, aud, sub, name, lvl, via, t, t + LIFE_S, 0);
        }
        /** The same statement for one start of the library: its epoch, the count the registry keeps of its starts (0: none said). */
        public Claims epoch(long n) { return new Claims(iss, aud, sub, name, lvl, via, iat, exp, n); }
    }

    /** Where a library finds the service's public key by its id; null when the service has no such key. */
    public interface Keys { PublicKey key(String kid) throws IOException; }

    // ---- making ----

    /** A new signing key: P-256, as ES256 asks. */
    /** The type the spending plug-in's tokens carry in their header: a token of the same key that is not a statement. */
    public static final String SPEND_TYP = "rz-spend+jwt";

    public static KeyPair newKey() {
        try { KeyPairGenerator g = KeyPairGenerator.getInstance("EC"); g.initialize(new ECGenParameterSpec("secp256r1")); return g.generateKeyPair(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    /** The statement, signed: header, claims and signature, each base64url, with dots between. */
    public static String sign(PrivateKey key, String kid, Claims c) {
        try {
            ObjectNode h = M.createObjectNode(); h.put("alg", "ES256"); h.put("typ", "JWT"); h.put("kid", kid);
            ObjectNode p = M.createObjectNode();
            p.put("iss", c.iss()); p.put("aud", c.aud()); p.put("sub", c.sub()); p.put("name", c.name()); p.put("lvl", c.lvl()); p.put("via", c.via()); p.put("iat", c.iat()); p.put("exp", c.exp());
            if (c.epo() > 0) p.put("epo", c.epo());
            String signed = Oidc.b64url(M.writeValueAsBytes(h)) + "." + Oidc.b64url(M.writeValueAsBytes(p));
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initSign(key); s.update(signed.getBytes(StandardCharsets.US_ASCII));
            return signed + "." + Oidc.b64url(s.sign());
        } catch (GeneralSecurityException | IOException e) { throw new IllegalStateException("The statement could not be signed: " + e.getMessage(), e); }
    }

    /**
     * Any claims signed as a statement is (0.8.0, the spending plug-in's calls): a JWT, ES256 with the service's key, header
     * {@code {"alg":"ES256","typ":"JWT","kid":…}}, the claims as given.
     */
    public static String signJson(PrivateKey key, String kid, ObjectNode claims) { return signJson(key, kid, claims, "JWT"); }

    /** The same under a type of its own ({@link #SPEND_TYP} for the spending plug-in's calls): never read as a statement (round F). */
    public static String signJson(PrivateKey key, String kid, ObjectNode claims, String typ) {
        try {
            ObjectNode h = M.createObjectNode(); h.put("alg", "ES256"); h.put("typ", typ); h.put("kid", kid);
            String signed = Oidc.b64url(M.writeValueAsBytes(h)) + "." + Oidc.b64url(M.writeValueAsBytes(claims));
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initSign(key); s.update(signed.getBytes(StandardCharsets.US_ASCII));
            return signed + "." + Oidc.b64url(s.sign());
        } catch (GeneralSecurityException | IOException e) { throw new IllegalStateException("The claims could not be signed: " + e.getMessage(), e); }
    }

    // ---- checking ----

    /**
     * The claims of a statement this service signed, for this library, within its minute; an exception says in a sentence what failed.
     * Nothing of the token is believed before the signature checks.
     */
    public static Claims verify(String token, Keys keys, String iss, String aud) throws IOException {
        if (aud == null || aud.isBlank()) throw new IOException("The statement was made for another library.");
        return read(token, keys, iss, aud);
    }

    /** The service's own reading of a statement it made, whichever library it was for: signature, maker and minute are checked as a library checks them. */
    static Claims read(String token, Keys keys, String iss) throws IOException { return read(token, keys, iss, null); }

    private static Claims read(String token, Keys keys, String iss, String aud) throws IOException {
        if (token == null || token.isBlank()) throw new IOException("This library is reached through its account service, and the request carries no statement from it.");
        String[] parts = token.strip().split("\\.");
        if (parts.length != 3) throw new IOException("The statement is not in three parts.");
        JsonNode header, claims;
        byte[] sig;
        try { header = M.readTree(Oidc.b64decode(parts[0])); claims = M.readTree(Oidc.b64decode(parts[1])); sig = Oidc.b64decode(parts[2]); }
        catch (IOException | RuntimeException e) { throw new IOException("The statement cannot be read."); }
        if (!"ES256".equals(header.path("alg").asText(""))) throw new IOException("The statement is not signed the way the account service signs.");
        if (!"JWT".equals(header.path("typ").asText("JWT"))) throw new IOException("This is not a statement of the account service.");   // a plug-in's token, signed with the same key, is no statement
        PublicKey key = keys.key(header.path("kid").asText(""));
        if (key == null) throw new IOException("The statement is signed with a key the account service does not publish.");
        try {
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initVerify(key); s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!s.verify(sig)) throw new IOException("The statement's signature does not check against the account service's key.");
        } catch (GeneralSecurityException e) { throw new IOException("The statement's signature does not check against the account service's key."); }
        Claims c = new Claims(claims.path("iss").asText(""), claims.path("aud").asText(""), claims.path("sub").asText(""), claims.path("name").asText(""), claims.path("lvl").asText(""), claims.path("via").asText(""),
                claims.path("iat").asLong(0), claims.path("exp").asLong(0), claims.path("epo").asLong(0));
        if (!c.iss().equals(iss)) throw new IOException("The statement was made by another account service.");
        if (aud != null && !c.aud().equals(aud)) throw new IOException("The statement was made for another library.");
        long now = System.currentTimeMillis() / 1000;
        if (c.exp() + SKEW_S < now) throw new IOException("The statement has expired.");
        if (c.iat() - SKEW_S > now) throw new IOException("The statement is from the future; check the clocks.");
        if (c.sub().isBlank()) throw new IOException("The statement names no account.");
        if (!c.lvl().equals(READ) && !c.lvl().equals(WRITE) && !c.lvl().equals(KEEPER)) throw new IOException("The statement gives no level.");
        return c;
    }

    // ---- keys as text ----

    /** A public key as a JSON Web Key, with its id. */
    public static ObjectNode jwk(PublicKey key, String kid) {
        ECPublicKey ec = (ECPublicKey) key;
        ObjectNode o = M.createObjectNode();
        o.put("kty", "EC"); o.put("crv", "P-256"); o.put("alg", "ES256"); o.put("use", "sig"); o.put("kid", kid);
        o.put("x", Oidc.b64url(coordinate(ec.getW().getAffineX()))); o.put("y", Oidc.b64url(coordinate(ec.getW().getAffineY())));
        return o;
    }

    /** A coordinate as 32 bytes, as a key on P-256 is written. */
    private static byte[] coordinate(BigInteger v) {
        byte[] b = v.toByteArray(), out = new byte[32];
        int n = Math.min(b.length, 32);
        System.arraycopy(b, b.length - n, out, 32 - n, n);
        return out;
    }

    /** The public key a JSON Web Key holds; an exception when it is not a P-256 key. */
    public static PublicKey fromJwk(JsonNode jwk) throws IOException { return Oidc.publicKey(jwk, "ES256"); }

    public static String privateText(PrivateKey key) { return Base64.getEncoder().encodeToString(key.getEncoded()); }

    public static PrivateKey privateKey(String text) throws IOException {
        try { return KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(text.strip()))); }
        catch (GeneralSecurityException | RuntimeException e) { throw new IOException("The account service's signing key cannot be read."); }
    }
}
