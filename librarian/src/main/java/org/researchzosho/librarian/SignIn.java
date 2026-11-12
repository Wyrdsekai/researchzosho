package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.researchzosho.librarian.Pages.esc;
import static org.researchzosho.librarian.Pages.t;

/**
 * The pages where a person signs in and keeps their account (0.6.1): the sign-in page (name and password, a passkey, a program's token,
 * and asking to be let in); the join link, which asks the invited person to choose a password; the one-time-code step after the
 * password; and the account page, where a person changes their name or password, adds a passkey, sets up the one-time code and signs
 * every browser out. The owner, on the library's own computer, makes their account on the same page.
 */
final class SignIn {
    private SignIn() { }

    /** The half of a sign-in done while the one-time code is awaited: a secret in the browser's cookie, the account it stands for here, for five minutes. */
    static final String HALF = "rz_half";
    private record Half(String did, long made) { }
    private static final Map<String, Half> HALVES = new ConcurrentHashMap<>();

    static final String WAITING = "Your request is waiting for the person who keeps this library. Come back to this page later: when they have let you in, you will be signed in.";

    // ---- the sign-in page ----

    static String loginPage(LibraryStore store, String note, HttpExchange x) throws IOException { return loginPage(store, note, x, null); }

    static String loginPage(LibraryStore store, String note, HttpExchange x, String waiting) throws IOException {
        StringBuilder b = new StringBuilder();
        if (note != null) b.append("<p class=\"err\">").append(esc(note)).append("</p>");
        if (!WebAccess.signInRequired()) b.append("<p>").append(t("Access is open.")).append(" ").append(t("Signing in only puts your name on what you send.")).append(" ")
                .append(t("Run {0} on the computer where the library lives to change that.", "<code>" + esc(WebAccess.OPEN_COMMAND) + "</code>")).append("</p>");
        else b.append("<p class=\"k\">").append(t("Sign in with the name and password you chose when you were let in. The person who keeps this library signs in the same way.")).append("</p>");
        b.append("<form method=\"post\" action=\"/login\" class=\"stack\" id=\"signin\"><label>").append(t("Name")).append("<br><input name=\"name\" size=\"40\" autocomplete=\"username\" autofocus></label>")
         .append("<label>").append(t("Password")).append("<br><input type=\"password\" name=\"password\" size=\"40\" autocomplete=\"current-password\"></label><button>").append(t("Sign in")).append("</button></form>");
        if (Oidc.configured()) b.append("<p><a class=\"btn\" href=\"/login/oidc\">").append(t("Sign in with {0}", esc(Oidc.name()))).append("</a> <span class=\"k\">").append(t("your work or school sign-in")).append("</span></p>");
        b.append("<p><button type=\"button\" id=\"passkey\" class=\"quiet\">").append(t("Sign in with a passkey")).append("</button> <span class=\"k\" id=\"passkey-why\"></span></p>");
        String login = x == null ? null : (String) x.getAttribute(LibrarianDaemon.IDENTITY_LOGIN), name = x == null ? null : (String) x.getAttribute(LibrarianDaemon.IDENTITY_NAME);
        b.append("<h2>").append(t("Ask to be let in")).append("</h2>");
        if (waiting != null) b.append("<p class=\"note\">").append(esc(waiting)).append("</p>");
        else if (x != null && Pages.cookie(x, Pages.ASK) != null) b.append("<p class=\"note\">").append(esc(t(WAITING))).append("</p>");
        else b.append("<p class=\"k\">").append(t("No account here yet? Ask. Choose the password you will sign in with; the person who keeps this library sees the request and lets you in, or not."))
              .append(login == null ? "" : " " + t("You are {0} to the relay you came through, and the request says so.", esc(login))).append("</p>")
              .append("<form method=\"post\" action=\"/login\" class=\"stack\"><input type=\"hidden\" name=\"ask\" value=\"1\"><label>").append(t("Your name")).append("<br><input name=\"name\" size=\"40\" autocomplete=\"name\" value=\"").append(esc(name == null ? "" : name)).append("\"></label>")
              .append("<label>").append(t("A password of your choosing (at least {0} characters)", Passwords.MIN)).append("<br><input type=\"password\" name=\"password\" size=\"40\" autocomplete=\"new-password\"></label>")
              .append("<label>").append(t("A note for them")).append("<br><input name=\"note\" size=\"60\"></label><button>").append(Chrome.now().tc("Ask", "the button that sends a request to be let into the library")).append("</button></form>");
        b.append("<details><summary>").append(t("A program's token")).append("</summary><p class=\"k\">").append(t("A program (an MCP host, a script) signs in with a token the person who keeps the library makes for it. A person pastes one here only when they were given one.")).append("</p>")
         .append("<form method=\"post\" action=\"/login\" class=\"stack\"><label>").append(t("Token")).append("<br><input name=\"token\" size=\"60\"></label><button class=\"quiet\">").append(t("Sign in with the token")).append("</button></form>")
         .append("<p class=\"k\">").append(t("On the computer where the library lives:")).append("</p><pre>").append(esc(Patrons.TOKEN_HOWTO)).append("\n").append(esc(Patrons.INVITE_HOWTO)).append("</pre></details>");
        b.append("<script>").append(script(LOGIN_JS)).append("</script>");
        return Pages.page(store, Patrons.Patron.ANONYMOUS, t("Sign in"), b.toString());
    }

