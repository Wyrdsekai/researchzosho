package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.researchzosho.Config;
import org.researchzosho.Version;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in through an identity provider (0.7.0): Google Workspace, Entra, Okta, Dex, anything that speaks OpenID Connect. The library is
 * a client with Authorization Code and PKCE: {@code /login/oidc} sends the browser to the provider named in {@code RESEARCHZOSHO_OIDC_ISSUER}
 * with the client id, a state, a nonce and a code challenge; the provider sends it back to {@code /login/oidc/callback} with a code; the
 * library trades the code for an identity token at the provider's token endpoint and checks the token against the provider's published
 * keys, its issuer, its audience, its time and the nonce. The person the token names is then an account here: the one bound to this
 * provider and subject before, else a listed reader with that verified mail, else, when the mail's domain is in
 * {@code RESEARCHZOSHO_OIDC_ALLOWED_DOMAIN}, a new reader the keeper may raise. The bindings are kept in {@code catalog/oidc-identities.json}.
 */
public final class Oidc {
    private Oidc() { }

    static final ObjectMapper M = new ObjectMapper();
    public static final String ISSUER = "RESEARCHZOSHO_OIDC_ISSUER", CLIENT_ID = "RESEARCHZOSHO_OIDC_CLIENT_ID", CLIENT_SECRET = "RESEARCHZOSHO_OIDC_CLIENT_SECRET",
            ALLOWED_DOMAIN = "RESEARCHZOSHO_OIDC_ALLOWED_DOMAIN", NAME = "RESEARCHZOSHO_OIDC_NAME", REDIRECT = "RESEARCHZOSHO_OIDC_REDIRECT";
    static final String CALLBACK = "/login/oidc/callback";
    /** Tests set this; null means read the settings. */
    static volatile Map<String, String> OVERRIDE;
    private static final long PENDING_MS = 10 * 60_000L, CACHE_MS = 60 * 60_000L, SKEW_S = 60;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

    static String setting(String key) { Map<String, String> o = OVERRIDE; return o != null ? o.get(key) : Config.live(key); }
    static boolean blank(String s) { return s == null || s.isBlank(); }

    /** Whether a provider is named with a client id: then the sign-in page offers it. */
    public static boolean configured() { return !blank(setting(ISSUER)) && !blank(setting(CLIENT_ID)); }

    /** The half-made case, for serve to say at start: a sentence, or null when the settings are whole or absent. */
    public static String halfConfigured() {
        boolean issuer = !blank(setting(ISSUER)), id = !blank(setting(CLIENT_ID));
        if (issuer == id) return null;
        return issuer ? "Sign-in through an identity provider is half set: " + ISSUER + " names one, but " + CLIENT_ID + " is empty, so the sign-in page does not offer it."
                      : "Sign-in through an identity provider is half set: " + CLIENT_ID + " is set, but " + ISSUER + " is empty, so the sign-in page does not offer it.";
    }

    /** What the sign-in button says: the setting, else the provider's host. */
    public static String name() {
        String n = setting(NAME);
        if (!blank(n)) return n.strip();
        try { String h = URI.create(setting(ISSUER).strip()).getHost(); return h == null ? "your identity provider" : h; } catch (RuntimeException e) { return "your identity provider"; }
    }

    // ---- the provider's documents ----

    record Discovery(String issuer, String authorizationEndpoint, String tokenEndpoint, String jwksUri, long fetched) { }
    private static volatile Discovery discovery;
    private record Keys(JsonNode keys, long fetched) { }
    private static volatile Keys keys;

    static synchronized Discovery discovery() throws IOException {
        String issuer = setting(ISSUER);
        if (blank(issuer)) throw new IOException("No identity provider is set (" + ISSUER + ").");
        issuer = issuer.strip().replaceAll("/+$", "");
        Discovery d = discovery;
        if (d != null && d.issuer().equals(issuer) && System.currentTimeMillis() - d.fetched() < CACHE_MS) return d;
        JsonNode j = getJson(issuer + "/.well-known/openid-configuration");
        String said = j.path("issuer").asText("").replaceAll("/+$", "");
        if (!said.equals(issuer)) throw new IOException("The provider at " + issuer + " says its issuer is " + said + "; the setting must name it as it names itself.");
        for (String k : new String[]{"authorization_endpoint", "token_endpoint", "jwks_uri"}) if (blank(j.path(k).asText(""))) throw new IOException("The provider's discovery document has no " + k + ".");
        d = new Discovery(issuer, j.path("authorization_endpoint").asText(), j.path("token_endpoint").asText(), j.path("jwks_uri").asText(), System.currentTimeMillis());
        discovery = d;
        return d;
    }

