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
        if (!WebAccess.signInRequired()) b.append("<p>").append(esc(WebAccess.OPEN_NOTICE)).append(" Signing in only puts your name on what you send. ")
                .append(esc(WebAccess.OPEN_HOWTO).replace("run: ", "Run <code>")).append("</code> on the computer where the library lives to change that.</p>");
        else b.append("<p class=\"k\">Sign in with the name and password you chose when you were let in. The person who keeps this library signs in the same way.</p>");
        b.append("<form method=\"post\" action=\"/login\" class=\"stack\" id=\"signin\"><label>Name<br><input name=\"name\" size=\"40\" autocomplete=\"username\" autofocus></label>")
         .append("<label>Password<br><input type=\"password\" name=\"password\" size=\"40\" autocomplete=\"current-password\"></label><button>Sign in</button></form>");
        b.append("<p><button type=\"button\" id=\"passkey\" class=\"quiet\">Sign in with a passkey</button> <span class=\"k\" id=\"passkey-why\"></span></p>");
        String login = x == null ? null : (String) x.getAttribute(LibrarianDaemon.IDENTITY_LOGIN), name = x == null ? null : (String) x.getAttribute(LibrarianDaemon.IDENTITY_NAME);
        b.append("<h2>Ask to be let in</h2>");
        if (waiting != null) b.append("<p class=\"note\">").append(esc(waiting)).append("</p>");
        else if (x != null && Pages.cookie(x, Pages.ASK) != null) b.append("<p class=\"note\">").append(esc(WAITING)).append("</p>");
        else b.append("<p class=\"k\">No account here yet? Ask. Choose the password you will sign in with; the person who keeps this library sees the request and lets you in, or not.")
              .append(login == null ? "" : " You are " + esc(login) + " to the relay you came through, and the request says so.").append("</p>")
              .append("<form method=\"post\" action=\"/login\" class=\"stack\"><input type=\"hidden\" name=\"ask\" value=\"1\"><label>Your name<br><input name=\"name\" size=\"40\" autocomplete=\"name\" value=\"").append(esc(name == null ? "" : name)).append("\"></label>")
              .append("<label>A password of your choosing (at least ").append(Passwords.MIN).append(" characters)<br><input type=\"password\" name=\"password\" size=\"40\" autocomplete=\"new-password\"></label>")
              .append("<label>A note for them<br><input name=\"note\" size=\"60\"></label><button>Ask</button></form>");
        b.append("<details><summary>A program's token</summary><p class=\"k\">A program (an MCP host, a script) signs in with a token the person who keeps the library makes for it. A person pastes one here only when they were given one.</p>")
         .append("<form method=\"post\" action=\"/login\" class=\"stack\"><label>Token<br><input name=\"token\" size=\"60\"></label><button class=\"quiet\">Sign in with the token</button></form>")
         .append("<p class=\"k\">On the computer where the library lives:</p><pre>").append(esc(Patrons.TOKEN_HOWTO)).append("\n").append(esc(Patrons.INVITE_HOWTO)).append("</pre></details>");
        b.append("<script>").append(LOGIN_JS).append("</script>");
        return Pages.page(store, Patrons.Patron.ANONYMOUS, "Sign in", b.toString());
    }

    /** POST /login: name and password, a program's token, or asking to be let in. */
    static void login(HttpExchange x, LibraryStore store, Map<String, String> form) throws IOException {
        if ("1".equals(form.get("ask"))) { Pages.send(x, 200, askToBeLetIn(store, x, form)); return; }
        String token = form.getOrDefault("token", "").strip();
        if (!token.isEmpty()) {
            Patrons.Entry e = Patrons.resolve(store, token);
            if (e == null) { Pages.send(x, 403, loginPage(store, "That token does not match anyone. Ask the person who keeps this library for a new link or a new token.", x)); return; }
            Pages.setCookie(x, token);
            Pages.redirect(x, "/");
            return;
        }
        String name = form.getOrDefault("name", "").strip(), password = form.getOrDefault("password", "");
        if (name.isEmpty() || password.isEmpty()) { Pages.send(x, 200, loginPage(store, "Type your name and your password.", x)); return; }
        Accounts.Account a;
        try { a = Accounts.signIn(store, name, password); } catch (IOException e) { Pages.send(x, 429, loginPage(store, e.getMessage(), x)); return; }
        if (a == null) { Pages.send(x, 403, loginPage(store, "That name and password do not match. The person who keeps this library can send you a new link if you have forgotten yours.", x)); return; }
        if (!a.owner()) {
            Patrons.Entry listed = Patrons.byDid(store, a.did());
            if (listed == null || listed.level() == Patrons.Level.deny) { Pages.send(x, 403, loginPage(store, "This account is no longer a reader of this library. Ask the person who keeps it.", x)); return; }
        }
        if (a.secondStep()) {
            String half = Passwords.secret(24);
            prune();
            HALVES.put(half, new Half(a.did(), System.currentTimeMillis()));
            x.getResponseHeaders().add("Set-Cookie", HALF + "=" + half + "; Path=/; HttpOnly; SameSite=Lax" + (Pages.onDoor(x) ? "; Secure" : "") + "; Max-Age=300");
            Pages.send(x, 200, codePage(store, null));
            return;
        }
        signedIn(x, store, a);
        Pages.redirect(x, "/");
    }

    /** The one-time code asked after the password. */
    static String codePage(LibraryStore store, String note) throws IOException {
        return Pages.page(store, Patrons.Patron.ANONYMOUS, "One more step", (note == null ? "" : "<p class=\"err\">" + esc(note) + "</p>")
                + "<p class=\"k\">Your account asks for a one-time code: open your authenticator app and type the six digits it shows for this library.</p>"
                + "<form method=\"post\" action=\"/login/code\" class=\"stack\"><label>Code<br><input name=\"code\" size=\"12\" inputmode=\"numeric\" autocomplete=\"one-time-code\" autofocus></label><button>Sign in</button></form>");
    }

    /** POST /login/code. */
    static void code(HttpExchange x, LibraryStore store, Map<String, String> form) throws IOException {
        String half = Pages.cookie(x, HALF);
        prune();
        Half h = half == null ? null : HALVES.get(half);
        if (h == null) { Pages.send(x, 403, loginPage(store, "That took more than five minutes. Sign in again.", x)); return; }
        boolean ok;
        try { ok = Accounts.takeCode(store, h.did(), form.getOrDefault("code", "")); } catch (IOException e) { Pages.send(x, 429, codePage(store, e.getMessage())); return; }
        if (!ok) { Pages.send(x, 403, codePage(store, "That code is not right, or was used already. Wait for the next one and try again.")); return; }
        HALVES.remove(half);
        x.getResponseHeaders().add("Set-Cookie", HALF + "=; Path=/; HttpOnly; SameSite=Lax" + (Pages.onDoor(x) ? "; Secure" : "") + "; Max-Age=0");
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
        if (name.length() > 120 || note.length() > 400) return loginPage(store, "Keep the name under 120 characters and the note under 400.", x);
        if (name.isEmpty()) return loginPage(store, "Say your name.", x);
        String why = Passwords.why(password);
        if (why != null) return loginPage(store, why, x);
        if (Accounts.byLogin(store, name) != null) return loginPage(store, "Someone signs in here as \"" + name + "\" already. If that is you, sign in above; else ask with another name.", x);
        String login = (String) x.getAttribute(LibrarianDaemon.IDENTITY_LOGIN);
        String did = login != null ? Patrons.loginDid(login) : "did:key:web-" + Pages.randomHex(6);
        try {
            AccessRequests.Filed f = AccessRequests.request(store, did, name, note, Passwords.hash(password));
            if (f.fresh()) x.getResponseHeaders().add("Set-Cookie", Pages.ASK + "=" + f.id() + "." + f.claim() + "; Path=/; HttpOnly; SameSite=Lax" + (Pages.onDoor(x) ? "; Secure" : "") + "; Max-Age=2592000");
            return loginPage(store, null, x, WAITING);
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
        if (!o.ok()) { Pages.send(x, 403, Pages.page(store, Patrons.Patron.ANONYMOUS, "This link does not open", "<p class=\"err\">" + esc(o.why()) + "</p><p class=\"k\"><a href=\"/login\">Sign in another way</a></p>")); return; }
        AccessRequests.Request r = AccessRequests.byId(store, o.id());
        String name = r == null ? "" : r.name();
        if (!"POST".equals(method)) { Pages.send(x, 200, joinPage(store, code, name, null)); return; }
        String password = form.getOrDefault("password", ""), again = form.getOrDefault("again", "");
        String why = Passwords.why(password);
        if (why != null) { Pages.send(x, 200, joinPage(store, code, name, why)); return; }
        if (!password.equals(again)) { Pages.send(x, 200, joinPage(store, code, name, "The two passwords differ. Type the same one twice.")); return; }
        Invites.Opened opened = Invites.open(store, code);
        if (!opened.ok()) { Pages.send(x, 403, Pages.page(store, Patrons.Patron.ANONYMOUS, "This link does not open", "<p class=\"err\">" + esc(opened.why()) + "</p>")); return; }
        Accounts.Account a = Accounts.create(store, opened.did(), name, password);
        signedIn(x, store, a);
        Pages.redirect(x, "/");
    }

    private static String joinPage(LibraryStore store, String code, String name, String note) throws IOException {
        return Pages.page(store, Patrons.Patron.ANONYMOUS, "Welcome" + (name.isEmpty() ? "" : ", " + name), (note == null ? "" : "<p class=\"err\">" + esc(note) + "</p>")
                + "<p>You were invited to <b>" + esc(store.identity().name()) + "</b>. Choose a password: with your name and it you sign in from any phone or computer.</p>"
                + "<form method=\"post\" action=\"/join\" class=\"stack\"><input type=\"hidden\" name=\"code\" value=\"" + esc(code) + "\">"
                + "<label>Your name<br><input value=\"" + esc(name) + "\" disabled></label>"
                + "<label>Password (at least " + Passwords.MIN + " characters)<br><input type=\"password\" name=\"password\" size=\"40\" autocomplete=\"new-password\" autofocus></label>"
                + "<label>The same again<br><input type=\"password\" name=\"again\" size=\"40\" autocomplete=\"new-password\"></label><button>Sign in</button></form>"
                + "<p class=\"k\">On the account page you can add a passkey (your fingerprint, face or security key) and a one-time code afterwards.</p>");
    }

    // ---- the account page ----

    /** Whether this caller has an account page: the owner (signed in, or on the library's own computer) and any signed-in reader. */
    static boolean hasAccountPage(Patrons.Patron patron) { return patron.person() || (!patron.anonymous() && !patron.web()); }

    static String account(LibraryStore store, Patrons.Patron patron, Map<String, String> q, HttpExchange x) throws IOException {
        if (!hasAccountPage(patron)) throw ProtocolError.forbidden("Sign in to see your account.");
        Accounts.Account a = Accounts.byDid(store, patron.did());
        StringBuilder b = new StringBuilder();
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(esc(note)).append("</p>");
        if (a == null) {
            // no account yet: the owner on the library's own computer, or a reader a relay signed in (Tailscale, Cloudflare Access) who wants a password too
            b.append(patron.person() ? "<p>You are the person who keeps this library, on its own computer. Make your account, so you can sign in from any other phone or computer with your name and password.</p>"
                                     : "<p>You are signed in as <b>" + esc(patron.name()) + "</b> by the relay you came through. Choose a password too, and you can sign in with your name and it from any phone or computer.</p>")
             .append("<form method=\"post\" action=\"/account\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"make\"><label>Your name<br><input name=\"name\" size=\"40\" autocomplete=\"name\" value=\"").append(esc(patron.person() ? "" : patron.name())).append("\" autofocus></label>")
             .append("<label>Password (at least ").append(Passwords.MIN).append(" characters)<br><input type=\"password\" name=\"password\" size=\"40\" autocomplete=\"new-password\"></label>")
             .append("<label>The same again<br><input type=\"password\" name=\"again\" size=\"40\" autocomplete=\"new-password\"></label><button>Make my account</button></form>");
            if (patron.person()) b.append("<p class=\"k\">The same from a terminal: <code>researchzosho account set</code>.</p>");
            return Pages.page(store, patron, "Your account", b.toString());
        }
        boolean local = patron.person() && Pages.cookie(x, Pages.COOKIE) == null;   // the owner on the library's own computer: no password asked for a change
        b.append("<p>You sign in as <b>").append(esc(a.name())).append("</b>, with ").append(esc(a.ways())).append(".").append(a.owner() ? " You keep this library." : "").append("</p>");
        b.append("<h2>Name and password</h2><form method=\"post\" action=\"/account\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"password\">")
         .append("<label>Name<br><input name=\"name\" size=\"40\" value=\"").append(esc(a.name())).append("\" autocomplete=\"username\"></label>");
        if (a.hasPassword() && !local) b.append("<label>Current password<br><input type=\"password\" name=\"current\" size=\"40\" autocomplete=\"current-password\"></label>");
        b.append("<label>New password (leave empty to keep it)<br><input type=\"password\" name=\"password\" size=\"40\" autocomplete=\"new-password\"></label>")
         .append("<label>The same again<br><input type=\"password\" name=\"again\" size=\"40\" autocomplete=\"new-password\"></label><button>Save</button></form>");
        b.append("<h2>Passkeys</h2><p class=\"k\">A passkey signs you in with your fingerprint, your face or a security key, with no password to type. It is made for the address you are at now").append(x == null ? "" : " (" + esc(Passkeys.rpId(x)) + ")").append(" and works there; the address must be a name, not a number, and https unless it is this computer's own localhost.</p>");
        if (!a.passkeys().isEmpty()) {
            b.append("<table><tr><th>passkey</th><th>made</th><th>for</th><th></th></tr>\n");
            for (Accounts.Passkey k : a.passkeys()) b.append("<tr><td>").append(esc(k.label())).append("</td><td>").append(esc(k.made())).append("</td><td>").append(esc(k.rp())).append("</td><td><form class=\"inline\" method=\"post\" action=\"/account\"><input type=\"hidden\" name=\"op\" value=\"passkey-remove\"><input type=\"hidden\" name=\"id\" value=\"").append(esc(k.id())).append("\"><button class=\"quiet\">remove</button></form></td></tr>\n");
            b.append("</table>\n");
        }
        b.append("<p><button type=\"button\" id=\"passkey-add\" class=\"quiet\">Add a passkey</button> <span class=\"k\" id=\"passkey-why\"></span></p>");
        b.append("<h2>One-time code</h2>");
        if (a.secondStep()) {
            b.append("<p>On since ").append(esc(a.code().since())).append(": after your password, the library asks for the six digits your authenticator app shows. A passkey needs no code.</p>")
             .append("<form method=\"post\" action=\"/account\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"code-off\"><button class=\"quiet\">Turn the code off</button></form>");
        } else if ("1".equals(q.get("code"))) {
            String secret = Totp.newSecret(), uri = Totp.uri("ResearchZosho " + store.identity().name(), a.name(), secret);
            b.append("<p>Scan this with an authenticator app (Google Authenticator, Microsoft Authenticator, Aegis, 1Password, Apple's Passwords…), or type the letters into it. Then enter the code the app shows, to turn the step on.</p>")
             .append("<p>").append(QrCode.svg(uri, 5)).append("</p><p><code>").append(esc(secret)).append("</code></p>")
             .append("<form method=\"post\" action=\"/account\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"code-on\"><input type=\"hidden\" name=\"secret\" value=\"").append(esc(secret)).append("\">")
             .append("<label>The code the app shows<br><input name=\"code\" size=\"12\" inputmode=\"numeric\" autocomplete=\"one-time-code\"></label><button>Turn the code on</button></form>");
        } else {
            b.append("<p class=\"k\">A second step after the password: a six-digit code from an authenticator app on your phone. Off.</p><p><a href=\"/account?code=1\">Set up the one-time code</a></p>");
        }
        b.append("<h2>Where you are signed in</h2>");
        if (a.sessions().isEmpty()) b.append("<p class=\"k\">No browser is signed in with a password or passkey.</p>");
        else {
            b.append("<table><tr><th>browser</th><th>since</th></tr>\n");
            for (Accounts.Session s : a.sessions()) b.append("<tr><td>").append(esc(s.label().isEmpty() ? "a browser" : s.label())).append("</td><td>").append(esc(s.made())).append("</td></tr>\n");
            b.append("</table>\n<form method=\"post\" action=\"/account\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"signout-all\"><button class=\"quiet\">Sign out everywhere</button></form>");
        }
        b.append("<h2>For a program of yours</h2><p class=\"k\">An MCP host or a script signs in with a token, not a password. On the computer where the library lives: <code>").append(esc(a.owner() ? Patrons.TOKEN_HOWTO.replace("\n", "; ") : "researchzosho reader token " + a.did())).append("</code></p>");
        b.append("<script>").append(ACCOUNT_JS).append("</script>");
        return Pages.page(store, patron, "Your account", b.toString());
    }

    /** POST /account: the change asked for; the note the page shows after. */
    static String accountPost(HttpExchange x, LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        if (!hasAccountPage(patron)) throw ProtocolError.forbidden("Sign in to change your account.");
        String op = form.getOrDefault("op", "");
        Accounts.Account a = Accounts.byDid(store, patron.did());
        boolean local = patron.person() && Pages.cookie(x, Pages.COOKIE) == null;
        try {
            switch (op) {
                case "make" -> {
                    if (a != null) return "You have an account already.";
                    String pw = form.getOrDefault("password", "");
                    if (!pw.equals(form.getOrDefault("again", ""))) return "The two passwords differ. Type the same one twice.";
                    Accounts.create(store, patron.did(), form.getOrDefault("name", ""), pw);
                    return "Your account is made. From any other phone or computer, sign in with your name and password.";
                }
                case "password" -> {
                    if (a == null) return "There is no account to change.";
                    String name = form.getOrDefault("name", "").strip(), pw = form.getOrDefault("password", "");
                    if (!pw.isEmpty()) {
                        if (a.hasPassword() && !local && !Passwords.check(a.password(), form.getOrDefault("current", ""))) return "The current password is not right.";
                        if (!pw.equals(form.getOrDefault("again", ""))) return "The two passwords differ. Type the same one twice.";
                        Accounts.setPassword(store, a.did(), pw);
                    }
                    if (!name.isEmpty() && !name.equals(a.name())) Accounts.rename(store, a.did(), name);
                    return pw.isEmpty() ? (name.equals(a.name()) ? "Nothing changed." : "Your name is " + name + " now.") : "Your password is changed.";
                }
                case "passkey-remove" -> { if (a != null) Accounts.removePasskey(store, a.did(), form.getOrDefault("id", "")); return "The passkey is removed."; }
                case "code-on" -> {
                    if (a == null) return "There is no account to change.";
                    String secret = form.getOrDefault("secret", "").strip();
                    long step = secret.isEmpty() ? -1 : Totp.stepOf(secret, form.getOrDefault("code", ""), System.currentTimeMillis());
                    if (step < 0) return "That code is not the one the app shows for this secret. Scan it again and type the current code.";
                    Accounts.setCode(store, a.did(), secret, step);
                    return "The one-time code is on: after your password the library asks for it.";
                }
                case "code-off" -> { if (a != null) Accounts.clearCode(store, a.did()); return "The one-time code is off."; }
                case "signout-all" -> { if (a != null) Accounts.signOutAll(store, a.did()); if (!local) Pages.clearCookie(x); return "Every browser is signed out."; }
                default -> { return "That is not a change the account page knows."; }
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
            if (!hasAccountPage(patron)) { Pages.sendJson(x, 403, why("Sign in first.")); return; }
            Accounts.Account a = Accounts.byDid(store, patron.did());
            if (a == null) { Pages.sendJson(x, 403, why("Make your account first.")); return; }
            Pages.sendJson(x, 200, Passkeys.registerOptions(store, a, x));
        } else Pages.sendJson(x, 200, Passkeys.signInOptions(x));
    }

    /** POST /account/passkey: the browser's new credential, checked and kept. */
    static void passkeyRegister(HttpExchange x, LibraryStore store, Patrons.Patron patron) throws IOException {
        if (!hasAccountPage(patron)) { Pages.sendJson(x, 403, why("Sign in first.")); return; }
        Accounts.Account a = Accounts.byDid(store, patron.did());
        if (a == null) { Pages.sendJson(x, 403, why("Make your account first.")); return; }
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
                if (listed == null || listed.level() == Patrons.Level.deny) { Pages.sendJson(x, 403, why("This account is no longer a reader of this library.")); return; }
            }
            signedIn(x, store, a);
            Pages.sendJson(x, 200, ok());
        } catch (IOException e) { Pages.sendJson(x, 403, why(e.getMessage())); }
    }

    private static JsonNode body(HttpExchange x) throws IOException {
        String s = (String) x.getAttribute(LibrarianDaemon.BODY);
        if (s == null || s.isBlank()) throw new IOException("The browser sent nothing.");
        JsonNode n = Accounts.M.readTree(s);
        if (!n.isObject()) throw new IOException("The browser sent something that is not a passkey.");
        return n;
    }

    private static ObjectNode ok() { ObjectNode o = Accounts.M.createObjectNode(); o.put("ok", true); return o; }
    private static ObjectNode why(String w) { ObjectNode o = Accounts.M.createObjectNode(); o.put("ok", false); o.put("why", w); return o; }

    // ---- the scripts ----

    /** base64url to bytes and back, shared by both scripts. */
    private static final String B64_JS = "function u2b(s){s=s.replace(/-/g,'+').replace(/_/g,'/');while(s.length%4)s+='=';var r=atob(s),a=new Uint8Array(r.length);for(var i=0;i<r.length;i++)a[i]=r.charCodeAt(i);return a.buffer}"
            + "function b2u(b){var a=new Uint8Array(b),s='';for(var i=0;i<a.length;i++)s+=String.fromCharCode(a[i]);return btoa(s).replace(/\\+/g,'-').replace(/\\//g,'_').replace(/=+$/,'')}"
            + "function ready(btn,why){if(/^[\\d.]+$|^\\[/.test(location.hostname)){btn.disabled=true;why.textContent='Passkeys need an address with a name, not a number: on this computer open http://localhost:'+(location.port||'80')+'/ instead, or use the https address researchzosho share gives.';return false}"
            + "if(!window.isSecureContext||!window.PublicKeyCredential){btn.disabled=true;why.textContent='Passkeys need an https address (researchzosho share --tailscale gives one), or http://localhost on the library\\'s own computer.';return false}return true}";

    static final String LOGIN_JS = "(function(){var b=document.getElementById('passkey'),w=document.getElementById('passkey-why');if(!b)return;" + B64_JS
            + "if(!ready(b,w))return;b.addEventListener('click',function(){w.textContent='';fetch('/login/passkey/options',{method:'POST'}).then(function(r){return r.json()}).then(function(o){"
            + "o.challenge=u2b(o.challenge);(o.allowCredentials||[]).forEach(function(c){c.id=u2b(c.id)});return navigator.credentials.get({publicKey:o})}).then(function(c){"
            + "var r={id:c.id,rawId:b2u(c.rawId),type:c.type,response:{clientDataJSON:b2u(c.response.clientDataJSON),authenticatorData:b2u(c.response.authenticatorData),signature:b2u(c.response.signature),userHandle:c.response.userHandle?b2u(c.response.userHandle):null}};"
            + "return fetch('/login/passkey',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(r)})}).then(function(r){return r.json()}).then(function(j){if(j.ok)location.href='/';else w.textContent=j.why||'The passkey was not accepted.'})"
            + ".catch(function(e){w.textContent=(e&&e.name==='NotAllowedError')?'No passkey was used.':'The passkey could not be used: '+(e&&e.message?e.message:e)})})})();";

    static final String ACCOUNT_JS = "(function(){var b=document.getElementById('passkey-add'),w=document.getElementById('passkey-why');if(!b)return;" + B64_JS
            + "if(!ready(b,w))return;b.addEventListener('click',function(){w.textContent='';fetch('/account/passkey/options',{method:'POST'}).then(function(r){return r.json()}).then(function(o){"
            + "if(o.ok===false)throw new Error(o.why);o.challenge=u2b(o.challenge);o.user.id=u2b(o.user.id);(o.excludeCredentials||[]).forEach(function(c){c.id=u2b(c.id)});return navigator.credentials.create({publicKey:o})}).then(function(c){"
            + "var r={id:c.id,rawId:b2u(c.rawId),type:c.type,label:navigator.userAgent,response:{clientDataJSON:b2u(c.response.clientDataJSON),attestationObject:b2u(c.response.attestationObject)}};"
            + "return fetch('/account/passkey',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(r)})}).then(function(r){return r.json()}).then(function(j){if(j.ok)location.href='/account?note='+encodeURIComponent('The passkey is added.');else w.textContent=j.why||'The passkey was not kept.'})"
            + ".catch(function(e){w.textContent=(e&&e.name==='NotAllowedError')?'No passkey was made.':'The passkey could not be made: '+(e&&e.message?e.message:e)})})})();";
}