    /** POST /login: name and password, a program's token, or asking to be let in. */
    static void login(HttpExchange x, LibraryStore store, Map<String, String> form) throws IOException {
        if ("1".equals(form.get("ask"))) { Pages.send(x, 200, askToBeLetIn(store, x, form)); return; }
        String token = form.getOrDefault("token", "").strip();
        if (!token.isEmpty()) {
            Patrons.Entry e = Patrons.resolve(store, token);
            if (e == null) { Pages.send(x, 403, loginPage(store, t("That token does not match anyone. Ask the person who keeps this library for a new link or a new token."), x)); return; }
            Pages.setCookie(x, token);
            Pages.redirect(x, "/");
            return;
        }
        String name = form.getOrDefault("name", "").strip(), password = form.getOrDefault("password", "");
        if (name.isEmpty() || password.isEmpty()) { Pages.send(x, 200, loginPage(store, t("Type your name and your password."), x)); return; }
        Accounts.Account a;
        try { a = Accounts.signIn(store, name, password); } catch (IOException e) { Pages.send(x, 429, loginPage(store, e.getMessage(), x)); return; }
        if (a == null) { Pages.send(x, 403, loginPage(store, t("That name and password do not match. The person who keeps this library can send you a new link if you have forgotten yours."), x)); return; }
        if (!a.owner()) {
            Patrons.Entry listed = Patrons.byDid(store, a.did());
            if (listed == null || listed.level() == Patrons.Level.deny) { Pages.send(x, 403, loginPage(store, t("This account is no longer a reader of this library. Ask the person who keeps it."), x)); return; }
        }
        if (a.secondStep()) {
            String half = Passwords.secret(24);
            prune();
            HALVES.put(half, new Half(a.did(), System.currentTimeMillis()));
            x.getResponseHeaders().add("Set-Cookie", HALF + "=" + half + "; Path=/; HttpOnly; SameSite=Lax" + (Pages.secure(x) ? "; Secure" : "") + "; Max-Age=300");
            Pages.send(x, 200, codePage(store, null));
            return;
        }
        signedIn(x, store, a);
        Pages.redirect(x, "/");
    }

    /** The one-time code asked after the password. */
    static String codePage(LibraryStore store, String note) throws IOException {
        return Pages.page(store, Patrons.Patron.ANONYMOUS, t("One more step"), (note == null ? "" : "<p class=\"err\">" + esc(note) + "</p>")
                + "<p class=\"k\">" + t("Your account asks for a one-time code: open your authenticator app and type the six digits it shows for this library.") + "</p>"
                + "<form method=\"post\" action=\"/login/code\" class=\"stack\"><label>" + t("Code") + "<br><input name=\"code\" size=\"12\" inputmode=\"numeric\" autocomplete=\"one-time-code\" autofocus></label><button>" + t("Sign in") + "</button></form>");
    }

