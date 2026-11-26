package org.researchzosho.librarian;

import org.researchzosho.tools.Outbound;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.researchzosho.Config;
import org.researchzosho.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The account service (0.8.0): the one address of a ResearchZosho that serves several accounts. It is on when the setting
 * {@code accounts} is {@code on}, and then {@code researchzosho serve} starts it in place of one library's daemon. It keeps the
 * accounts, signs people in, gives programs their keys, knows which library each account keeps and which it may read or write
 * ({@link AccountStore}), starts a library when it is first asked for ({@link LibraryRunner}), and sends each request on to its library
 * with a statement it signs for that request alone ({@link Statement}): who is asking, which library, at what level, for a minute.
 * A library under it believes that statement and nothing else.
 *
 * <p>It answers the sign-in, join and account pages, {@code /static/}, {@code /healthz} and its key file itself. Every other request
 * is a library's: the library the key was made for, or the one this browser is in. Bodies go through as they come, in both
 * directions, so a file of several hundred megabytes and an answer written over minutes pass without being held here.
 */
public final class AccountService {
    private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);

    public static final String SETTING = "RESEARCHZOSHO_ACCOUNTS", STORE = "RESEARCHZOSHO_ACCOUNTS_STORE", ROOT = "RESEARCHZOSHO_ACCOUNTS_ROOT", SIGNUP = "RESEARCHZOSHO_ACCOUNTS_SIGNUP",
            ADDRESS = "RESEARCHZOSHO_ACCOUNTS_ADDRESS", FIRST_PORT = "RESEARCHZOSHO_ACCOUNTS_FIRST_PORT",
            /** The address the libraries and the nodes reach the service at, on the private network between them; the name its statements are made in. Unset: the address it listens on. */
            SERVICE = "RESEARCHZOSHO_ACCOUNTS_SERVICE";

    /** The name the service's statements are made in and the address its libraries reach it at: the setting, else {@code self}. Every process of one service has the same. */
    static String issuer(String self) { String v = Config.get(SERVICE); return v == null || v.isBlank() ? self : v.strip().replaceAll("/+$", ""); }
    /** Where the service publishes the keys its statements are signed with. */
    public static final String KEYS_PATH = "/.well-known/jwks.json";
    /** The cookie a signed-in browser keeps: its session's secret. */
    static final String COOKIE = "rz_account";
    /** How long a join link stands, in days. */
    public static final int JOIN_DAYS = 7;

    /** Whether this ResearchZosho serves several accounts: the setting, on only when it says on. */
    public static boolean on() { String v = Config.get(SETTING); return v != null && v.strip().equalsIgnoreCase("on"); }

    /** Where the accounts and their libraries are kept: the setting, else {@code accounts} in the settings folder. */
    public static Path root() { String r = Config.get(ROOT); return r == null || r.isBlank() ? Config.home().resolve("accounts") : Path.of(r.strip()).toAbsolutePath().normalize(); }

    /** The store: the address the setting names (PostgreSQL), else the file {@code accounts.db} under {@link #root()}. */
    public static AccountStore openStore() throws IOException { String s = Config.get(STORE); return AccountStore.open(s == null || s.isBlank() ? root().resolve("accounts.db").toString() : s.strip()); }

    /** Who may make an account by signing in for the first time: {@code invited} (nobody: an account is made for a person), {@code anyone}, or {@code domain} (the mail domains of {@code oidc_allowed_domain}). */
    public static String signup() { String o = SIGNUP_OVERRIDE, v = o != null ? o : Config.get(SIGNUP); v = v == null ? "" : v.strip().toLowerCase(Locale.ROOT); return v.equals("anyone") || v.equals("domain") ? v : "invited"; }
    /** Tests set this; null means read the setting. */
    static volatile String SIGNUP_OVERRIDE;

    /** The address people reach the service at, for the links it writes: the setting, else the address it listens on. */
    public String address() { String a = Config.get(ADDRESS); return a == null || a.isBlank() ? self : a.strip().replaceAll("/+$", ""); }

    final AccountStore store;
    final LibraryRunner runner;
    /** The address the service listens on, which libraries reach it at and which its statements are made in the name of. */
    final String self;
    /** The name the statements are made in: {@link #issuer(String)}. */
    final String issuer;
    private final HttpServer server;
    /** Whether the service listens on this computer's loopback only: then the address it listens on is the address people reach it at. */
    boolean reachedOnlyHere;
    private final HttpClient client = Outbound.client(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).executor(Executors.newVirtualThreadPerTaskExecutor()).build());

    /** Who a request is: the account, how it proved it (a browser's session, or a key that reads and writes or reads), and what it proved it with. */
    record Who(AccountStore.Account account, String via, AccountStore.Key key, String session) {
        boolean browser() { return key == null; }
    }

    private AccountService(AccountStore store, HttpServer server, String self, Function<String, LibraryRunner> runner) {
        this.store = store; this.server = server; this.self = self; this.issuer = issuer(self); this.runner = runner.apply(issuer); this.spending = Uses.spending(store, issuer);
        this.limits = new RateLimits(store);
    }

    /** How often an address or an account may ask (stage 4): this process's counts, and its share of the service's limits. */
    final RateLimits limits;

    /** Binds and starts ({@code port} 0: any free one). The runner is made with the service's own address, which it gives its libraries. */
    public static AccountService start(AccountStore store, String host, int port, Function<String, LibraryRunner> runner) throws IOException {
        return start(store, host, port, (issuer, self) -> runner.apply(issuer));
    }

    /** The same, the runner made with the service's address its statements name and its own address on this machine ({@code self}). */
    public static AccountService start(AccountStore store, String host, int port, BiFunction<String, String, LibraryRunner> runner) throws IOException {
        HttpServer s = HttpServers.create(new InetSocketAddress(host, port), 0);
        String at = "http://" + (host.equals("0.0.0.0") || host.equals("::") ? "127.0.0.1" : host) + ":" + s.getAddress().getPort();
        AccountService a = new AccountService(store, s, at, issuer -> runner.apply(issuer, at));
        a.reachedOnlyHere = InetAddress.getByName(host).isLoopbackAddress();
        store.signing();   // the key is made before the first request, so two first requests do not make two
        // what every process of the service must read alike: the sign-ins begun through the provider, and the failed tries
        Oidc.SHARED = a.pending = new Oidc.Shared() { @Override public void put(String st, String v) throws IOException { store.pendingPut(st, v); } @Override public String take(String st) throws IOException { return store.pendingTake(st); } };
        Accounts.SHARED_TRIES = a.tries = new Accounts.SharedTries() {
            @Override public int since(String k, long t) throws IOException { return store.tries(k, t); }
            @Override public void failed(String k) throws IOException { store.tried(k); }
            @Override public void cleared(String k) throws IOException { store.triesCleared(k); }
        };
        s.createContext("/", x -> a.handle(new PerRequest(x)));
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        if (a.spending instanceof PluginSpending p) a.outbox = p.sender(at + "/" + ProcessHandle.current().pid());   // what was spent, sent on to the plug-in in order
        a.sweeper = Thread.ofPlatform().daemon().name("uses-sweep").start(() -> a.sweepUses(at + "/" + ProcessHandle.current().pid()));
        a.settings = ServiceSettings.publishing(store);   // what the nodes count and cap by, from this service's settings (BILL 10)
        return a;
    }

    public int port() { return server.getAddress().getPort(); }
    Spending spending() { return spending; }

    /**
     * The key a use's end and its state are told with (0.8.0 review, round C, SEC 12): given to the library with the use, worked out from
     * the store's own key, so every process of the service knows it and nobody else on the machine does who only names the use.
     */
    String useKey(String use) { return mac("use\n" + use, 32); }

    /** A note written into an address for a page this service sends the browser to, with the mark that it wrote it (SEC 12). */
    String noted(String path, String note, String session) {
        if (note == null || note.isBlank()) return path;
        return path + (path.contains("?") ? "&" : "?") + "note=" + URLEncoder.encode(note, StandardCharsets.UTF_8) + "&ns=" + mac("note\n" + sessionHash(session) + "\n" + note, 22);
    }

    /** The note an address carries, when this service wrote it for this browser; null otherwise. */
    String noteShown(Map<String, String> q, String session) {
        String note = q.get("note"), mark = q.get("ns");
        if (note == null || note.isBlank() || mark == null) return null;
        return MessageDigest.isEqual(mark.getBytes(StandardCharsets.US_ASCII), mac("note\n" + sessionHash(session) + "\n" + note, 22).getBytes(StandardCharsets.US_ASCII)) ? note : null;
    }

    private static String sessionHash(String session) { return session == null || session.isBlank() ? "" : Patrons.sha256(session.strip()); }

    private volatile byte[] macKey;

    /** An HMAC of {@code what} under a key of the service's own, worked out from the store's signing key, {@code chars} long. */
    private String mac(String what, int chars) {
        try {
            byte[] k = macKey;
            if (k == null) macKey = k = MessageDigest.getInstance("SHA-256").digest((Base64.getEncoder().encodeToString(store.signing().key().getEncoded()) + "\nresearchzosho-marks").getBytes(StandardCharsets.UTF_8));
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(k, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(what.getBytes(StandardCharsets.UTF_8))).substring(0, chars);
        } catch (IOException | GeneralSecurityException e) { throw new IllegalStateException("The service's key could not be read: " + e.getMessage(), e); }
    }
    public String url() { return self; }

    /** Stops the service and the libraries it started. */
    public void stop() {
        if (outbox != null) outbox.stop();
        if (sweeper != null) sweeper.interrupt();
        if (settings != null) settings.interrupt();
        server.stop(0); runner.stopAll(); client.close();
        limits.stop();
        if (Oidc.SHARED == pending) Oidc.SHARED = null;   // what this service put in place, taken away with it
        if (Accounts.SHARED_TRIES == tries) Accounts.SHARED_TRIES = null;
    }

    private Oidc.Shared pending;
    private PluginSpending.Sender outbox;
    private Thread sweeper, settings;

    /** Every few minutes, in one process of the service: the uses nobody will end closed, and what was counted late told ({@link Uses#sweep}). */
    private void sweepUses(String holder) {
        while (!Thread.currentThread().isInterrupted()) {
            try { Thread.sleep(Uses.SWEEP_MS); } catch (InterruptedException e) { return; }
            try { if (store.duty("uses-sweep", holder, Uses.SWEEP_MS * 2)) Uses.sweep(store, spending, System.currentTimeMillis()); }
            catch (IOException | RuntimeException e) { LOG.warn("The uses could not be swept: {}", e.getMessage()); }
        }
    }
    private Accounts.SharedTries tries;

    // ---- one request ----

    private void handle(HttpExchange x) throws IOException {
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");   // nothing this program serves is read as another type than it says: only its own script files are scripts
        String path = x.getRequestURI().getPath(), method = x.getRequestMethod();
        try {
            if (ModelNetworks.refused(x)) return;   // a model server's network with no way out asks nothing of the service (ModelNetworks)
            if (Proxy.behindTrusted(x)) x.setAttribute(Proxy.PROXIED, Boolean.TRUE);   // a router in front of the service, named in the trusted-proxy setting: its forwarded headers are believed
            if (path.equals("/healthz")) { ObjectNode o = Statement.M.createObjectNode(); o.put("ok", true); o.put("version", Version.string()); o.put("service", "accounts"); json(x, 200, o); return; }
            if (path.equals(KEYS_PATH)) { ObjectNode o = Statement.M.createObjectNode(); ArrayNode keys = o.putArray("keys"); for (String k : store.publicJwks()) keys.add(Statement.M.readTree(k)); json(x, 200, o); return; }
            if (path.startsWith("/static/") && method.equals("GET")) { Pages.staticFile(x, path.substring("/static/".length())); return; }
            if ((path.equals("/favicon.ico") || path.equals("/favicon.png")) && method.equals("GET")) { byte[] icon = LibrarianDaemon.favicon(); x.getResponseHeaders().set("Content-Type", "image/png"); x.getResponseHeaders().set("Cache-Control", "public, max-age=86400"); x.sendResponseHeaders(200, icon.length); try (OutputStream os = x.getResponseBody()) { os.write(icon); } return; }
            if (path.equals(ServiceAccess.PATH)) { internalAccess(x); return; }
            if (path.equals(Uses.START) || path.equals(Uses.END)) { internalUse(x, path.equals(Uses.START)); return; }
            if (path.equals(Uses.STATE)) { internalUseState(x); return; }
            if (path.equals(USAGE_INTERNAL)) { internalUsage(x); return; }
            // the rate limits (stage 4): a request with nothing that says who it is counts against its address before anything is looked
            // up; one with a key or a session that opens nothing, against its address after; an account's, against the account
            String auth = x.getRequestHeaders().getFirst("Authorization");
            boolean says = auth != null && !auth.isBlank() || Pages.cookie(x, COOKIE) != null;
            RateLimits.Refused over = says ? null : limits.take(RateLimits.Limit.ADDRESS, RateLimits.ofAddress(x));
            if (over != null) { tooMany(x, null, path, method, over); return; }
            Who who;
            try { who = identify(x); }
            catch (ProtocolError e) {
                over = limits.take(RateLimits.Limit.ADDRESS, RateLimits.ofAddress(x));
                if (over != null) tooMany(x, null, path, method, over); else json(x, 403, error(e.code, e.getMessage()));
                return;
            }
            over = who != null ? limits.take(who.browser() ? RateLimits.Limit.PAGES : RateLimits.Limit.API, RateLimits.ofAccount(who.account().id()))
                    : says ? limits.take(RateLimits.Limit.ADDRESS, RateLimits.ofAddress(x)) : null;
            if (over != null) { tooMany(x, who, path, method, over); return; }
            if (path.equals(USAGE)) {   // the account's month, for a program with the account's key, or a signed-in browser (stage 3, step 5)
                if (who == null) { json(x, 403, error("forbidden", "Sign in at " + address() + "/login, or send a key a signed-in person made: Authorization: Bearer rz_…")); return; }
                Chrome.here(Chrome.choose(null, null, query(x).get(Chrome.QUERY), Pages.cookie(x, Chrome.COOKIE), x.getRequestHeaders().getFirst("Accept-Language")));
                try { json(x, 200, AccountPages.monthJson(store, who.account())); } finally { Chrome.clear(); }
                return;
            }
            if (path.equals(ARCHIVE) && method.equals("GET")) { archive(x, who); return; }   // a library to take home (stage 4): the service's to answer, never a library's
            if (AccountPages.own(path)) {
                if (!OwnPage.allowed(x)) { OwnPage.refuse(x); return; }   // a form from another site is not carried out, here as in a library
                Map<String, String> q = query(x);
                Chrome.here(Chrome.choose(null, null, q.get(Chrome.QUERY), Pages.cookie(x, Chrome.COOKIE), x.getRequestHeaders().getFirst("Accept-Language")));
                String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                AccountPages.handle(this, x, who, path, method, q, Pages.form(body, x.getRequestHeaders().getFirst("Content-Type")));
                return;
            }
            forward(x, who, path, method);
        } catch (ProtocolError e) {
            try { json(x, LibrarianDaemon.status(e.code), error(e)); } catch (IOException | RuntimeException sent) { /* the answer had begun */ }
        } catch (IOException | RuntimeException e) {
            try { json(x, 503, error("unavailable", "The service could not answer: " + e.getMessage())); } catch (IOException | RuntimeException sent) { /* the answer had begun */ }
        } finally { x.close(); }
    }

    /** Who the request is: a key in the Authorization header, else this browser's session; null for nobody. A key the service does not know is refused. */
    Who identify(HttpExchange x) throws IOException {
        String auth = x.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            AccountStore.Key k = store.resolveKey(auth.substring(7).strip());
            AccountStore.Account a = k == null ? null : store.account(k.account());
            if (a != null && (a.state().equals("suspended") || a.state().equals("deleting"))) throw ProtocolError.forbidden(AccountActs.notActiveSaid(store, a));   // kept, and refused while it is (stage 4)
            if (a == null || !a.active()) throw ProtocolError.forbidden("That key does not match any this service made, or it was revoked. The person it belonged to makes a new one on their account page.");
            return new Who(a, k.reads() ? Statement.KEY_READ : Statement.KEY, k, null);
        }
        String secret = Pages.cookie(x, COOKIE);
        AccountStore.Session s = store.session(secret);
        AccountStore.Account a = s == null ? null : store.account(s.account());
        // a deleting account signs in to its account page alone, where it may cancel its deletion (stage 4); a suspended one has no session
        return a == null || !(a.active() || a.state().equals("deleting")) ? null : new Who(a, Statement.SESSION, null, secret);
    }

    // ---- sending a request on to its library ----

    /** What is not passed on in either direction: what belongs to one connection, and what says who is asking, which the statement alone says to a library. */
    private static final Set<String> NOT_PASSED = Set.of("connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "expect", "date",
            "authorization", "cookie", "x-rz-statement", "x-forwarded-for", "x-forwarded-proto", "x-forwarded-host", "forwarded", "x-real-ip", "tailscale-user-login", "tailscale-user-name", "tailscale-user-profile-pic",
            "cf-access-authenticated-user-email", "cf-access-jwt-assertion");

    /**
     * Whether a library's answer sets one of the service's own cookies (its session, a sign-in under way): never passed on, so a library cannot
     * sign a browser into another account or end its session (round F, the second review).
     */
    static boolean servicesCookie(String header, String value) {
        if (!header.equalsIgnoreCase("set-cookie") || value == null) return false;
        String name = value.strip().split("=", 2)[0].strip();
        return name.equalsIgnoreCase(COOKIE) || name.equalsIgnoreCase(Oidc.SIGNIN) || name.equalsIgnoreCase(AccountPages.JOIN);
    }

    private void forward(HttpExchange x, Who who, String path, String method) throws IOException {
        if (who == null) {
            // nobody signed in: a browser asking for a page is sent to the sign-in page, a program is told in a sentence
            String accept = x.getRequestHeaders().getFirst("Accept");
            if (method.equals("GET") && accept != null && accept.contains("text/html")) { x.getRequestBody().close(); Pages.redirect(x, "/login"); return; }
            throw ProtocolError.forbidden("Sign in at " + address() + "/login, or send a key a signed-in person made: Authorization: Bearer rz_…");
        }
        if (who.browser() && !OwnPage.allowed(x)) { OwnPage.refuse(x); return; }   // a cookie proves a browser only for a request its own pages sent
        if (!who.account().active()) {   // being deleted: its account page alone, where it may cancel
            if (who.browser() && method.equals("GET")) { x.getRequestBody().close(); Pages.redirect(x, "/account"); return; }
            throw ProtocolError.forbidden(AccountActs.notActiveSaid(store, who.account()));
        }
        AccountStore.Library lib = libraryOf(who);
        if (lib == null) {
            if (who.browser() && method.equals("GET")) { Pages.redirect(x, "/account"); return; }
            throw ProtocolError.forbidden("This account has no library it may open.");
        }
        if (!lib.active()) {   // taken out of service: being moved or brought back for a minute, or removed or let go for good
            boolean moving = lib.state().equals("moving");
            String said = moving ? Pages.t("The library {0} is being moved. Ask again in a minute.", lib.title()) : Pages.t("The library {0} is no longer served here.", lib.title());
            if (who.browser() && method.equals("GET")) { if (!moving) store.sessionLibrary(who.session(), ""); x.getRequestBody().close(); Pages.redirect(x, noted("/account", said, who.session())); return; }
            throw moving ? ProtocolError.unavailable(said) : ProtocolError.forbidden(said);
        }
        if (!AccountActs.keeperOpen(store, lib)) {   // its keeper is suspended or being deleted: closed to everyone, members too
            String said = AccountActs.closedSaid(lib);
            if (who.browser() && method.equals("GET")) { x.getRequestBody().close(); Pages.redirect(x, noted("/account", said, who.session())); return; }
            throw ProtocolError.forbidden(said);
        }
        String level = store.level(lib, who.account().id());
        if (level == null) {
            if (who.browser()) { store.sessionLibrary(who.session(), ""); if (method.equals("GET")) { Pages.redirect(x, "/account"); return; } }
            throw ProtocolError.forbidden("Your account may not open the library " + lib.title() + ". The person who keeps it lets people in on their account page.");
        }
        // an open chat page asks for its runs strip once a minute for as long as its tab is open. A sleeping library runs nothing, so
        // the page is told nothing and keeps what it shows; the library is not started for it
        boolean byItself = method.equals("GET") && path.equals(Held.RUNS_STRIP);
        AccountStore.Signing signing = store.signing();
        HttpResponse<InputStream> r = null;
        boolean counted = false;
        try {
            for (int attempt = 0; r == null; attempt++) {
                String address;
                try { address = byItself ? runner.ifAwake(lib) : runner.ensure(lib); }   // held here while a sleeping library starts
                catch (Node.Packing p) {   // being packed to take home: a page says so on the account page, a program is told to ask again in a minute
                    if (who.browser() && method.equals("GET")) { x.getRequestBody().close(); Pages.redirect(x, noted("/account", p.getMessage(), who.session())); return; }
                    x.getResponseHeaders().set("Retry-After", "60");
                    throw ProtocolError.unavailable(p.getMessage());
                }
                catch (IOException e) { throw ProtocolError.unavailable(e.getMessage()); }
                if (address == null) { nothing(x); return; }
                counted = true;
                // the statement names the start of the library it is made for: a start that has been replaced does not answer it
                String statement = Statement.sign(signing.key(), signing.kid(), Statement.Claims.now(issuer, lib.id(), who.account().id(), who.account().name(), level, who.via()).epoch(runner.epoch(lib.id())));
                try { r = send(x, address, statement, method); }
                catch (ConnectException gone) {   // the library is not at its address any more: looked for again, once
                    runner.done(lib.id()); counted = false;
                    runner.lost(lib.id());
                    if (byItself) { nothing(x); return; }
                    if (attempt > 0) throw ProtocolError.unavailable("The library " + lib.title() + " is not answering: " + gone.getMessage());
                } catch (IOException e) {
                    if (attempt > 0) throw ProtocolError.unavailable("The library " + lib.title() + " is not answering: " + e.getMessage());
                    throw e;
                }
            }
            for (Map.Entry<String, List<String>> h : r.headers().map().entrySet()) if (!NOT_PASSED.contains(h.getKey().toLowerCase(Locale.ROOT))) for (String v : h.getValue()) if (!servicesCookie(h.getKey(), v)) x.getResponseHeaders().add(h.getKey(), v);
            long length = r.headers().firstValueAsLong("content-length").orElse(-1);
            boolean none = method.equals("HEAD") || r.statusCode() == 204 || r.statusCode() == 304 || length == 0;
            x.sendResponseHeaders(r.statusCode(), none ? -1 : length > 0 ? length : 0);
            byte[] buf = new byte[16384];
            try (InputStream in = r.body(); OutputStream out = none ? OutputStream.nullOutputStream() : x.getResponseBody()) {
                for (int n; (n = in.read(buf)) > 0; ) { out.write(buf, 0, n); out.flush(); }   // as it comes: an answer written over minutes is read over minutes
            } catch (IOException left) { /* the browser or the program went away: closing the library's answer here ends the library's side too */ }
        } finally { if (counted) runner.done(lib.id()); }   // answered to its end: the library may sleep when nothing else is open
    }

    /** An answer with nothing in it: a page that asked by itself keeps what it shows. */
    private static void nothing(HttpExchange x) throws IOException {
        x.getRequestBody().close();
        x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        x.sendResponseHeaders(200, -1);
        x.close();
    }

    /** The request sent to the library's address with the statement, its body as a stream of the length the caller declared. */
    private HttpResponse<InputStream> send(HttpExchange x, String address, String statement, String method) throws IOException {
        URI u = x.getRequestURI();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(address + u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery())));
        for (Map.Entry<String, List<String>> h : x.getRequestHeaders().entrySet()) if (!NOT_PASSED.contains(h.getKey().toLowerCase(Locale.ROOT))) for (String v : h.getValue()) b.header(h.getKey(), v);
        String cookies = otherCookies(x.getRequestHeaders().getFirst("Cookie"));
        if (!cookies.isEmpty()) b.header("Cookie", cookies);   // the library's own small cookies (the language chosen); never the session's
        b.header(Statement.HEADER, statement);
        InetAddress from = Proxy.client(x);
        if (from != null) b.header("X-Forwarded-For", from.getHostAddress());
        b.header("X-Forwarded-Proto", Proxy.https(x) ? "https" : "http");
        String host = Proxy.proxied(x) && x.getRequestHeaders().getFirst("X-Forwarded-Host") != null ? x.getRequestHeaders().getFirst("X-Forwarded-Host") : x.getRequestHeaders().getFirst("Host");
        if (host != null && !host.isBlank()) b.header("X-Forwarded-Host", host.strip());
        String declared = x.getRequestHeaders().getFirst("Content-Length");
        boolean body = !method.equals("GET") && !method.equals("HEAD");
        HttpRequest.BodyPublisher pub = !body ? HttpRequest.BodyPublishers.noBody()
                : declared != null && declared.strip().matches("\\d+") ? HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(x::getRequestBody), Long.parseLong(declared.strip()))
                : HttpRequest.BodyPublishers.ofInputStream(x::getRequestBody);
        b.method(method, pub);
        try { return client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }

    /** A Cookie header without the service's own session. */
    static String otherCookies(String header) {
        if (header == null || header.isBlank()) return "";
        StringBuilder b = new StringBuilder();
        for (String c : header.split(";")) { String s = c.strip(); if (s.isEmpty() || s.startsWith(COOKIE + "=")) continue; if (b.length() > 0) b.append("; "); b.append(s); }
        return b.toString();
    }

    /** The library a request is for: the one its key was made for; for a browser, the one it is in, which is its account's own until it opens another. */
    AccountStore.Library libraryOf(Who who) throws IOException {
        if (who.key() != null) return store.library(who.key().library());
        AccountStore.Session s = store.session(who.session());
        AccountStore.Library lib = s == null || s.library().isEmpty() ? null : store.library(s.library());
        if (lib != null && lib.active()) return lib;
        List<AccountStore.Library> mine = store.librariesOf(who.account().id());
        if (mine.isEmpty()) return null;
        store.sessionLibrary(who.session(), mine.get(0).id());
        return mine.get(0);
    }

    // ---- a library to take home (stage 4) ----

    /** Where the person who keeps a library downloads it as one archive: with the key made for it, or signed in (its id in {@code library}). */
    static final String ARCHIVE = "/v1/library/archive";

    /**
     * The archive of a library its keeper asked for, streamed as it is made (docs/public/LIBRARY_ARCHIVE.md): the library a key was made
     * for, or for a browser the one named by {@code library}, else the one it is in. Only its keeper takes it, an account that is active.
     */
    private void archive(HttpExchange x, Who who) throws IOException {
        x.getRequestBody().close();
        Chrome.here(Chrome.choose(null, null, query(x).get(Chrome.QUERY), Pages.cookie(x, Chrome.COOKIE), x.getRequestHeaders().getFirst("Accept-Language")));
        try {
            if (who == null) throw ProtocolError.forbidden("Sign in at " + address() + "/login, or send a key a signed-in person made: Authorization: Bearer rz_…");
            if (!who.account().active()) throw ProtocolError.forbidden(AccountActs.notActiveSaid(store, who.account()));
            String named = query(x).getOrDefault("library", "");
            AccountStore.Library lib = who.key() == null && !named.isEmpty() ? store.library(named) : libraryOf(who);
            if (lib == null || !lib.active() || !lib.owner().equals(who.account().id())) throw ProtocolError.forbidden(Pages.t("Only the person who keeps a library takes it home."));
            LazyBody out = new LazyBody(x, "application/zip");
            x.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + LibraryArchive.fileName(lib) + "\"");
            x.getResponseHeaders().set("Cache-Control", "no-store");
            try { runner.archive(lib, out, address()); out.flush(); }
            catch (IOException | RuntimeException e) {
                if (out.started()) { LOG.warn("The archive of the library {} ended short: {}", lib.id(), e.toString()); return; }   // begun: it ends short, which the person's download shows
                x.getResponseHeaders().remove("Content-Disposition");
                if (e instanceof Node.Packing) x.getResponseHeaders().set("Retry-After", "60");
                throw e instanceof ProtocolError pe ? pe : ProtocolError.unavailable(e.getMessage());
            }
            AccountActs.audit(store, AccountActs.By.of(who, x), "library_archived", who.account().id(), lib.id(), null);
        } finally { Chrome.clear(); }
    }

    // ---- accounts and libraries made ----

    /** A new account with a library of its own; the join link's code that lets the person choose how they sign in. */
    public record Made(AccountStore.Account account, AccountStore.Library library, String code) { }

    public Made newAccount(String name, String mail, boolean admin, String by) throws IOException { return newAccount(store, root(), name, mail, admin, by); }

    /** An account with no library of its own: one an invitation made where the sign-up rule gives none (null library in the result). */
    public Made newAccount(String name, String mail, boolean admin, String by, boolean withLibrary) throws IOException { return newAccount(store, root(), name, mail, admin, by, withLibrary); }

    static Made newAccount(AccountStore store, Path root, String name, String mail, boolean admin, String by) throws IOException { return newAccount(store, root, name, mail, admin, by, true); }

    static Made newAccount(AccountStore store, Path root, String name, String mail, boolean admin, String by, boolean withLibrary) throws IOException {
        if (withLibrary && onNodes(store) && Nodes.liveIn(store).isEmpty()) throw new IOException(Pages.t("No machine of this service answers now. Ask again in a minute."));   // its library is made on a node: none answers, no account
        AccountStore.Account a = store.createAccount(name, mail, admin);
        AccountStore.Library lib = withLibrary ? newLibrary(store, root, a, a.name()) : null;
        return new Made(a, lib, store.newInvite(a.id(), by, JOIN_DAYS));
    }

    /**
     * Whether the sign-up rule gives a new account a library of its own: anyone's under {@code anyone}; under {@code domain}, one whose
     * provider vouched for a mail address in an allowed domain; under {@code invited}, none. An invitation lets its person into the library
     * it is for, and makes them no library beside it unless this says so (the owner, 2026-11-18).
     */
    static boolean signupGivesALibrary(String mail, boolean verified) {
        String s = signup();
        if (s.equals("anyone")) return true;
        return s.equals("domain") && verified && mail != null && mail.contains("@") && Oidc.allowed(mail.substring(mail.indexOf('@') + 1));
    }

    /**
     * Whether a new library is made by a node (stage 4, step 5): this machine runs none itself ({@code accounts_node off}) and the service
     * has nodes written down. A service that never had a node makes it under its own root, as before.
     */
    static boolean onNodes(AccountStore store) throws IOException { return !Node.ownOn() && !store.nodes().isEmpty(); }

    /** A new library kept by an account: its folder under the root, made as a library is made, and written down in the store. */
    static AccountStore.Library newLibrary(AccountStore store, Path root, AccountStore.Account keeper, String title) throws IOException {
        String name = freeName(store, keeper);
        if (onNodes(store)) {   // this machine runs no libraries (accounts_node off) and the service has nodes: one makes it on its own disk, where it will run (stage 4, step 5)
            String t = title == null || title.isBlank() ? keeper.name() : title.strip();
            Nodes.Made m = Nodes.make(store, issuer(""), name, t);
            store.addLibrary(m.id(), name, t, keeper.id(), m.folder());
            store.libraryHome(m.id(), m.node());   // the node whose disk holds it (ROB 3)
            return store.library(m.id());
        }
        Path folder = LibraryPlaces.newFolder(root, name);
        LibraryStore ls = new LibraryStore(LibraryPlaces.library(folder));
        ls.init();
        String t = title == null || title.isBlank() ? keeper.name() : title.strip();
        ls.setName(t);
        store.addLibrary(ls.identity().id(), name, t, keeper.id(), folder.toString());
        store.libraryHome(ls.identity().id(), Node.at(store, root));   // this machine's node, when it runs one
        return store.library(ls.identity().id());
    }

    /** A short name for a new library of an account's, no other library's: its sign-in name, with a number after it when that is taken. */
    static String freeName(AccountStore store, AccountStore.Account keeper) throws IOException {
        String base = keeper.login().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", ""), name = base.isEmpty() ? "library" : base;
        for (int n = 2; store.libraryByName(name) != null; n++) name = (base.isEmpty() ? "library" : base) + "-" + n;
        return name;
    }

    // ---- the call a library passes on: its people and their keys ----

    /** Whether an address is the host of a node of this service: its libraries call from there. */
    private boolean nodeHost(InetAddress a) {
        try {
            for (AccountStore.NodeRow n : store.nodes()) {
                if (n.address().isEmpty()) continue;
                String h = URI.create(n.address()).getHost();
                if (h != null && InetAddress.getByName(h.replace("[", "").replace("]", "")).equals(a)) return true;
            }
        } catch (IOException | RuntimeException e) { return false; }
        return false;
    }

    /** The service's own keys, by their ids. */
    PublicKey ownKey(String kid) throws IOException {
        for (String k : store.publicJwks()) { JsonNode j = Statement.M.readTree(k); if (j.path("kid").asText("").equals(kid)) return Statement.fromJwk(j); }
        return null;
    }

    /** What answers whether an account may spend (stage 3): the built-in caps unless a setting names another. */
    private final Spending spending;

    /**
     * A library asks before a use and tells when it ends (stage 3). A start carries the request's statement: the account is its
     * person, or for the service's own work in a library (its night) the library's keeper. An end names the use it opened.
     */
    /** Where a program asks for its account's month, and where a library asks for the month of the account its request is. */
    static final String USAGE = "/v1/account/usage", USAGE_INTERNAL = "/internal/account/usage";

    /** A library asks for the month of the account its request's statement names (its spend call, stage 3, step 5). */
    private void internalUsage(HttpExchange x) throws IOException {
        InetSocketAddress from = x.getRemoteAddress();
        if (Proxy.proxied(x) || from == null || from.getAddress() == null || !(from.getAddress().isLoopbackAddress() || nodeHost(from.getAddress()))) { json(x, 404, error("not_found", "Nothing is served at " + x.getRequestURI().getPath())); return; }
        JsonNode args = Statement.M.readTree(x.getRequestBody().readAllBytes());
        Statement.Claims c;
        try { c = Statement.read(x.getRequestHeaders().getFirst(Statement.HEADER), this::ownKey, issuer); } catch (IOException e) { json(x, 403, error("forbidden", e.getMessage())); return; }
        AccountStore.Library lib = store.library(c.aud());
        AccountStore.Account who = lib == null ? null : c.via().equals(Statement.SERVICE) ? store.account(lib.owner()) : store.account(c.sub());
        if (who == null || !who.active()) { json(x, 403, error("forbidden", "Your account may not open this library.")); return; }
        String lang = args.path("lang").asText("");
        if (lang.matches("[a-z]{2}(-[A-Za-z]{2,4})?")) Chrome.here(lang);
        try { json(x, 200, AccountPages.monthJson(store, who)); } finally { Chrome.clear(); }
    }

    /** A library asks whether a use of its has reached its cap: the sentence the person is told when it has (stage 3, step 5). */
    private void internalUseState(HttpExchange x) throws IOException {
        InetSocketAddress from = x.getRemoteAddress();
        if (Proxy.proxied(x) || from == null || from.getAddress() == null || !(from.getAddress().isLoopbackAddress() || nodeHost(from.getAddress()))) { json(x, 404, error("not_found", "Nothing is served at " + x.getRequestURI().getPath())); return; }
        JsonNode args = Statement.M.readTree(x.getRequestBody().readAllBytes());
        if (!keyed(args)) { json(x, 403, error("forbidden", "A use's state is told to the library that has it.")); return; }
        AccountStore.Use u = store.use(args.path("use").asText(""));
        String lang = args.path("lang").asText("");
        if (lang.matches("[a-z]{2}(-[A-Za-z]{2,4})?")) Chrome.here(lang);
        String said;
        // the work's minutes from when it began to run, as the library says, never before the use was opened nor after now
        long started = Math.max(u == null ? 0 : u.openedAt(), Math.min(args.path("started_ms").asLong(0), System.currentTimeMillis()));
        try { said = u == null || !u.open() ? null : NodeGates.reached(store, u, started); } finally { Chrome.clear(); }
        ObjectNode o = Statement.M.createObjectNode();
        o.put("over", said != null);
        if (said != null) o.put("said", said);
        json(x, 200, o);
    }

    private void internalUse(HttpExchange x, boolean start) throws IOException {
        InetSocketAddress from = x.getRemoteAddress();
        if (Proxy.proxied(x) || from == null || from.getAddress() == null || !(from.getAddress().isLoopbackAddress() || nodeHost(from.getAddress()))) { json(x, 404, error("not_found", "Nothing is served at " + x.getRequestURI().getPath() + ".")); return; }
        JsonNode args = Statement.M.readTree(x.getRequestBody().readAllBytes());
        if (!start) {
            if (!keyed(args)) { json(x, 403, error("forbidden", "A use is ended by the library that has it.")); return; }
            String state = args.path("state").asText("done");
            AccountStore.Use u;
            try { u = Uses.end(store, spending, args.path("use").asText(""), Set.of("done", "stopped", "failed").contains(state) ? state : "done"); }
            catch (IOException e) { LOG.warn("The end of the use {} could not be written: {}", args.path("use").asText(""), e.getMessage()); json(x, 503, error("unavailable", "The end could not be written; tell it again.")); return; }   // the library keeps it, and tells it again
            ObjectNode o = Statement.M.createObjectNode();
            o.put("closed", u != null);
            if (u != null) o.put("actual_dollars", u.actualMicros() / 1_000_000.0);
            json(x, 200, o);
            return;
        }
        Statement.Claims c;
        try { c = Statement.read(x.getRequestHeaders().getFirst(Statement.HEADER), this::ownKey, issuer); } catch (IOException e) { json(x, 403, error("forbidden", e.getMessage())); return; }
        AccountStore.Library lib = store.library(c.aud());
        String account = null;
        if (lib != null && c.via().equals(Statement.SERVICE)) account = lib.owner();   // the service's own work in a library: its night, for its keeper
        else if (lib != null) { AccountStore.Account who = store.account(c.sub()); if (who != null && who.active() && store.level(lib, who.id()) != null) account = who.id(); }
        AccountStore.Account asking = lib == null ? null : c.via().equals(Statement.SERVICE) ? store.account(lib.owner()) : store.account(c.sub());
        String lang = args.path("lang").asText("");
        if (asking != null && !asking.active() && !asking.state().equals("removed") || lib != null && account != null && !AccountActs.keeperOpen(store, lib)) {   // suspended, or being deleted (stage 4)
            if (lang.matches("[a-z]{2}(-[A-Za-z]{2,4})?")) Chrome.here(lang);
            try {
                ObjectNode refused = Statement.M.createObjectNode();
                refused.put("use", ""); refused.put("state", "refused");
                refused.put("said", asking != null && !asking.active() ? AccountActs.notActiveSaid(store, asking) : AccountActs.closedSaid(lib));
                json(x, 200, refused);
            } finally { Chrome.clear(); }
            return;
        }
        if (account == null) { json(x, 403, error("forbidden", "Your account may not open this library.")); return; }
        // one request starts at most so many uses: a library cannot keep opening uses charged to a visitor with the statement it was given
        if (!store.oneMoreUse(statementHash(x.getRequestHeaders().getFirst(Statement.HEADER)), USES_PER_STATEMENT, store.clock() + 10 * 60_000L)) {
            json(x, 403, error("forbidden", "This request's statement has started as many uses as one request can.")); return;
        }
        if (lang.matches("[a-z]{2}(-[A-Za-z]{2,4})?")) Chrome.here(lang);   // what the person is told, in their language
        AccountStore.Use u;
        try {
            String kind = args.path("kind").asText("other");
            // the person's own key for the host of the model the use runs on: their work, counted as theirs and not against the operator's dollars
            String payer = kind.equals(CapsSpending.ADD) || kind.equals(CapsSpending.UPLOAD) ? "operator" : OwnKeys.payerFor(store, account, args.path("entry").asText(""));
            u = Uses.start(store, spending, account, lib.id(), kind, args.path("entry").asText(""), payer,
                    Math.round(args.path("estimate_dollars").asDouble(0) * 1_000_000), args.path("estimate_minutes").asLong(0), args.path("job").asText(""));
        } finally { Chrome.clear(); }
        ObjectNode o = Statement.M.createObjectNode();
        o.put("use", u.id()); o.put("state", u.state()); o.put("said", u.said());
        if (!u.id().isEmpty()) o.put("key", useKey(u.id()));   // what its end and its state are told with
        o.put("ceiling_dollars", u.ceilingMicros() < 0 ? -1 : u.ceilingMicros() / 1_000_000.0); o.put("ceiling_minutes", u.ceilingMinutes());
        json(x, 200, o);
    }

    /**
     * The most uses one request's statement starts (round F): one chat turn's own use, and the runs the most tool calls a turn makes
     * ({@link Librarian#MAX_CALLS}, 8) can each file, a list's items sent as runs being the most (200 items, eight to a run: 25), and one
     * more: 1 + 8 × 25 + 1 = 202 with the default settings. A held act's yes is a request of its own, with its own statement.
     */
    static volatile int USES_PER_STATEMENT = 2 + Librarian.MAX_CALLS * Math.max((Items.MAX_ITEMS + Items.BATCH - 1) / Items.BATCH, Questions.MAX_RUNS);

    /** A statement as it is written down once it is used: its hash, never the statement itself. */
    static String statementHash(String statement) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((statement == null ? "" : statement).getBytes(StandardCharsets.US_ASCII))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** Whether a call about a use carries the key the service gave with it. */
    private boolean keyed(JsonNode args) {
        String use = args.path("use").asText(""), key = args.path("key").asText("");
        return !use.isEmpty() && !key.isEmpty() && MessageDigest.isEqual(key.getBytes(StandardCharsets.US_ASCII), useKey(use).getBytes(StandardCharsets.US_ASCII));
    }

    private void internalAccess(HttpExchange x) throws IOException {
        // a library's own call, from this machine: nobody who came through a router, and nobody from elsewhere, is answered here
        InetSocketAddress from = x.getRemoteAddress();
        if (Proxy.proxied(x) || from == null || from.getAddress() == null || !(from.getAddress().isLoopbackAddress() || nodeHost(from.getAddress()))) { json(x, 404, error("not_found", "Nothing is served at " + ServiceAccess.PATH + ".")); return; }
        Statement.Claims c;
        try { c = Statement.read(x.getRequestHeaders().getFirst(Statement.HEADER), this::ownKey, issuer); } catch (IOException e) { json(x, 403, error("forbidden", e.getMessage())); return; }
        JsonNode args = Statement.M.readTree(x.getRequestBody().readAllBytes());
        // a change made for the person is made once for each statement their request brought: a library cannot use one again (round F, S8)
        String op = args.path("op").asText("list");
        if (!op.equals("list") && !op.equals("keys") && !store.once(statementHash(x.getRequestHeaders().getFirst(Statement.HEADER)), store.clock() + 10 * 60_000L)) {
            json(x, 403, error("forbidden", "This request's statement was used already; ask again from the page.")); return;
        }
        AccountStore.Library lib = store.library(c.aud());
        AccountStore.Account who = store.account(c.sub());
        String level = who == null || !who.active() ? null : store.level(lib, who.id());   // as the store says now, not as the statement said a moment ago
        if (level == null) { json(x, 403, error("forbidden", "Your account may not open this library.")); return; }
        try { json(x, 200, AccountPages.access(this, lib, who, level, c.via(), args)); }
        catch (ProtocolError e) { json(x, LibrarianDaemon.status(e.code), error(e)); }
        catch (IOException e) { json(x, 400, error("invalid_args", e.getMessage())); }
    }

    // ---- plumbing ----

    static ObjectNode error(String code, String message) { ObjectNode e = Statement.M.createObjectNode(); e.putObject("error").put("code", code).put("message", message); return e; }

    /** A protocol error as the library answers it over HTTP: its code and message, and the fields it carries beside them (retry_after). */
    static ObjectNode error(ProtocolError p) {
        ObjectNode e = error(p.code, p.getMessage());
        if (p.extra != null) ((ObjectNode) e.get("error")).setAll(p.extra.deepCopy());
        return e;
    }

    /**
     * A request a limit refused (stage 4): 429 with Retry-After and the limit's sentence in the person's language, shown as the surface it
     * came from shows a sentence: the sign-in page with it; for a browser's page, a page that says it, with the way back; for an MCP call, a
     * JSON-RPC error with the code rate_limited, the form every MCP error of the library has and the one a host's model reads (an MCP client
     * that is told 429 reports a failed connection, and some drop the body); for a program, the protocol's error. An account's refusal is
     * written to the audit log, at most once a minute.
     */
    private void tooMany(HttpExchange x, Who who, String path, String method, RateLimits.Refused r) throws IOException {
        if (who != null) limits.noted(who.account(), AccountActs.By.of(who, x), r.limit());
        RateLimits.retryAfter(x, r);
        Chrome.here(Chrome.choose(null, null, query(x).get(Chrome.QUERY), Pages.cookie(x, Chrome.COOKIE), x.getRequestHeaders().getFirst("Accept-Language")));
        try {
            ProtocolError said = ProtocolError.rateLimited(r.said(), r.seconds());
            if (path.equals("/rpc") && method.equals("POST")) {
                JsonNode req;
                try { req = Statement.M.readTree(x.getRequestBody().readNBytes(1 << 20)); } catch (IOException e) { req = null; }
                JsonNode id = req == null ? null : req.get("id");
                if (id != null && !id.isNull()) {
                    ObjectNode env = Statement.M.createObjectNode();
                    env.put("jsonrpc", "2.0");
                    env.set("id", id);
                    ObjectNode err = env.putObject("error");
                    err.put("code", said.rpc); err.put("message", said.getMessage());
                    err.putObject("data").put("code", said.code).put("retry_after", r.seconds());
                    json(x, 200, env);
                    return;
                }
            }
            String accept = x.getRequestHeaders().getFirst("Accept");
            if ((who == null || who.browser()) && accept != null && accept.contains("text/html")) {
                Pages.send(x, 429, path.equals("/login") ? AccountPages.loginPage(this, said.getMessage()) : AccountPages.waitPage(this, who, said.getMessage(), method.equals("GET") ? x.getRequestURI().toString() : null));
                return;
            }
            json(x, 429, error(said));
        } finally { Chrome.clear(); }
    }

    static void json(HttpExchange x, int status, JsonNode body) throws IOException {
        byte[] b = Statement.M.writeValueAsBytes(body);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.sendResponseHeaders(status, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    static Map<String, String> query(HttpExchange x) {
        Map<String, String> m = new HashMap<>();
        String q = x.getRequestURI().getRawQuery();
        if (q == null) return m;
        for (String kv : q.split("&")) { int eq = kv.indexOf('='); m.put(URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8), eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8)); }
        return m;
    }
}