    private static synchronized JsonNode jwks(Discovery d, boolean fresh) throws IOException {
        Keys k = keys;
        if (!fresh && k != null && System.currentTimeMillis() - k.fetched() < CACHE_MS) return k.keys();
        JsonNode j = getJson(d.jwksUri());
        keys = new Keys(j.path("keys"), System.currentTimeMillis());
        return keys.keys();
    }

    private static JsonNode getJson(String url) throws IOException {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Accept", "application/json").header("User-Agent", userAgent()).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) throw new IOException(url + " answered " + r.statusCode() + ".");
            return M.readTree(r.body());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }

    static String userAgent() { return "ResearchZosho/" + Version.string() + " (+https://researchzosho.org)"; }

    // ---- the flow ----

    private record Pending(String nonce, String verifier, String redirect, long made) { }
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

    /** GET /login/oidc: the address at the provider to send the browser to, the state and the code verifier kept here for ten minutes. */
    static String start(HttpExchange x) throws IOException {
        Discovery d = discovery();
        String state = random(24), nonce = random(24), verifier = random(48);
        String challenge = b64url(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        String redirect = redirectUri(x);
        long now = System.currentTimeMillis();
        PENDING.entrySet().removeIf(e -> now - e.getValue().made() > PENDING_MS);
        PENDING.put(state, new Pending(nonce, verifier, redirect, now));
        return d.authorizationEndpoint() + (d.authorizationEndpoint().contains("?") ? "&" : "?") + "response_type=code&client_id=" + enc(setting(CLIENT_ID).strip()) + "&redirect_uri=" + enc(redirect)
                + "&scope=" + enc("openid email profile") + "&state=" + state + "&nonce=" + nonce + "&code_challenge=" + challenge + "&code_challenge_method=S256";
    }

    /** Where the provider sends the browser back: the setting, else this library's own address as the browser named it. */
    static String redirectUri(HttpExchange x) {
        String set = setting(REDIRECT);
        if (!blank(set)) return set.strip();
        String host = x.getRequestHeaders().getFirst("Host");
        return (Pages.secure(x) ? "https://" : "http://") + (host == null ? "localhost" : host.strip()) + CALLBACK;
    }

    /** GET /login/oidc/callback: the account the provider's answer proves, bound or made; an exception says why not, for the sign-in page. */
    static Accounts.Account finish(LibraryStore store, Map<String, String> q) throws IOException {
        String error = q.get("error");
        if (!blank(error)) throw new IOException("The identity provider did not sign you in: " + error + (blank(q.get("error_description")) ? "." : ", " + q.get("error_description") + "."));
        String state = q.get("state"), code = q.get("code");
        Pending p = blank(state) ? null : PENDING.remove(state);
        if (p == null) throw new IOException("This sign-in was not started here, or took longer than ten minutes. Try again.");
        if (blank(code)) throw new IOException("The identity provider sent no code back.");
        Discovery d = discovery();
        String secret = setting(CLIENT_SECRET);
        String form = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(p.redirect()) + "&client_id=" + enc(setting(CLIENT_ID).strip()) + "&code_verifier=" + enc(p.verifier())
                + (blank(secret) ? "" : "&client_secret=" + enc(secret.strip()));
        JsonNode t;
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(d.tokenEndpoint())).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                    .header("User-Agent", userAgent()).POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            t = M.readTree(r.body());
            if (r.statusCode() != 200 || blank(t.path("id_token").asText(""))) throw new IOException("The identity provider gave no identity token (" + r.statusCode() + (blank(t.path("error").asText("")) ? "" : ", " + t.path("error").asText()) + ").");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        JsonNode claims = verify(t.path("id_token").asText(), d, p.nonce());
        return account(store, d.issuer(), claims);
    }