    /** POST /login/code. */
    static void code(HttpExchange x, LibraryStore store, Map<String, String> form) throws IOException {
        String half = Pages.cookie(x, HALF);
        prune();
        Half h = half == null ? null : HALVES.get(half);
        if (h == null) { Pages.send(x, 403, loginPage(store, t("That took more than five minutes. Sign in again."), x)); return; }
        boolean ok;
        try { ok = Accounts.takeCode(store, h.did(), form.getOrDefault("code", "")); } catch (IOException e) { Pages.send(x, 429, codePage(store, e.getMessage())); return; }
        if (!ok) { Pages.send(x, 403, codePage(store, t("That code is not right, or was used already. Wait for the next one and try again."))); return; }
        HALVES.remove(half);
        x.getResponseHeaders().add("Set-Cookie", HALF + "=; Path=/; HttpOnly; SameSite=Lax" + (Pages.secure(x) ? "; Secure" : "") + "; Max-Age=0");
        signedIn(x, store, Accounts.byDid(store, h.did()));
        Pages.redirect(x, "/");
    }

    private static void prune() {
        long cutoff = System.currentTimeMillis() - 5 * 60_000L;
        HALVES.entrySet().removeIf(e -> e.getValue().made() < cutoff);
    }

    /** This browser signed in to the account: a session made, its secret in the cookie. */
    static void signedIn(HttpExchange x, LibraryStore store, Accounts.Account a) throws IOException {
        String secret = Accounts.newSession(store, a.did(), browser(x.getRequestHeaders().getFirst("User-Agent")));
        Pages.setCookie(x, secret);
    }

    /** The browser in a few words, for the list of where an account is signed in. */
    static String browser(String ua) {
        if (ua == null || ua.isBlank()) return "a browser";
        String b = ua.contains("Edg/") ? "Edge" : ua.contains("OPR/") ? "Opera" : ua.contains("Firefox/") ? "Firefox" : ua.contains("Chrome/") ? "Chrome" : ua.contains("Safari/") ? "Safari" : "a browser";
        String on = ua.contains("iPhone") ? "an iPhone" : ua.contains("iPad") ? "an iPad" : ua.contains("Android") ? "Android" : ua.contains("Windows") ? "Windows" : ua.contains("Mac OS") ? "a Mac" : ua.contains("Linux") ? "Linux" : "";
        return on.isEmpty() ? b : b + " on " + on;
    }

    /** POST /login with ask=1: the request filed with the password chosen, its claim kept in this browser, the page says it waits. */
    private static String askToBeLetIn(LibraryStore store, HttpExchange x, Map<String, String> form) throws IOException {
        String name = form.getOrDefault("name", "").strip(), note = form.getOrDefault("note", "").strip(), password = form.getOrDefault("password", "");
        if (name.length() > 120 || note.length() > 400) return loginPage(store, t("Keep the name under 120 characters and the note under 400."), x);
        if (name.isEmpty()) return loginPage(store, t("Say your name."), x);
        String why = Passwords.why(password);
        if (why != null) return loginPage(store, why, x);
        if (Accounts.byLogin(store, name) != null) return loginPage(store, t("Someone signs in here as \"{0}\" already. If that is you, sign in above; else ask with another name.", name), x);
        String login = (String) x.getAttribute(LibrarianDaemon.IDENTITY_LOGIN);
        String did = login != null ? Patrons.loginDid(login) : "did:key:web-" + Pages.randomHex(6);
        try {
            AccessRequests.Filed f = AccessRequests.request(store, did, name, note, Passwords.hash(password));
            if (f.fresh()) x.getResponseHeaders().add("Set-Cookie", Pages.ASK + "=" + f.id() + "." + f.claim() + "; Path=/; HttpOnly; SameSite=Lax" + (Pages.secure(x) ? "; Secure" : "") + "; Max-Age=2592000");
            return loginPage(store, null, x, t(WAITING));
        } catch (IOException e) { return loginPage(store, e.getMessage(), x); }
    }

    /** A visitor who asked to be let in, on a later load: the owner's answer. True when they are signed in now (the daemon loads the page again as them). */
    static boolean collectAsk(HttpExchange x, LibraryStore store, String ask) {
        int dot = ask.indexOf('.');
        if (dot <= 0) { Pages.clearAsk(x); return false; }
        try {
            String id = ask.substring(0, dot);
            AccessRequests.Outcome o = AccessRequests.claim(store, id, ask.substring(dot + 1));
            if (!o.token().isEmpty()) {
                AccessRequests.Request r = AccessRequests.byId(store, id);
                Accounts.Account a = r == null ? null : Accounts.byDid(store, r.did());
                if (a != null) signedIn(x, store, a); else Pages.setCookie(x, o.token());
                Pages.clearAsk(x);
                return true;
            }
            if (!o.state().equals("pending")) Pages.clearAsk(x);   // refused, or collected before: nothing more to wait for
            return false;
        } catch (IOException e) { Pages.clearAsk(x); return false; }
    }

