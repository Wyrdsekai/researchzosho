package org.researchzosho.librarian;

import org.researchzosho.tools.Outbound;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOG = LoggerFactory.getLogger(Oidc.class);

    private Oidc() { }

    static final ObjectMapper M = new ObjectMapper();
    public static final String ISSUER = "RESEARCHZOSHO_OIDC_ISSUER", CLIENT_ID = "RESEARCHZOSHO_OIDC_CLIENT_ID", CLIENT_SECRET = "RESEARCHZOSHO_OIDC_CLIENT_SECRET",
            ALLOWED_DOMAIN = "RESEARCHZOSHO_OIDC_ALLOWED_DOMAIN", NAME = "RESEARCHZOSHO_OIDC_NAME", REDIRECT = "RESEARCHZOSHO_OIDC_REDIRECT";
    static final String CALLBACK = "/login/oidc/callback";
    /** The cookie that holds the hash of a sign-in's state, so only the browser that began it finishes it (SEC 4). */
    static final String SIGNIN = "rz_signin";

    static String stateMark(String state) { return b64url(sha256(state.getBytes(StandardCharsets.UTF_8))); }

    /** The sign-in begun with {@code state} bound to this browser: a short-lived cookie with its state's hash, for the callback alone. */
    static void bindToBrowser(HttpExchange x, String state) {
        x.getResponseHeaders().add("Set-Cookie", SIGNIN + "=" + stateMark(state) + "; Path=/login/oidc; HttpOnly; SameSite=Lax" + (Pages.secure(x) ? "; Secure" : "") + "; Max-Age=600");
    }

    /** Whether the provider's answer came back to the browser that began the sign-in; the cookie is cleared either way. */
    static boolean fromThisBrowser(HttpExchange x, String state) {
        String mark = Pages.cookie(x, SIGNIN);
        x.getResponseHeaders().add("Set-Cookie", SIGNIN + "=; Path=/login/oidc; HttpOnly; SameSite=Lax" + (Pages.secure(x) ? "; Secure" : "") + "; Max-Age=0");
        return mark != null && state != null && !state.isEmpty() && MessageDigest.isEqual(mark.getBytes(StandardCharsets.US_ASCII), stateMark(state).getBytes(StandardCharsets.US_ASCII));
    }
    /** Tests set this; null means read the settings. */
    static volatile Map<String, String> OVERRIDE;
    private static final long PENDING_MS = 10 * 60_000L, CACHE_MS = 60 * 60_000L, SKEW_S = 60;
    private static final HttpClient HTTP = Outbound.client(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());

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

    /** The provider's discovery document: where to send the browser, trade the code, read the keys, and sign out (empty when it names none). */
    record Discovery(String issuer, String authorizationEndpoint, String tokenEndpoint, String jwksUri, String endSessionEndpoint, long fetched) { }
    private static volatile Discovery discovery;
    private record Keys(JsonNode keys, long fetched) { }
    private static volatile Keys keys;

    static synchronized Discovery discovery() throws IOException {
        String issuer = setting(ISSUER);
        if (blank(issuer)) throw new IOException(Pages.t("No identity provider is set ({0}).", ISSUER));
        issuer = issuer.strip().replaceAll("/+$", "");
        Discovery d = discovery;
        if (d != null && d.issuer().equals(issuer) && System.currentTimeMillis() - d.fetched() < CACHE_MS) return d;
        JsonNode j = getJson(issuer + "/.well-known/openid-configuration");
        String said = j.path("issuer").asText("").replaceAll("/+$", "");
        if (!said.equals(issuer)) throw new IOException(Pages.t("The provider at {0} says its issuer is {1}; the setting must name it as it names itself.", issuer, said));
        for (String k : new String[]{"authorization_endpoint", "token_endpoint", "jwks_uri"}) if (blank(j.path(k).asText(""))) throw new IOException(Pages.t("The provider's discovery document has no {0}.", k));
        d = new Discovery(issuer, j.path("authorization_endpoint").asText(), j.path("token_endpoint").asText(), j.path("jwks_uri").asText(), j.path("end_session_endpoint").asText(""), System.currentTimeMillis());
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
            if (r.statusCode() != 200) throw new IOException(Pages.t("{0} answered {1}.", url, r.statusCode()));
            return M.readTree(r.body());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }

    static String userAgent() { return "ResearchZosho/" + Version.string() + " (+https://researchzosho.org)"; }

    // ---- the flow ----

    private record Pending(String nonce, String verifier, String redirect, long made) { }
    private static final Map<String, Pending> PENDING = new ConcurrentHashMap<>();

    /**
     * Where the sign-ins begun are kept when several processes answer the same people (an account service of two processes, 0.8.0): put
     * as text, taken once. Null: this process's memory, as a library of a person's own keeps them.
     */
    interface Shared { void put(String state, String value) throws IOException; String take(String state) throws IOException; }
    static volatile Shared SHARED;

    /** GET /login/oidc: the address at the provider to send the browser to, the state and the code verifier kept here for ten minutes. */
    static String start(HttpExchange x) throws IOException { return begin(x, Map.of()).url(); }

    /** A sign-in begun: the address at the provider, and its state (the account service binds it to the browser, SEC 4). */
    record Started(String url, String state) { }

    /** The same, with more of the request's own parameters ({@code max_age}, {@code prompt}: to sign in again, SEC 7). */
    static Started begin(HttpExchange x, Map<String, String> extra) throws IOException {
        Discovery d = discovery();
        String state = random(24), nonce = random(24), verifier = random(48);
        String challenge = b64url(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
        String redirect = redirectUri(x);
        long now = System.currentTimeMillis();
        Shared shared = SHARED;
        if (shared != null) shared.put(state, String.join("\t", nonce, verifier, redirect, String.valueOf(now)));
        else {
            PENDING.entrySet().removeIf(e -> now - e.getValue().made() > PENDING_MS);
            PENDING.put(state, new Pending(nonce, verifier, redirect, now));
        }
        StringBuilder more = new StringBuilder();
        extra.forEach((k, v) -> more.append('&').append(enc(k)).append('=').append(enc(v)));
        return new Started(d.authorizationEndpoint() + (d.authorizationEndpoint().contains("?") ? "&" : "?") + "response_type=code&client_id=" + enc(setting(CLIENT_ID).strip()) + "&redirect_uri=" + enc(redirect)
                + "&scope=" + enc("openid email profile") + "&state=" + state + "&nonce=" + nonce + "&code_challenge=" + challenge + "&code_challenge_method=S256" + more, state);
    }

    /**
     * Where the browser goes to sign out at the provider as well (RP-initiated logout): its end_session_endpoint with the identity token of
     * this sign-in, the client and the page to come back to; null when the provider names none (Google), or nothing can be read.
     */
    static String endSession(String idToken, String backTo) {
        try {
            Discovery d = discovery();
            if (blank(d.endSessionEndpoint())) return null;
            return d.endSessionEndpoint() + (d.endSessionEndpoint().contains("?") ? "&" : "?") + (blank(idToken) ? "" : "id_token_hint=" + enc(idToken) + "&")
                    + "client_id=" + enc(setting(CLIENT_ID).strip()) + "&post_logout_redirect_uri=" + enc(backTo);
        } catch (IOException e) { return null; }
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
        Proven p = proven(q);
        return account(store, p.issuer(), p.claims());
    }

    /** The sign-in begun with this state, taken once: from where they are kept. */
    private static Pending pending(String state) throws IOException {
        Shared shared = SHARED;
        if (shared == null) return PENDING.remove(state);
        String v = shared.take(state);
        if (v == null) return null;
        String[] p = v.split("\t", -1);
        if (p.length != 4) return null;
        long made = Long.parseLong(p[3]);
        return System.currentTimeMillis() - made > PENDING_MS ? null : new Pending(p[0], p[1], p[2], made);
    }

    /** What the provider's answer proves: the provider, the claims of the identity token it signed, and the token itself. */
    record Proven(String issuer, JsonNode claims, String idToken) {
        Proven(String issuer, JsonNode claims) { this(issuer, claims, ""); }
    }

    /** The provider's answer checked, as {@link #finish} checks it, without naming an account: the account service names its own (0.8.0). */
    /** The error codes OAuth and OpenID Connect name: said on the sign-in page as they are; any other word a browser brings is not. */
    static final Set<String> OAUTH_ERRORS = Set.of("access_denied", "login_required", "consent_required", "interaction_required", "account_selection_required",
            "invalid_request", "unauthorized_client", "unsupported_response_type", "invalid_scope", "server_error", "temporarily_unavailable", "request_not_supported");

    static Proven proven(Map<String, String> q) throws IOException {
        String error = q.get("error");
        // the provider's own words never go onto the sign-in page: anyone can send a browser here with an error of their wording, and the page
        // would show it as the service's (round F, the second review); a code of OAuth's own list is said, anything else is logged only
        if (!blank(error)) {
            LOG.info("The identity provider did not sign a browser in: {} {}", Acquisitions.compress(error, 60), Acquisitions.compress(q.getOrDefault("error_description", ""), 200));
            throw new IOException(OAUTH_ERRORS.contains(error) ? Pages.t("The identity provider did not sign you in: {0}.", error) : Pages.t("The identity provider did not sign you in."));
        }
        String state = q.get("state"), code = q.get("code");
        Pending p = blank(state) ? null : pending(state);
        if (p == null) throw new IOException(Pages.t("This sign-in was not started here, or took longer than ten minutes. Try again."));
        if (blank(code)) throw new IOException(Pages.t("The identity provider sent no code back."));
        Discovery d = discovery();
        String secret = setting(CLIENT_SECRET);
        String form = "grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(p.redirect()) + "&client_id=" + enc(setting(CLIENT_ID).strip()) + "&code_verifier=" + enc(p.verifier())
                + (blank(secret) ? "" : "&client_secret=" + enc(secret.strip()));
        JsonNode t;
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(d.tokenEndpoint())).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                    .header("User-Agent", userAgent()).POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
            t = M.readTree(r.body());
            if (r.statusCode() != 200 || blank(t.path("id_token").asText(""))) throw new IOException(Pages.t("The identity provider gave no identity token ({0}).", r.statusCode() + (blank(t.path("error").asText("")) ? "" : ", " + t.path("error").asText())));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
        return new Proven(d.issuer(), verify(t.path("id_token").asText(), d, p.nonce()), t.path("id_token").asText());
    }

    /** The account the token's person has here: bound before, a listed reader by verified mail, or new from an allowed domain. */
    static synchronized Accounts.Account account(LibraryStore store, String issuer, JsonNode claims) throws IOException {
        String sub = claims.path("sub").asText("").strip();
        if (sub.isEmpty()) throw new IOException(Pages.t("The identity token names no subject."));
        String email = claims.path("email").asText("").strip().toLowerCase(Locale.ROOT), name = claims.path("name").asText("").strip();
        boolean verified = claims.path("email_verified").asBoolean(false);
        String did = boundDid(store, issuer, sub);
        if (did != null) {
            if (!Accounts.OWNER.equals(did)) { Patrons.Entry e = Patrons.byDid(store, did); if (e == null || e.level() == Patrons.Level.deny) throw new IOException(Pages.t("Your account is no longer a reader of this library. Ask the person who keeps it.")); }
            Accounts.Account a = Accounts.byDid(store, did);
            if (a != null) return a;
        }
        if (did == null && !email.isEmpty() && verified) {
            Patrons.Entry e = Patrons.byDid(store, Patrons.loginDid(email));
            if (e != null && e.level() != Patrons.Level.deny) did = e.did();
            if (did == null) { Accounts.Account o = Accounts.owner(store); if (o != null && o.login().equals(Accounts.login(email))) did = Accounts.OWNER; }
        }
        if (did == null) {
            if (email.isEmpty() || !verified) throw new IOException(Pages.t("The identity provider gave no verified mail address for you, and no account here is bound to this sign-in. Ask the person who keeps this library to let you in."));
            String domain = email.substring(email.indexOf('@') + 1);
            if (!allowed(domain)) throw new IOException(Pages.t("Mail addresses at {0} are not let in here. Ask the person who keeps this library.", domain));
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
        if (parts.length != 3) throw new IOException(Pages.t("The identity token is not in three parts."));
        JsonNode header = M.readTree(b64decode(parts[0])), claims = M.readTree(b64decode(parts[1]));
        String alg = header.path("alg").asText(""), kid = header.path("kid").asText("");
        if (!alg.equals("RS256") && !alg.equals("ES256")) throw new IOException(Pages.t("The identity token is signed with {0}; RS256 and ES256 are taken.", alg));
        PublicKey key = key(d, kid, alg, false);
        if (key == null) key = key(d, kid, alg, true);
        if (key == null) throw new IOException(kid.isEmpty() ? Pages.t("The provider's published keys have none the token names.") : Pages.t("The provider's published keys have none the token names ({0}).", kid));
        byte[] signed = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII), sig = b64decode(parts[2]);
        try {
            Signature s = Signature.getInstance(alg.equals("RS256") ? "SHA256withRSA" : "SHA256withECDSAinP1363Format");
            s.initVerify(key); s.update(signed);
            if (!s.verify(sig)) throw new IOException(Pages.t("The identity token's signature does not check against the provider's key."));
        } catch (GeneralSecurityException e) { throw new IOException(Pages.t("The identity token's signature could not be checked: {0}", e.getMessage())); }
        String iss = claims.path("iss").asText("").replaceAll("/+$", "");
        if (!iss.equals(d.issuer())) throw new IOException(Pages.t("The identity token is from {0}, not from {1}.", iss, d.issuer()));
        String clientId = setting(CLIENT_ID).strip();
        JsonNode aud = claims.path("aud");
        boolean forUs = aud.isArray() ? audHas(aud, clientId) : aud.asText("").equals(clientId);
        if (!forUs) throw new IOException(Pages.t("The identity token is for another client."));
        long now = System.currentTimeMillis() / 1000;
        if (claims.path("exp").asLong(0) + SKEW_S < now) throw new IOException(Pages.t("The identity token has expired."));
        if (claims.has("iat") && claims.path("iat").asLong(0) - SKEW_S > now) throw new IOException(Pages.t("The identity token is from the future; check the clocks."));
        if (nonce != null && !nonce.equals(claims.path("nonce").asText(""))) throw new IOException(Pages.t("The identity token's nonce is not the one this sign-in sent."));
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
                if (!jwk.path("crv").asText("").equals("P-256")) throw new IOException(Pages.t("The provider's key is on {0}; P-256 is taken.", jwk.path("crv").asText()));
                AlgorithmParameters p = AlgorithmParameters.getInstance("EC"); p.init(new ECGenParameterSpec("secp256r1"));
                ECPoint w = new ECPoint(new BigInteger(1, b64decode(jwk.path("x").asText())), new BigInteger(1, b64decode(jwk.path("y").asText())));
                return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(w, p.getParameterSpec(ECParameterSpec.class)));
            }
            throw new IOException(Pages.t("The provider's key is a {0} key, and the token says {1}.", kty, alg));
        } catch (GeneralSecurityException e) { throw new IOException(Pages.t("The provider's key could not be read: {0}", e.getMessage())); }
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