    /** The account the token's person has here: bound before, a listed reader by verified mail, or new from an allowed domain. */
    static synchronized Accounts.Account account(LibraryStore store, String issuer, JsonNode claims) throws IOException {
        String sub = claims.path("sub").asText("").strip();
        if (sub.isEmpty()) throw new IOException("The identity token names no subject.");
        String email = claims.path("email").asText("").strip().toLowerCase(Locale.ROOT), name = claims.path("name").asText("").strip();
        boolean verified = claims.path("email_verified").asBoolean(false);
        String did = boundDid(store, issuer, sub);
        if (did != null) {
            if (!Accounts.OWNER.equals(did)) { Patrons.Entry e = Patrons.byDid(store, did); if (e == null || e.level() == Patrons.Level.deny) throw new IOException("Your account is no longer a reader of this library. Ask the person who keeps it."); }
            Accounts.Account a = Accounts.byDid(store, did);
            if (a != null) return a;
        }
        if (did == null && !email.isEmpty() && verified) {
            Patrons.Entry e = Patrons.byDid(store, Patrons.loginDid(email));
            if (e != null && e.level() != Patrons.Level.deny) did = e.did();
            if (did == null) { Accounts.Account o = Accounts.owner(store); if (o != null && o.login().equals(Accounts.login(email))) did = Accounts.OWNER; }
        }
        if (did == null) {
            if (email.isEmpty() || !verified) throw new IOException("The identity provider gave no verified mail address for you, and no account here is bound to this sign-in. Ask the person who keeps this library to let you in.");
            String domain = email.substring(email.indexOf('@') + 1);
            if (!allowed(domain)) throw new IOException("Mail addresses at " + domain + " are not let in here. Ask the person who keeps this library.");
            did = Patrons.loginDid(email);
            Patrons.set(store, did, name.isEmpty() ? email : name, Patrons.Level.read);   // a first sign-in from an allowed domain: a reader, whom the keeper may raise
        }
        Accounts.Account a = Accounts.byDid(store, did);
        if (a == null) {
            String n = name.isEmpty() ? email : name;
            try { a = Accounts.createHashed(store, did, n, null); }
            catch (IOException taken) { a = Accounts.createHashed(store, did, email.isEmpty() ? did : email, null); }   // the name is another account's: the mail tells them apart
        }
        bind(store, issuer, sub, did, email);
        return a;
    }

    static boolean allowed(String domain) {
        String set = setting(ALLOWED_DOMAIN);
        if (blank(set)) return false;
        for (String d : set.toLowerCase(Locale.ROOT).split("[,\\s]+")) if (!d.isBlank() && (d.equals(domain) || domain.endsWith("." + d))) return true;
        return false;
    }

    // ---- the token ----