    // ---- the join link ----

    /** GET /join?code=…: the invited person chooses a password; POST: the account made, the browser signed in. */
    static void join(HttpExchange x, LibraryStore store, String method, Map<String, String> q, Map<String, String> form) throws IOException {
        String code = "POST".equals(method) ? form.getOrDefault("code", "") : q.getOrDefault("code", "");
        Invites.Opened o = Invites.peek(store, code);
        if (!o.ok()) { Pages.send(x, 403, Pages.page(store, Patrons.Patron.ANONYMOUS, t("This link does not open"), "<p class=\"err\">" + esc(o.why()) + "</p><p class=\"k\"><a href=\"/login\">" + t("Sign in another way") + "</a></p>")); return; }
        AccessRequests.Request r = AccessRequests.byId(store, o.id());
        String name = r == null ? "" : r.name();
        if (!"POST".equals(method)) { Pages.send(x, 200, joinPage(store, code, name, null)); return; }
        String password = form.getOrDefault("password", ""), again = form.getOrDefault("again", "");
        String why = Passwords.why(password);
        if (why != null) { Pages.send(x, 200, joinPage(store, code, name, why)); return; }
        if (!password.equals(again)) { Pages.send(x, 200, joinPage(store, code, name, t("The two passwords differ. Type the same one twice."))); return; }
        Invites.Opened opened = Invites.open(store, code);
        if (!opened.ok()) { Pages.send(x, 403, Pages.page(store, Patrons.Patron.ANONYMOUS, t("This link does not open"), "<p class=\"err\">" + esc(opened.why()) + "</p>")); return; }
        Accounts.Account a = Accounts.create(store, opened.did(), name, password);
        signedIn(x, store, a);
        Pages.redirect(x, "/");
    }

    private static String joinPage(LibraryStore store, String code, String name, String note) throws IOException {
        return Pages.page(store, Patrons.Patron.ANONYMOUS, name.isEmpty() ? t("Welcome") : t("Welcome, {0}", name), (note == null ? "" : "<p class=\"err\">" + esc(note) + "</p>")
                + "<p>" + t("You were invited to {0}. Choose a password: with your name and it you sign in from any phone or computer.", "<b>" + esc(store.identity().name()) + "</b>") + "</p>"
                + "<form method=\"post\" action=\"/join\" class=\"stack\"><input type=\"hidden\" name=\"code\" value=\"" + esc(code) + "\">"
                + "<label>" + t("Your name") + "<br><input value=\"" + esc(name) + "\" disabled></label>"
                + "<label>" + t("Password (at least {0} characters)", Passwords.MIN) + "<br><input type=\"password\" name=\"password\" size=\"40\" autocomplete=\"new-password\" autofocus></label>"
                + "<label>" + t("The same again") + "<br><input type=\"password\" name=\"again\" size=\"40\" autocomplete=\"new-password\"></label><button>" + t("Sign in") + "</button></form>"
                + "<p class=\"k\">" + t("On the account page you can add a passkey (your fingerprint, face or security key) and a one-time code afterwards.") + "</p>");
    }

    // ---- the account page ----

    /** Whether this caller has an account page: the owner (signed in, or on the library's own computer) and any signed-in reader. */
    static boolean hasAccountPage(Patrons.Patron patron) { return patron.person() || (!patron.anonymous() && !patron.web()); }

