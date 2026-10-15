package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Passkeys (WebAuthn, the browser's {@code navigator.credentials}): a person signs in with their fingerprint, face, phone or security
 * key and nothing to type. The library is the relying party: it hands the browser a challenge, the browser's authenticator signs it
 * with a key it made for this address, and the library checks the signature against the public key it kept when the passkey was added.
 * ES256 and RS256 keys, the two every authenticator offers, with the JDK's own EC and RSA. The authenticator's make (attestation) is not
 * checked: a home library has no list of approved makers. A passkey is bound to the address it was made at (the rpId): one made at
 * {@code mylibrary.tail1234.ts.net} works there and nowhere else, which the account page says.
 */
public final class Passkeys {
    private Passkeys() { }

    private record Pending(String did, long made) { }
    private static final Map<String, Pending> CHALLENGES = new ConcurrentHashMap<>();
    static final int ES256 = -7, RS256 = -257;

    /** The relying party id: the host the browser is at, without the port. */
    public static String rpId(HttpExchange x) {
        String host = x.getRequestHeaders().getFirst("Host");
        if (host == null || host.isBlank()) return "localhost";
        host = host.strip();
        if (host.startsWith("[")) { int e = host.indexOf(']'); return e > 0 ? host.substring(1, e) : host; }
        int c = host.indexOf(':');
        return c > 0 ? host.substring(0, c) : host;
    }

    /** The origin the browser will name: https through the shared door, http on the main port (where only localhost is a secure context). */
    static String origin(HttpExchange x) {
        String host = x.getRequestHeaders().getFirst("Host");
        return (Pages.onDoor(x) ? "https://" : "http://") + (host == null ? "localhost" : host.strip());
    }

    private static String challenge(String did) {
        long cutoff = System.currentTimeMillis() - 5 * 60_000L;
        CHALLENGES.entrySet().removeIf(e -> e.getValue().made() < cutoff);
        String c = Passwords.secret(32);
        CHALLENGES.put(c, new Pending(did, System.currentTimeMillis()));
        return c;
    }

    /** What the browser needs to make a passkey for this account. */
    static ObjectNode registerOptions(LibraryStore store, Accounts.Account a, HttpExchange x) throws IOException {
        ObjectNode o = Accounts.M.createObjectNode();
        o.put("challenge", challenge(a.did()));
        ObjectNode rp = o.putObject("rp"); rp.put("id", rpId(x)); rp.put("name", "ResearchZosho " + store.identity().name());
        ObjectNode user = o.putObject("user"); user.put("id", Passwords.b64(a.did().getBytes(StandardCharsets.UTF_8))); user.put("name", a.login()); user.put("displayName", a.name());
        ArrayNode params = o.putArray("pubKeyCredParams");
        for (int alg : new int[]{ES256, RS256}) { ObjectNode p = params.addObject(); p.put("type", "public-key"); p.put("alg", alg); }
        ObjectNode sel = o.putObject("authenticatorSelection"); sel.put("residentKey", "preferred"); sel.put("userVerification", "preferred");
        o.put("attestation", "none");
        o.put("timeout", 120_000);
        ArrayNode ex = o.putArray("excludeCredentials");
        for (Accounts.Passkey k : a.passkeys()) { ObjectNode e = ex.addObject(); e.put("type", "public-key"); e.put("id", k.id()); }
        return o;
    }

    /** What the browser needs to sign in with any passkey made here. */
    static ObjectNode signInOptions(HttpExchange x) {
        ObjectNode o = Accounts.M.createObjectNode();
        o.put("challenge", challenge(""));
        o.put("rpId", rpId(x));
        o.put("userVerification", "preferred");
        o.put("timeout", 120_000);
        o.putArray("allowCredentials");
        return o;
    }

    /** The browser's new credential checked and kept on the account. */
    static void register(LibraryStore store, Accounts.Account a, HttpExchange x, JsonNode r) throws IOException {
        byte[] clientData = b64(r.path("response").path("clientDataJSON").asText(""));
        checkClientData(clientData, "webauthn.create", a.did(), x);
        Object att = Cbor.decode(b64(r.path("response").path("attestationObject").asText("")));
        if (!(att instanceof Map<?, ?> m) || !(m.get("authData") instanceof byte[] authData)) throw new IOException("The passkey's attestation has no authenticator data.");
        checkRpIdHash(authData, x);
        int flags = authData[32] & 0xff;
        if ((flags & 0x01) == 0) throw new IOException("The authenticator did not see the person (no user presence).");
        if ((flags & 0x40) == 0) throw new IOException("The authenticator sent no credential.");
        int at = 37 + 16;
        int idLen = ((authData[at] & 0xff) << 8) | (authData[at + 1] & 0xff);
        at += 2;
        byte[] id = Arrays.copyOfRange(authData, at, at + idLen);
        at += idLen;
        Cbor.Read key = Cbor.decodeFrom(authData, at);
        if (!(key.value() instanceof Map<?, ?> cose)) throw new IOException("The credential's key is not a COSE key.");
        int alg = ((Number) cose.get(3L)).intValue();
        PublicKey pk = publicKey(cose, alg);
        String idText = Passwords.b64(id);
        if (!idText.equals(r.path("id").asText("")) && !idText.equals(r.path("rawId").asText(""))) throw new IOException("The credential's id does not match what the browser named.");
        if (Accounts.byPasskey(store, idText) != null) throw new IOException("This passkey is on an account here already.");
        String label = SignIn.browser(r.path("label").asText(x.getRequestHeaders().getFirst("User-Agent")));
        Accounts.addPasskey(store, a.did(), new Accounts.Passkey(idText, Base64.getEncoder().encodeToString(pk.getEncoded()), alg, counter(authData), rpId(x), LocalDate.now().toString(), label));
    }

    /** The browser's assertion checked: the account whose passkey signed it. */
    static Accounts.Account signIn(LibraryStore store, HttpExchange x, JsonNode r) throws IOException {
        String id = r.path("id").asText("");
        Accounts.Account a = Accounts.byPasskey(store, id);
        if (a == null) throw new IOException("This passkey is not known here. It may have been made for another address, or removed.");
        Accounts.Passkey k = a.passkeys().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
        if (!k.rp().equals(rpId(x))) throw new IOException("This passkey was made for " + k.rp() + " and works there, not at " + rpId(x) + ".");
        byte[] clientData = b64(r.path("response").path("clientDataJSON").asText(""));
        checkClientData(clientData, "webauthn.get", "", x);
        byte[] authData = b64(r.path("response").path("authenticatorData").asText(""));
        checkRpIdHash(authData, x);
        if ((authData[32] & 0x01) == 0) throw new IOException("The authenticator did not see the person (no user presence).");
        byte[] signed = new byte[authData.length + 32];
        System.arraycopy(authData, 0, signed, 0, authData.length);
        System.arraycopy(sha256(clientData), 0, signed, authData.length, 32);
        try {
            PublicKey pk = KeyFactory.getInstance(k.alg() == RS256 ? "RSA" : "EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(k.key())));
            Signature s = Signature.getInstance(k.alg() == RS256 ? "SHA256withRSA" : "SHA256withECDSA");
            s.initVerify(pk);
            s.update(signed);
            if (!s.verify(b64(r.path("response").path("signature").asText("")))) throw new IOException("The passkey's signature does not check out.");
        } catch (GeneralSecurityException e) { throw new IOException("The passkey's signature could not be checked: " + e.getMessage()); }
        long count = counter(authData);
        if (k.count() > 0 && count > 0 && count <= k.count()) throw new IOException("This passkey's counter went backwards, which a copy of it would do. Remove it from the account page and add it again.");
        if (count > 0) Accounts.countPasskey(store, id, count);
        return a;
    }

    private static void checkClientData(byte[] clientData, String type, String did, HttpExchange x) throws IOException {
        JsonNode c = Accounts.M.readTree(new String(clientData, StandardCharsets.UTF_8));
        if (!type.equals(c.path("type").asText(""))) throw new IOException("The browser sent a " + c.path("type").asText("") + ", not a " + type + ".");
        Pending p = CHALLENGES.remove(c.path("challenge").asText(""));
        if (p == null || System.currentTimeMillis() - p.made() > 5 * 60_000L) throw new IOException("The challenge is not one the library gave in the last five minutes. Try again.");
        if (!p.did().equals(did)) throw new IOException("The challenge was given for another step.");
        String origin = c.path("origin").asText("");
        if (!origin.equalsIgnoreCase(origin(x))) throw new IOException("The passkey was used at " + origin + ", and this page is " + origin(x) + ".");
    }

    private static void checkRpIdHash(byte[] authData, HttpExchange x) throws IOException {
        if (authData.length < 37) throw new IOException("The authenticator data is too short.");
        byte[] want = sha256(rpId(x).getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(want, Arrays.copyOfRange(authData, 0, 32))) throw new IOException("The passkey is for another address, not " + rpId(x) + ".");
    }

    private static long counter(byte[] authData) {
        return ((long) (authData[33] & 0xff) << 24) | ((authData[34] & 0xff) << 16) | ((authData[35] & 0xff) << 8) | (authData[36] & 0xff);
    }

    /** A COSE key (RFC 9053) as the JDK's public key: EC2 on P-256 for ES256, RSA for RS256. */
    static PublicKey publicKey(Map<?, ?> cose, int alg) throws IOException {
        try {
            long kty = ((Number) cose.get(1L)).longValue();
            if (alg == ES256) {
                if (kty != 2 || ((Number) cose.get(-1L)).longValue() != 1) throw new IOException("The passkey's key is not on P-256.");
                AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
                ap.init(new ECGenParameterSpec("secp256r1"));
                ECParameterSpec spec = ap.getParameterSpec(ECParameterSpec.class);
                ECPoint w = new ECPoint(new BigInteger(1, (byte[]) cose.get(-2L)), new BigInteger(1, (byte[]) cose.get(-3L)));
                return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, spec));
            }
            if (alg == RS256) {
                if (kty != 3) throw new IOException("The passkey's key is not an RSA key.");
                return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(1, (byte[]) cose.get(-1L)), new BigInteger(1, (byte[]) cose.get(-2L))));
            }
            throw new IOException("The passkey uses an algorithm the library does not take (" + alg + "); ES256 and RS256 are taken.");
        } catch (GeneralSecurityException | ClassCastException | NullPointerException e) { throw new IOException("The passkey's key could not be read: " + e); }
    }

    static byte[] sha256(byte[] b) {
        try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static byte[] b64(String s) {
        try { return Base64.getUrlDecoder().decode(s.strip()); } catch (IllegalArgumentException e) { try { return Base64.getDecoder().decode(s.strip()); } catch (IllegalArgumentException e2) { return new byte[0]; } }
    }

    /** For tests: a challenge the library will accept, as the browser would have been given. */
    static String challengeForTest(String did) { return challenge(did); }

    static List<String> algorithms() { return List.of("ES256", "RS256"); }
}