    /** The claims of an identity token that this provider signed, for this client, now, with this nonce; an exception says what failed. */
    static JsonNode verify(String token, Discovery d, String nonce) throws IOException {
        String[] parts = token.split("\\.");
        if (parts.length != 3) throw new IOException("The identity token is not in three parts.");
        JsonNode header = M.readTree(b64decode(parts[0])), claims = M.readTree(b64decode(parts[1]));
        String alg = header.path("alg").asText(""), kid = header.path("kid").asText("");
        if (!alg.equals("RS256") && !alg.equals("ES256")) throw new IOException("The identity token is signed with " + alg + "; RS256 and ES256 are taken.");
        PublicKey key = key(d, kid, alg, false);
        if (key == null) key = key(d, kid, alg, true);
        if (key == null) throw new IOException("The provider's published keys have none the token names" + (kid.isEmpty() ? "" : " (" + kid + ")") + ".");
        byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII), sig = b64decode(parts[2]);
        try {
            Signature s = Signature.getInstance(alg.equals("RS256") ? "SHA256withRSA" : "SHA256withECDSAinP1363Format");
            s.initVerify(key); s.update(signed);
            if (!s.verify(sig)) throw new IOException("The identity token's signature does not check against the provider's key.");
        } catch (GeneralSecurityException e) { throw new IOException("The identity token's signature could not be checked: " + e.getMessage()); }
        String iss = claims.path("iss").asText("").replaceAll("/+$", "");
        if (!iss.equals(d.issuer())) throw new IOException("The identity token is from " + iss + ", not from " + d.issuer() + ".");
        String clientId = setting(CLIENT_ID).strip();
        JsonNode aud = claims.path("aud");
        boolean forUs = aud.isArray() ? audHas(aud, clientId) : aud.asText("").equals(clientId);
        if (!forUs) throw new IOException("The identity token is for another client.");
        long now = System.currentTimeMillis() / 1000;
        if (claims.path("exp").asLong(0) + SKEW_S < now) throw new IOException("The identity token has expired.");
        if (claims.has("iat") && claims.path("iat").asLong(0) - SKEW_S > now) throw new IOException("The identity token is from the future; check the clocks.");
        if (nonce != null && !nonce.equals(claims.path("nonce").asText(""))) throw new IOException("The identity token's nonce is not the one this sign-in sent.");
        return claims;
    }

    private static boolean audHas(JsonNode aud, String clientId) { for (JsonNode a : aud) if (a.asText("").equals(clientId)) return true; return false; }

    /** The provider's key with this id (or its only key when the token names none), as the JDK's public key; null when none. */
    private static PublicKey key(Discovery d, String kid, String alg, boolean fresh) throws IOException {
        JsonNode keys = jwks(d, fresh);
        JsonNode found = null;
        int n = 0;
        for (JsonNode k : keys) { n++; if (!kid.isEmpty() && kid.equals(k.path("kid").asText(""))) { found = k; break; } }
        if (found == null && kid.isEmpty() && n == 1) found = keys.get(0);
        if (found == null) return null;
        return publicKey(found, alg);
    }

    /** A JSON Web Key as the JDK's public key: RSA by its modulus and exponent, EC on P-256 by its point. */
    static PublicKey publicKey(JsonNode jwk, String alg) throws IOException {
        try {
            String kty = jwk.path("kty").asText("");
            if (kty.equals("RSA") && alg.equals("RS256")) {
                return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(1, b64decode(jwk.path("n").asText())), new BigInteger(1, b64decode(jwk.path("e").asText()))));
            }
            if (kty.equals("EC") && alg.equals("ES256")) {
                if (!jwk.path("crv").asText("").equals("P-256")) throw new IOException("The provider's key is on " + jwk.path("crv").asText() + "; P-256 is taken.");
                AlgorithmParameters p = AlgorithmParameters.getInstance("EC"); p.init(new ECGenParameterSpec("secp256r1"));
                ECPoint w = new ECPoint(new BigInteger(1, b64decode(jwk.path("x").asText())), new BigInteger(1, b64decode(jwk.path("y").asText())));
                return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, p.getParameterSpec(ECParameterSpec.class)));
            }
            throw new IOException("The provider's key is a " + kty + " key, and the token says " + alg + ".");
        } catch (GeneralSecurityException e) { throw new IOException("The provider's key could not be read: " + e.getMessage()); }
    }

    // ---- the bindings ----

    static Path file(LibraryStore store) { return store.root().resolve("catalog").resolve("oidc-identities.json"); }

    static synchronized String boundDid(LibraryStore store, String issuer, String sub) throws IOException {
        for (JsonNode i : root(store).path("identities")) if (i.path("issuer").asText().equals(issuer) && i.path("sub").asText().equals(sub)) return i.path("did").asText();
        return null;
    }

    static synchronized void bind(LibraryStore store, String issuer, String sub, String did, String email) throws IOException {
        ObjectNode root = root(store);
        ArrayNode all = (ArrayNode) root.withArray("identities");
        for (JsonNode i : all) if (i.path("issuer").asText().equals(issuer) && i.path("sub").asText().equals(sub)) return;
        ObjectNode n = all.addObject();
        n.put("issuer", issuer); n.put("sub", sub); n.put("did", did); n.put("email", email); n.put("bound", LocalDate.now().toString());
        Files.createDirectories(file(store).getParent());
        AtomicWrite.text(file(store), M.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        Changes.append(store, "reader", did, "signed in through " + issuer, email);
    }

    private static ObjectNode root(LibraryStore store) throws IOException {
        Path f = file(store);
        if (!Files.exists(f)) { ObjectNode r = M.createObjectNode(); r.putArray("identities"); return r; }
        JsonNode n = M.readTree(Files.readString(f, StandardCharsets.UTF_8));
        if (!n.isObject()) throw new IOException(f + " is not the file the library writes.");
        return (ObjectNode) n;
    }

    // ---- small things ----

    static String random(int bytes) { byte[] b = new byte[bytes]; new SecureRandom().nextBytes(b); return b64url(b); }
    static String b64url(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static byte[] b64decode(String s) { return Base64.getUrlDecoder().decode(s.strip()); }
    static byte[] sha256(byte[] b) { try { return MessageDigest.getInstance("SHA-256").digest(b); } catch (GeneralSecurityException e) { throw new IllegalStateException(e); } }
    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    /** Tests: the caches dropped, so a stub issuer on a new port is read afresh. */
    static void forget() { discovery = null; keys = null; PENDING.clear(); }
}