    static String account(LibraryStore store, Patrons.Patron patron, Map<String, String> q, HttpExchange x) throws IOException {
        if (!hasAccountPage(patron)) throw ProtocolError.forbidden(t("Sign in to see your account."));
        Accounts.Account a = Accounts.byDid(store, patron.did());
        StringBuilder b = new StringBuilder();
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(esc(note)).append("</p>");
        if (a == null) {
            // no account yet: the owner on the library's own computer, or a reader a relay signed in (Tailscale, Cloudflare Access) who wants a password too
            b.append("<p>").append(patron.person() ? t("You are the person who keeps this library, on its own computer. Make your account, so you can sign in from any other phone or computer with your name and password.")
                                     : t("You are signed in as {0} by the relay you came through. Choose a password too, and you can sign in with your name and it from any phone or computer.", "<b>" + esc(patron.name()) + "</b>")).append("</p>")
             .append("<form method=\"post\" action=\"/account\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"make\"><label>").append(t("Your name")).append("<br><input name=\"name\" size=\"40\" autocomplete=\"name\" value=\"").append(esc(patron.person() ? "" : patron.name())).append("\" autofocus></label>")
             .append("<label>").append(t("Password (at least {0} characters)", Passwords.MIN)).append("<br><input type=\"password\" name=\"password\" size=\"40\" autocomplete=\"new-password\"></label>")
             .append("<label>").append(t("The same again")).append("<br><input type=\"password\" name=\"again\" size=\"40\" autocomplete=\"new-password\"></label><button>").append(t("Make my account")).append("</button></form>");
            if (patron.person()) b.append("<p class=\"k\">").append(t("The same from a terminal: {0}.", "<code>researchzosho account set</code>")).append("</p>");
            return Pages.page(store, patron, t("Your account"), b.toString());
        }
        boolean local = patron.person() && Pages.cookie(x, Pages.COOKIE) == null;   // the owner on the library's own computer: no password asked for a change
        b.append("<p>").append(t("You sign in as {0}, with {1}.", "<b>" + esc(a.name()) + "</b>", esc(a.ways(Chrome.now())))).append(a.owner() ? " " + t("You keep this library.") : "").append("</p>");
        b.append("<h2>").append(t("Name and password")).append("</h2><form method=\"post\" action=\"/account\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"password\">")
         .append("<label>").append(t("Name")).append("<br><input name=\"name\" size=\"40\" value=\"").append(esc(a.name())).append("\" autocomplete=\"username\"></label>");
        if (a.hasPassword() && !local) b.append("<label>").append(t("Current password")).append("<br><input type=\"password\" name=\"current\" size=\"40\" autocomplete=\"current-password\"></label>");
        b.append("<label>").append(t("New password (leave empty to keep it)")).append("<br><input type=\"password\" name=\"password\" size=\"40\" autocomplete=\"new-password\"></label>")
         .append("<label>").append(t("The same again")).append("<br><input type=\"password\" name=\"again\" size=\"40\" autocomplete=\"new-password\"></label><button>").append(t("Save")).append("</button></form>");
        b.append("<h2>").append(t("Passkeys")).append("</h2><p class=\"k\">").append(t("A passkey signs you in with your fingerprint, your face or a security key, with no password to type.")).append(" ").append(x == null ? t("It is made for the address you are at now and works there; the address must be a name, not a number, and https unless it is this computer's own localhost.") : t("It is made for the address you are at now ({0}) and works there; the address must be a name, not a number, and https unless it is this computer's own localhost.", esc(Passkeys.rpId(x)))).append("</p>");
        if (!a.passkeys().isEmpty()) {
            b.append("<table><tr><th>").append(t("passkey")).append("</th><th>").append(t("made")).append("</th><th>").append(t("for")).append("</th><th></th></tr>\n");
            for (Accounts.Passkey k : a.passkeys()) b.append("<tr><td>").append(esc(k.label())).append("</td><td>").append(esc(Chrome.now().day(k.made()))).append("</td><td>").append(esc(k.rp())).append("</td><td><form class=\"inline\" method=\"post\" action=\"/account\"><input type=\"hidden\" name=\"op\" value=\"passkey-remove\"><input type=\"hidden\" name=\"id\" value=\"").append(esc(k.id())).append("\"><button class=\"quiet\">").append(t("remove")).append("</button></form></td></tr>\n");
            b.append("</table>\n");
        }
        b.append("<p><button type=\"button\" id=\"passkey-add\" class=\"quiet\">").append(t("Add a passkey")).append("</button> <span class=\"k\" id=\"passkey-why\"></span></p>");
        b.append("<h2>").append(t("One-time code")).append("</h2>");
        if (a.secondStep()) {
            b.append("<p>").append(t("On since {0}: after your password, the library asks for the six digits your authenticator app shows. A passkey needs no code.", esc(Chrome.now().day(a.code().since())))).append("</p>")
             .append("<form method=\"post\" action=\"/account\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"code-off\"><button class=\"quiet\">").append(t("Turn the code off")).append("</button></form>");
        } else if ("1".equals(q.get("code"))) {
            String secret = Totp.newSecret(), uri = Totp.uri("ResearchZosho " + store.identity().name(), a.name(), secret);
            b.append("<p>").append(t("Scan this with an authenticator app (Google Authenticator, Microsoft Authenticator, Aegis, 1Password, Apple's Passwords…), or type the letters into it. Then enter the code the app shows, to turn the step on.")).append("</p>")
             .append("<p>").append(QrCode.svg(uri, 5)).append("</p><p><code>").append(esc(secret)).append("</code></p>")
             .append("<form method=\"post\" action=\"/account\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"code-on\"><input type=\"hidden\" name=\"secret\" value=\"").append(esc(secret)).append("\">")
             .append("<label>").append(t("The code the app shows")).append("<br><input name=\"code\" size=\"12\" inputmode=\"numeric\" autocomplete=\"one-time-code\"></label><button>").append(t("Turn the code on")).append("</button></form>");
        } else {
            b.append("<p class=\"k\">").append(t("A second step after the password: a six-digit code from an authenticator app on your phone. Off.")).append("</p><p><a href=\"/account?code=1\">").append(t("Set up the one-time code")).append("</a></p>");
        }
        b.append("<h2>").append(t("Where you are signed in")).append("</h2>");
        if (a.sessions().isEmpty()) b.append("<p class=\"k\">").append(t("No browser is signed in with a password or passkey.")).append("</p>");
        else {
            b.append("<table><tr><th>").append(t("browser")).append("</th><th>").append(t("since")).append("</th></tr>\n");
            for (Accounts.Session s : a.sessions()) b.append("<tr><td>").append(esc(s.label().isEmpty() ? t("a browser") : s.label())).append("</td><td>").append(esc(Chrome.now().day(s.made()))).append("</td></tr>\n");
            b.append("</table>\n<form method=\"post\" action=\"/account\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"signout-all\"><button class=\"quiet\">").append(t("Sign out everywhere")).append("</button></form>");
        }
        b.append("<h2>").append(t("For a program of yours")).append("</h2>").append(ApiKeysPage.section(store, patron, q, "/account"));
        b.append("<script>").append(script(ACCOUNT_JS)).append("</script>");
        return Pages.page(store, patron, t("Your account"), b.toString());
    }

    /** POST /account: the change asked for; the note the page shows after. */
    static String accountPost(HttpExchange x, LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        if (!hasAccountPage(patron)) throw ProtocolError.forbidden(t("Sign in to change your account."));
        String op = form.getOrDefault("op", "");
        Accounts.Account a = Accounts.byDid(store, patron.did());
        boolean local = patron.person() && Pages.cookie(x, Pages.COOKIE) == null;
        try {
            switch (op) {
                case "make" -> {
                    if (a != null) return t("You have an account already.");
                    String pw = form.getOrDefault("password", "");
                    if (!pw.equals(form.getOrDefault("again", ""))) return t("The two passwords differ. Type the same one twice.");
                    Accounts.create(store, patron.did(), form.getOrDefault("name", ""), pw);
                    return t("Your account is made. From any other phone or computer, sign in with your name and password.");
                }
                case "password" -> {
                    if (a == null) return t("There is no account to change.");
                    String name = form.getOrDefault("name", "").strip(), pw = form.getOrDefault("password", "");
                    if (!pw.isEmpty()) {
                        if (a.hasPassword() && !local && !Passwords.check(a.password(), form.getOrDefault("current", ""))) return t("The current password is not right.");
                        if (!pw.equals(form.getOrDefault("again", ""))) return t("The two passwords differ. Type the same one twice.");
                        Accounts.setPassword(store, a.did(), pw);
                    }
                    if (!name.isEmpty() && !name.equals(a.name())) Accounts.rename(store, a.did(), name);
                    return pw.isEmpty() ? (name.equals(a.name()) ? t("Nothing changed.") : t("Your name is {0} now.", name)) : t("Your password is changed.");
                }
                case "passkey-remove" -> { if (a != null) Accounts.removePasskey(store, a.did(), form.getOrDefault("id", "")); return t("The passkey is removed."); }
                case "code-on" -> {
                    if (a == null) return t("There is no account to change.");
                    String secret = form.getOrDefault("secret", "").strip();
                    long step = secret.isEmpty() ? -1 : Totp.stepOf(secret, form.getOrDefault("code", ""), System.currentTimeMillis());
                    if (step < 0) return t("That code is not the one the app shows for this secret. Scan it again and type the current code.");
                    Accounts.setCode(store, a.did(), secret, step);
                    return t("The one-time code is on: after your password the library asks for it.");
                }
                case "code-off" -> { if (a != null) Accounts.clearCode(store, a.did()); return t("The one-time code is off."); }
                case "signout-all" -> { if (a != null) Accounts.signOutAll(store, a.did()); if (!local) Pages.clearCookie(x); return t("Every browser is signed out."); }
                default -> { return t("That is not a change the account page knows."); }
            }
        } catch (IOException e) { return e.getMessage(); }
    }

    // ---- the readers' accounts, from the owner's side ----

    /** A fresh join link for a listed reader: their ways in are taken away, and the link lets them choose a new password. */
    static String renewLink(LibraryStore store, String did) throws IOException {
        Patrons.Entry e = Patrons.byDid(store, did);
        if (e == null) throw new IOException(did + " is not on the readers list.");
        Accounts.Account a = Accounts.byDid(store, did);
        if (a != null) Accounts.reset(store, did);
        return Invites.renew(store, did, e.name().isEmpty() ? did : e.name()).link();
    }

    // ---- the passkey calls (JSON) ----

    /** POST /login/passkey/options and /account/passkey/options. */
    static void passkeyOptions(HttpExchange x, LibraryStore store, Patrons.Patron patron, boolean register) throws IOException {
        if (register) {
            if (!hasAccountPage(patron)) { Pages.sendJson(x, 403, why(t("Sign in first."))); return; }
            Accounts.Account a = Accounts.byDid(store, patron.did());
            if (a == null) { Pages.sendJson(x, 403, why(t("Make your account first."))); return; }
            Pages.sendJson(x, 200, Passkeys.registerOptions(store, a, x));
        } else Pages.sendJson(x, 200, Passkeys.signInOptions(x));
    }

    /** POST /account/passkey: the browser's new credential, checked and kept. */
    static void passkeyRegister(HttpExchange x, LibraryStore store, Patrons.Patron patron) throws IOException {
        if (!hasAccountPage(patron)) { Pages.sendJson(x, 403, why(t("Sign in first."))); return; }
        Accounts.Account a = Accounts.byDid(store, patron.did());
        if (a == null) { Pages.sendJson(x, 403, why(t("Make your account first."))); return; }
        try {
            Passkeys.register(store, a, x, body(x));
            Pages.sendJson(x, 200, ok());
        } catch (IOException e) { Pages.sendJson(x, 400, why(e.getMessage())); }
    }

    /** POST /login/passkey: the browser's assertion, checked; the browser signed in. */
    static void passkeyAssert(HttpExchange x, LibraryStore store) throws IOException {
        try {
            Accounts.Account a = Passkeys.signIn(store, x, body(x));
            if (!a.owner()) {
                Patrons.Entry listed = Patrons.byDid(store, a.did());
                if (listed == null || listed.level() == Patrons.Level.deny) { Pages.sendJson(x, 403, why(t("This account is no longer a reader of this library."))); return; }
            }
            signedIn(x, store, a);
            Pages.sendJson(x, 200, ok());
        } catch (IOException e) { Pages.sendJson(x, 403, why(e.getMessage())); }
    }

    private static JsonNode body(HttpExchange x) throws IOException {
        String s = (String) x.getAttribute(LibrarianDaemon.BODY);
        if (s == null || s.isBlank()) throw new IOException(t("The browser sent nothing."));
        JsonNode n = Accounts.M.readTree(s);
        if (!n.isObject()) throw new IOException(t("The browser sent something that is not a passkey."));
        return n;
    }

    private static ObjectNode ok() { ObjectNode o = Accounts.M.createObjectNode(); o.put("ok", true); return o; }
    private static ObjectNode why(String w) { ObjectNode o = Accounts.M.createObjectNode(); o.put("ok", false); o.put("why", w); return o; }

    // ---- the scripts ----

    /**
     * A script with its sentences in the person's language: each T_… name in it is replaced by its sentence as a JavaScript string, the
     * address or the browser's own error filled into {0} by the script itself.
     */
    static String script(String js) {
        return js.replace("T_NAME", Pages.js(t("Passkeys need an address with a name, not a number: on this computer open {0} instead, or use the https address researchzosho share gives.")))
                .replace("T_HTTPS", Pages.js(t("Passkeys need an https address (researchzosho share --tailscale gives one), or http://localhost on the library's own computer.")))
                .replace("T_NOT_ACCEPTED", Pages.js(t("The passkey was not accepted.")))
                .replace("T_NOT_USED", Pages.js(t("No passkey was used.")))
                .replace("T_COULD_NOT_USE", Pages.js(t("The passkey could not be used: {0}")))
                .replace("T_ADDED", Pages.js(t("The passkey is added.")))
                .replace("T_NOT_KEPT", Pages.js(t("The passkey was not kept.")))
                .replace("T_NOT_MADE", Pages.js(t("No passkey was made.")))
                .replace("T_COULD_NOT_MAKE", Pages.js(t("The passkey could not be made: {0}")));
    }

    /** base64url to bytes and back, shared by both scripts. */
    private static final String B64_JS = "function u2b(s){s=s.replace(/-/g,'+').replace(/_/g,'/');while(s.length%4)s+='=';var r=atob(s),a=new Uint8Array(r.length);for(var i=0;i<r.length;i++)a[i]=r.charCodeAt(i);return a.buffer}"
            + "function b2u(b){var a=new Uint8Array(b),s='';for(var i=0;i<a.length;i++)s+=String.fromCharCode(a[i]);return btoa(s).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=+$/,'')}"
            + "function ready(btn,why){if(/^[\\d.]+$|^\\[/.test(location.hostname)){btn.disabled=true;why.textContent=T_NAME.replace('{0}','http://localhost:'+(location.port||'80')+'/');return false}"
            + "if(!window.isSecureContext||!window.PublicKeyCredential){btn.disabled=true;why.textContent=T_HTTPS;return false}return true}";

    static final String LOGIN_JS = "(function(){var b=document.getElementById('passkey'),w=document.getElementById('passkey-why');if(!b)return;" + B64_JS
            + "if(!ready(b,w))return;b.addEventListener('click',function(){w.textContent='';fetch('/login/passkey/options',{method:'POST'}).then(function(r){return r.json()}).then(function(o){"
            + "o.challenge=u2b(o.challenge);(o.allowCredentials||[]).forEach(function(c){c.id=u2b(c.id)});return navigator.credentials.get({publicKey:o})}).then(function(c){"
            + "var r={id:c.id,rawId:b2u(c.rawId),type:c.type,response:{clientDataJSON:b2u(c.response.clientDataJSON),authenticatorData:b2u(c.response.authenticatorData),signature:b2u(c.response.signature),userHandle:c.response.userHandle?b2u(c.response.userHandle):null}};"
            + "return fetch('/login/passkey',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(r)})}).then(function(r){return r.json()}).then(function(j){if(j.ok)location.href='/';else w.textContent=j.why||T_NOT_ACCEPTED})"
            + ".catch(function(e){w.textContent=(e&&e.name==='NotAllowedError')?T_NOT_USED:T_COULD_NOT_USE.replace('{0}',e&&e.message?e.message:e)})})})();";

    static final String ACCOUNT_JS = "(function(){var b=document.getElementById('passkey-add'),w=document.getElementById('passkey-why');if(!b)return;" + B64_JS
            + "if(!ready(b,w))return;b.addEventListener('click',function(){w.textContent='';fetch('/account/passkey/options',{method:'POST'}).then(function(r){return r.json()}).then(function(o){"
            + "if(o.ok===false)throw new Error(o.why);o.challenge=u2b(o.challenge);o.user.id=u2b(o.user.id);(o.excludeCredentials||[]).forEach(function(c){c.id=u2b(c.id)});return navigator.credentials.create({publicKey:o})}).then(function(c){"
            + "var r={id:c.id,rawId:b2u(c.rawId),type:c.type,label:navigator.userAgent,response:{clientDataJSON:b2u(c.response.clientDataJSON),attestationObject:b2u(c.response.attestationObject)}};"
            + "return fetch('/account/passkey',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(r)})}).then(function(r){return r.json()}).then(function(j){if(j.ok)location.href='/account?note='+encodeURIComponent(T_ADDED);else w.textContent=j.why||T_NOT_KEPT})"
            + ".catch(function(e){w.textContent=(e&&e.name==='NotAllowedError')?T_NOT_MADE:T_COULD_NOT_MAKE.replace('{0}',e&&e.message?e.message:e)})})})();";
}
