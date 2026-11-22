package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.researchzosho.Version;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.researchzosho.librarian.Pages.esc;
import static org.researchzosho.librarian.Pages.t;
import static org.researchzosho.librarian.Pages.tc;

/**
 * The account service's own pages (0.8.0): signing in (through the identity provider, or with a name and a password), the join link
 * where an invited person chooses their password, and the account page, where a person sees the libraries they can open and opens
 * one, makes and revokes the keys of their programs, lets people into the libraries they keep, and changes their password. The same
 * acts answer the library's {@code access} call ({@link #access}), so a program has them too.
 */
final class AccountPages {
    private AccountPages() { }

    /** The addresses the service answers itself; every other address is a library's. */
    /** The page of whoever runs the service: every account's month, and the caps (0.8.0, stage 3, step 4). */
    static final String OPERATOR = "/operator";
    private static final Set<String> OWN = Set.of("/login", "/login/oidc", "/login/oidc/callback", "/logout", "/join", "/account", OPERATOR);
    static boolean own(String path) { return OWN.contains(path); }

    /** How long a sign-in, or proving it again, counts as proof that the person is at the browser (SEC 7); a test shortens it. */
    static volatile long PROVEN_MS = 5 * 60_000L;

    /** The cookie that carries a join link's code to the identity provider and back, for ten minutes. */
    static final String JOIN = "rz_join";

    static void handle(AccountService svc, HttpExchange x, AccountService.Who who, String path, String method, Map<String, String> q, Map<String, String> form) throws IOException {
        boolean post = method.equals("POST");
        switch (path) {
            case "/login" -> {
                if (who != null) { Pages.redirect(x, "/"); return; }
                if (!post) { Pages.send(x, 200, loginPage(svc, svc.noteShown(q, null))); return; }
                String name = form.getOrDefault("name", "").strip(), password = form.getOrDefault("password", "");
                if (name.isEmpty() || password.isEmpty()) { Pages.send(x, 200, loginPage(svc, t("Type your name and your password."))); return; }
                // the lock on a name's password counts the tries from one address (SEC 11): a storm from elsewhere does not lock its person out
                String from = RateLimits.ofAddress(x), tries = "svc:" + Accounts.login(name) + "|" + from;
                RateLimits.Refused over = svc.limits.full(RateLimits.Limit.SIGNIN, from);   // failed sign-ins from this address (stage 4): the password is not looked at
                if (over != null) {
                    AccountStore.Account named = svc.store.accountByLogin(name);
                    if (named != null) svc.limits.noted(named, AccountActs.By.person("", "password", x), over.limit());
                    RateLimits.retryAfter(x, over);
                    Pages.send(x, 429, loginPage(svc, over.said()));
                    return;
                }
                if (Accounts.tooMany(tries)) { Pages.send(x, 429, loginPage(svc, t("Too many tries. Wait {0} minutes and try again.", Accounts.WAIT_MINUTES))); return; }
                AccountStore.Account a = svc.store.accountByLogin(name);
                boolean open = a != null && (a.active() || a.state().equals("deleting"));
                if (a == null || !open || !a.hasPassword() || !Passwords.check(a.passwordHash(), password)) {
                    Accounts.failed(tries);
                    svc.limits.mark(RateLimits.Limit.SIGNIN, from);
                    AccountActs.audit(svc.store, AccountActs.By.person("", "password", x), "signin_failed", a == null ? "" : a.id(), "", null);   // an unknown name is not written down
                    boolean right = a != null && a.hasPassword() && Passwords.check(a.passwordHash(), password);
                    Pages.send(x, 403, loginPage(svc, right && a.state().equals("suspended") ? AccountActs.suspendedSaid()
                            : t("That name and password do not match. Whoever runs this service can send you a new link if you have forgotten yours.")));
                    return;
                }
                Accounts.cleared(tries);
                signedIn(svc, x, a, "password", "");
                AccountActs.audit(svc.store, AccountActs.By.person(a.id(), "password", x), "signin", a.id(), "", null);
                Pages.redirect(x, !a.active() ? "/account" : backToInvitation(svc, x));
            }
            case "/login/oidc" -> {
                if (!Oidc.configured()) { Pages.redirect(x, "/login"); return; }
                boolean again = "1".equals(q.get("again"));   // to prove it is them again (SEC 7): a new sign-in at the provider, not one it remembers
                if (again && (who == null || !who.browser())) { Pages.redirect(x, "/login"); return; }
                try {
                    Oidc.Started began = Oidc.begin(x, again ? Map.of("max_age", "0", "prompt", "login") : Map.of());
                    // the sign-in is this browser's (SEC 4): its state's hash rides in a cookie the provider's answer must come back with
                    Oidc.bindToBrowser(x, began.state());
                    if (again) svc.store.pendingPut(AGAIN + began.state(), who.account().id() + "\t" + System.currentTimeMillis());
                    Pages.redirect(x, began.url());
                } catch (IOException e) { Pages.send(x, 503, loginPage(svc, e.getMessage())); }
            }
            case "/login/oidc/callback" -> {
                String state = q.getOrDefault("state", "");
                if (!Oidc.fromThisBrowser(x, state)) {
                    refused(svc, x, who, t("This sign-in was not begun in this browser. Sign in from here."));   // nothing of the provider's answer is read
                    return;
                }
                String again = svc.store.pendingTake(AGAIN + state);
                try {
                    Oidc.Proven p = Oidc.proven(q);
                    if (again != null) { provedAgain(svc, x, who, p, again); return; }
                    AccountStore.Invitation inv = svc.store.invitation(Pages.cookie(x, JOIN));   // an invitation opened in this browser: the library is opened after
                    AccountStore.Account a = fromProvider(svc, p, Pages.cookie(x, JOIN), RateLimits.ofAddress(x), x);
                    x.getResponseHeaders().add("Set-Cookie", JOIN + "=; Path=/; HttpOnly; SameSite=Lax" + (Pages.secure(x) ? "; Secure" : "") + "; Max-Age=0");
                    String secret = signedIn(svc, x, a, "provider", p.idToken());
                    AccountStore.Library joined = inv == null ? null : svc.store.library(inv.library());
                    if (joined != null && svc.store.level(joined, a.id()) != null) svc.store.sessionLibrary(secret, joined.id());   // and the library it let them into opens
                    AccountActs.audit(svc.store, AccountActs.By.person(a.id(), "provider", x), "signin", a.id(), "", null);
                    Pages.redirect(x, a.active() ? "/" : "/account");
                } catch (RateLimits.Refusal e) { RateLimits.retryAfter(x, e.refused); Pages.send(x, 429, loginPage(svc, e.getMessage())); }
                catch (IOException e) { refused(svc, x, who, e.getMessage()); }   // on the sign-in page, so the code leaves the address bar
            }
            case "/logout" -> {
                if (!post) { Pages.send(x, 200, shell(svc, who, t("Sign out"), "<form method=\"post\" action=\"/logout\"><button>" + t("Sign out") + "</button></form>")); return; }   // a link from elsewhere signs nobody out
                AccountStore.Session s = who != null && who.session() != null ? svc.store.session(who.session()) : null;
                if (s != null) svc.store.endSession(who.session());
                x.getResponseHeaders().add("Set-Cookie", AccountService.COOKIE + "=; Path=/; HttpOnly; SameSite=Lax" + (Pages.secure(x) ? "; Secure" : "") + "; Max-Age=0");
                if (s != null && s.via().equals("provider") && Oidc.configured()) {   // signed in through the provider: signed out there too, where it says how
                    String there = Oidc.endSession(s.idToken(), svc.address() + "/login");
                    if (there == null) { Pages.redirect(x, svc.noted("/login", t("You may still be signed in to {0} on this computer.", Oidc.name()), null)); return; }
                    // a page that goes on by itself: the answer to a form may not lead to another site (the pages' form-action 'self'),
                    // and a browser holds a redirect to the provider's sign-out to that (found in the run with Keycloak)
                    Pages.send(x, 200, onwards(svc, t("Signed out"), t("You are signed out here, and taken to {0} to sign out there too.", Oidc.name()), there, t("Sign out at {0}", esc(Oidc.name()))));
                    return;
                }
                Pages.redirect(x, "/login");
            }
            case "/join" -> join(svc, x, who, post, q, form);
            case "/account" -> {
                if (who == null || !who.browser()) { Pages.redirect(x, "/login"); return; }
                if (!post) { Pages.send(x, 200, accountPage(svc, who, svc.noteShown(q, who.session()), "")); return; }
                Done done = accountPost(svc, x, who, form);
                if (!done.page().isEmpty()) Pages.send(x, 200, done.page());
                else if (done.retryAfter() > 0) { x.getResponseHeaders().set("Retry-After", String.valueOf(done.retryAfter())); Pages.send(x, 429, accountPage(svc, who, done.note(), "")); }
                else if (!done.shown().isEmpty()) Pages.send(x, 200, accountPage(svc, who, done.note(), done.shown()));
                else Pages.redirect(x, done.to().isEmpty() ? svc.noted("/account", done.note(), who.session()) : done.to());
            }
            case OPERATOR -> {
                if (who == null || !who.browser()) { Pages.redirect(x, "/login"); return; }
                if (!who.account().admin() || !who.account().active()) { Pages.send(x, 403, shell(svc, who, t("Not yours to open"), "<p class=\"err\">" + t("This page is for whoever runs this service.") + "</p><p><a href=\"/account\">" + t("Your account") + "</a></p>")); return; }
                if (!post) { Pages.send(x, 200, operatorPage(svc, who, svc.noteShown(q, who.session()), q)); return; }
                // an act the operator confirms: they show it is them first, as on the account page, when the last proof is older than five
                // minutes (round F, S17: a page of a library in the same browser could post here in their name)
                if ("yes".equals(form.get("confirm")) && !proven(svc, who)) {
                    String typed = form.get("prove_password");
                    x.getResponseHeaders().set("Cache-Control", "no-store");
                    if (typed == null) { Pages.send(x, 200, provePage(svc, who, form, null, OPERATOR)); return; }
                    RateLimits.Refused over = svc.limits.take(RateLimits.Limit.CHANGES, RateLimits.ofAccount(who.account().id()));
                    if (over != null) { Pages.send(x, 429, provePage(svc, who, form, over.said(), OPERATOR)); return; }
                    if (!who.account().hasPassword() || !Passwords.check(who.account().passwordHash(), typed)) { Pages.send(x, 200, provePage(svc, who, form, t("That is not your password."), OPERATOR)); return; }
                    svc.store.sessionProven(who.session());
                }
                OperatorPages.Result r = OperatorPages.post(svc, who, x, form);   // an act on an account or a library (stage 4): asked on a page of its own first
                if (r != null && !r.page().isEmpty()) { Pages.send(x, 200, r.page()); return; }
                String note = r != null ? r.note() : operatorPost(svc, who, form), anchor = r != null ? r.anchor() : "caps";
                Pages.redirect(x, svc.noted(OPERATOR, note, who.session()) + (anchor == null || anchor.isEmpty() ? "" : "#" + anchor));
            }
            default -> Pages.redirect(x, "/account");
        }
    }

    /** This browser signed in to the account: a session, its secret in the cookie, in the account's own library until it opens another. */
    static String signedIn(AccountService svc, HttpExchange x, AccountStore.Account a) throws IOException { return signedIn(svc, x, a, "", ""); }

    /** The same, saying how it signed in ({@code via}), with the provider's identity token when it came through one (to sign out there too). */
    static String signedIn(AccountService svc, HttpExchange x, AccountStore.Account a, String via, String idToken) throws IOException {
        int days = Accounts.sessionDays();
        String secret = svc.store.newSession(a.id(), "", SignIn.browser(x.getRequestHeaders().getFirst("User-Agent")), days, via, idToken);
        x.getResponseHeaders().add("Set-Cookie", AccountService.COOKIE + "=" + secret + "; Path=/; HttpOnly; SameSite=Lax" + (Pages.secure(x) ? "; Secure" : "") + "; Max-Age=" + days * 86400L);
        return secret;
    }

    /** Where a sign-in goes on to: the invitation this browser opened and signed in from, while it stands; else the library. */
    static String backToInvitation(AccountService svc, HttpExchange x) throws IOException {
        String code = Pages.cookie(x, JOIN);
        return code != null && svc.store.invitation(code) != null ? "/join?code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) : "/";
    }

    /** The cookie that holds the hash of a sign-in's state, so only the browser that began it finishes it (SEC 4). */
    static final String SIGNIN = Oidc.SIGNIN;
    /** Where a sign-in begun to prove it is them again is written down, by its state (SEC 7). */
    private static final String AGAIN = "again:";


    /** A sign-in through the provider refused: back on the sign-in page (or the account page, when this browser is signed in) with why. */
    static void refused(AccountService svc, HttpExchange x, AccountService.Who who, String said) throws IOException {
        if (who != null && who.browser()) Pages.redirect(x, svc.noted("/account", said, who.session()));
        else Pages.redirect(x, svc.noted("/login", said, null));
    }

    /**
     * The provider's answer to "sign in again" (SEC 7): the same browser, the same account, and a sign-in the provider made just now
     * (its auth_time after the request); then this browser's session has proved it is its person, for {@link #PROVEN_MS}.
     */
    static void provedAgain(AccountService svc, HttpExchange x, AccountService.Who who, Oidc.Proven p, String again) throws IOException {
        String[] a = again.split("\t", 2);
        long began = a.length == 2 ? Long.parseLong(a[1]) : 0;
        if (who == null || !who.browser() || !who.account().id().equals(a[0])) { refused(svc, x, who, t("Sign in again from the browser where you were asked to.")); return; }
        if (!a[0].equals(svc.store.boundAccount(p.issuer(), p.claims().path("sub").asText("")))) { refused(svc, x, who, t("That sign-in at {0} is not this account's.", Oidc.name())); return; }
        long at = p.claims().path("auth_time").asLong(0) * 1000;
        if (at < began - 60_000) { refused(svc, x, who, t("The identity provider did not ask you to sign in again, so nothing is proved. Try again.")); return; }
        svc.store.sessionProven(who.session());
        Pages.redirect(x, svc.noted("/account", t("It is proved that it is you. Do it again now: for five minutes it is done without asking."), who.session()));
    }

    /** Whether this browser proved it is its person within {@link #PROVEN_MS}: a sign-in, its password typed again, or a new sign-in at its provider. */
    static boolean proven(AccountService svc, AccountService.Who who) throws IOException {
        AccountStore.Session s = who.session() == null ? null : svc.store.session(who.session());
        return s != null && System.currentTimeMillis() - s.provenAt() < PROVEN_MS;
    }

    /** A form field that holds a key or a password, whatever the form: never written into a page. */
    static final Pattern SECRET_FIELD = Pattern.compile("(?i)secret|password|passphrase|token|api_?key");
    /** The proof page's note of the fields it asked for again: one left empty there is never taken for an empty one meant (an own key's empty field takes the key away). */
    static final String ASKED_AGAIN = "asked_again";

    /** The page that asks the person to show it is them before an act that needs it (SEC 7), with the act's own fields to do it after. */
    static String provePage(AccountService svc, AccountService.Who who, Map<String, String> form, String note) throws IOException { return provePage(svc, who, form, note, "/account"); }

    /** The same for an act on another page ({@code action}): the operator's (round F, S17). */
    static String provePage(AccountService svc, AccountService.Who who, Map<String, String> form, String note, String action) throws IOException {
        AccountStore.Account me = who.account();
        StringBuilder b = new StringBuilder();
        if (note != null) b.append("<p class=\"err\">").append(esc(note)).append("</p>");
        b.append("<p>").append(t("Before this, show it is you. It is asked again after five minutes, so that a page that is not yours cannot do it in your name.")).append("</p>");
        if (me.hasPassword()) {
            b.append("<form method=\"post\" action=\"").append(esc(action)).append("\" class=\"stack\">");
            // the act's own fields go with the form, except a key or a password: those never come back to the browser in a page, so they are
            // asked for again beside the password (a person's own key was in the page's HTML, 2026-11-18)
            List<String> again = new ArrayList<>();
            for (String f : form.getOrDefault(ASKED_AGAIN, "").split(",")) if (SECRET_FIELD.matcher(f).find() && form.containsKey(f.strip())) again.add(f.strip());
            for (Map.Entry<String, String> e : form.entrySet()) {
                if (e.getKey().equals("prove_password") || e.getKey().equals(ASKED_AGAIN)) continue;
                if (SECRET_FIELD.matcher(e.getKey()).find()) { if (!e.getValue().isBlank() && !again.contains(e.getKey())) again.add(e.getKey()); continue; }
                b.append("<input type=\"hidden\" name=\"").append(esc(e.getKey())).append("\" value=\"").append(esc(e.getValue())).append("\">");
            }
            if (!again.isEmpty()) b.append("<input type=\"hidden\" name=\"").append(ASKED_AGAIN).append("\" value=\"").append(esc(String.join(",", again))).append("\">");
            for (String name : again) b.append("<label>").append(t("Your key, again")).append("<br><input type=\"password\" name=\"").append(esc(name)).append("\" size=\"40\" autocomplete=\"off\" required></label>");
            b.append("<label>").append(t("Your password")).append("<br><input type=\"password\" name=\"prove_password\" size=\"40\" autocomplete=\"current-password\" autofocus></label><button>").append(t("Go on")).append("</button></form>");
        }
        if (Oidc.configured() && svc.store.identityIssuer(me.id()) != null)
            b.append("<p>").append(me.hasPassword() ? t("Or sign in again with {0}, and then do it again.", esc(Oidc.name())) : t("Sign in again with {0}, and then do it again.", esc(Oidc.name())))
             .append(" <a class=\"btn\" href=\"/login/oidc?again=1\">").append(t("Sign in again with {0}", esc(Oidc.name()))).append("</a></p>");
        b.append("<p><a href=\"/account\">").append(t("Back to your account")).append("</a></p>");
        return shell(svc, who, t("Show it is you"), b.toString());
    }

    /**
     * The account the provider's answer is: the one bound to this sign-in before; else the one a join link in this browser is for; else
     * the one with that verified mail address; else, where the service lets people sign up, a new one with a library of its own.
     */
    static AccountStore.Account fromProvider(AccountService svc, Oidc.Proven p, String joinCode, String from) throws IOException { return fromProvider(svc, p, joinCode, from, null); }

    /** The same, from the request {@code x} came by (null in a test): an account made is written down with its address. */
    static AccountStore.Account fromProvider(AccountService svc, Oidc.Proven p, String joinCode, String from, HttpExchange x) throws IOException {
        JsonNode claims = p.claims();
        String sub = claims.path("sub").asText("").strip();
        if (sub.isEmpty()) throw new IOException(t("The identity provider did not say who is signing in."));
        String mail = claims.path("email").asText("").strip().toLowerCase(Locale.ROOT), name = claims.path("name").asText("").strip();
        boolean verified = claims.path("email_verified").asBoolean(false) && !mail.isEmpty();
        AccountStore.Account a = svc.store.account(svc.store.boundAccount(p.issuer(), sub));
        AccountStore.Invitation inv = svc.store.invitation(joinCode);   // an invitation into a library this browser opened: whoever signs in is let in
        if (a == null && inv == null && joinCode != null && !joinCode.isBlank()) a = svc.store.account(svc.store.invited(joinCode, true));
        if (a == null && verified) a = svc.store.accountByVerifiedMail(mail);   // an address someone else typed never finds an account
        if (a == null) {
            String signup = AccountService.signup();
            boolean may = inv != null || verified && (signup.equals("anyone") || signup.equals("domain") && Oidc.allowed(mail.substring(mail.indexOf('@') + 1)));   // an invitation is an invitation to an account too
            if (!may && inv == null && !signup.equals("invited") && !verified)   // said by its cause (the Keycloak test): the provider did not vouch for the address
                throw new IOException(t("The identity provider did not vouch for your mail address, so no account is made for you here. Ask whoever runs this service."));
            if (!may && inv == null && signup.equals("domain"))
                throw new IOException(t("Mail addresses at {0} do not make an account here. Ask whoever runs this service for one.", mail.substring(mail.indexOf('@') + 1)));
            if (!may) throw new IOException(t("No account here is yours yet. Whoever runs this service makes one for you and sends you a link."));
            RateLimits.Refused over = svc.limits.full(RateLimits.Limit.SIGNUP, from);   // sign-ups from this address (stage 4)
            if (over != null) throw new RateLimits.Refusal(over);
            String n = !name.isEmpty() && svc.store.accountByLogin(name) == null ? name : verified && svc.store.accountByLogin(mail) == null ? mail : freeLogin(svc.store, name.isEmpty() ? "Reader" : name);   // a name another account signs in with: the mail address, else a number, tells them apart
            a = svc.newAccount(n, verified ? mail : "", false, inv != null ? inv.by() : "signed up", inv == null || AccountService.signupGivesALibrary(mail, verified)).account();
            svc.limits.mark(RateLimits.Limit.SIGNUP, from);
            AccountActs.audit(svc.store, AccountActs.By.person(a.id(), "provider", x), "account_made", a.id(), "", null);
        }
        if (a.state().equals("suspended")) throw new IOException(AccountActs.suspendedSaid());
        if (!a.active() && !a.state().equals("deleting")) throw new IOException(t("This account is closed. Ask whoever runs this service."));
        svc.store.bind(p.issuer(), sub, a.id(), mail);
        if (verified && mail.equals(a.mail())) svc.store.mailVerified(a.id());   // the provider vouches for the account's own address
        if (inv != null && a.active()) letInBy(svc, inv, joinCode, a, AccountActs.By.person(a.id(), "provider", null));
        return a;
    }

    /** A sign-in name nobody has: {@code base}, else with a number after it. */
    static String freeLogin(AccountStore store, String base) throws IOException {
        String n = base;
        for (int i = 2; store.accountByLogin(n) != null; i++) n = base + " " + i;
        return n;
    }

    /**
     * The one who opened an invitation let into its library, at its level, once: nothing when they are in it already or keep it, when
     * the library or its keeper is not open, or when the invitation was taken a moment before. Whether they were let in.
     */
    static boolean letInBy(AccountService svc, AccountStore.Invitation inv, String code, AccountStore.Account a, AccountActs.By by) throws IOException {
        AccountStore store = svc.store;
        AccountStore.Library lib = store.library(inv.library());
        if (lib == null || !lib.active() || !AccountActs.keeperOpen(store, lib) || store.level(lib, a.id()) != null) return false;
        if (!store.takeInvitation(code)) return false;
        store.setMember(lib.id(), a.id(), inv.level(), inv.by());
        AccountActs.audit(store, by, "member_let_in", a.id(), lib.id(), AccountActs.detail().put("level", inv.level()));
        return true;
    }

    // ---- the pages ----

    /**
     * A page that sends the browser on to {@code to} by itself, with a link to it: where a form's answer leads to another site (the
     * provider's sign-in or sign-out), which the pages' form-action 'self' does not let a redirect do.
     */
    static String onwards(AccountService svc, String title, String said, String to, String button) {
        return shell(svc, null, title, "<p>" + esc(said) + "</p><p><a class=\"btn\" href=\"" + esc(to) + "\">" + button + "</a></p>")
                .replace("<title>", "<meta http-equiv=\"refresh\" content=\"0;url=" + esc(to) + "\"><title>");
    }

    static String shell(AccountService svc, AccountService.Who who, String title, String body) {
        String whoSaid = who == null ? "<a href=\"/login\">" + t("sign in") + "</a>" : esc(who.account().name()) + " · <a href=\"/account\">" + t("account") + "</a> · <form class=\"inline\" method=\"post\" action=\"/logout\"><button class=\"quiet\">" + t("sign out") + "</button></form>";
        return "<!doctype html><html lang=\"" + Chrome.now().code + "\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(title + " · ResearchZosho") + "</title><link rel=\"icon\" href=\"/favicon.ico\" type=\"image/png\">" + Pages.script("look-boot") + "<style>" + Pages.CSS + "</style></head><body>"
                + "<header class=\"top\"><a class=\"brand\" href=\"/\">ResearchZosho</a><span class=\"ver\">" + esc(Version.number() == null ? "dev" : Version.number()) + "</span><span class=\"who\">" + whoSaid + "</span></header>"
                + "<main><h1>" + esc(title) + "</h1>" + body + "</main><footer>ResearchZosho " + esc(Version.string()) + " · <a href=\"https://researchzosho.org\">researchzosho.org</a></footer></body></html>";
    }

    /** What a browser is shown when a limit stops a page (stage 4): the sentence, and the way on: the same page again, or back. */
    static String waitPage(AccountService svc, AccountService.Who who, String said, String again) {
        return shell(svc, who, t("Wait a moment"), "<p class=\"err\">" + esc(said) + "</p><p>"
                + (again != null ? "<a class=\"btn\" href=\"" + esc(again) + "\">" + t("Try again") + "</a>" : who == null ? "<a href=\"/login\">" + t("Sign in") + "</a>" : "<a href=\"/\">" + t("Back to the library") + "</a>") + "</p>");
    }

    static String loginPage(AccountService svc, String note) {
        StringBuilder b = new StringBuilder();
        if (note != null && !note.isBlank()) b.append("<p class=\"err\">").append(esc(note)).append("</p>");
        if (Oidc.configured()) b.append("<p><a class=\"btn\" href=\"/login/oidc\">").append(t("Sign in with {0}", esc(Oidc.name()))).append("</a></p>");
        b.append("<form method=\"post\" action=\"/login\" class=\"stack\"><label>").append(t("Name")).append("<br><input name=\"name\" size=\"40\" autocomplete=\"username\" autofocus></label>")
         .append("<label>").append(t("Password")).append("<br><input type=\"password\" name=\"password\" size=\"40\" autocomplete=\"current-password\"></label><button>").append(t("Sign in")).append("</button></form>");
        String signup = AccountService.signup();
        b.append("<p class=\"k\">").append(signup.equals("anyone") && Oidc.configured() ? t("No account yet? Sign in with {0}: an account is made for you, with a library of your own.", esc(Oidc.name()))
                : signup.equals("domain") && Oidc.configured() ? t("No account yet? People of this service's own mail domains sign in with {0}, and an account is made for them, with a library of their own.", esc(Oidc.name()))
                : t("An account here is made for you by whoever runs this service. They send you a link, and there you choose your password.")).append("</p>");
        return shell(svc, null, t("Sign in"), b.toString());
    }

    /** GET /join?code=…: the invited person chooses a password, or signs in through the provider; POST: the password kept, the browser signed in. */
    private static void join(AccountService svc, HttpExchange x, AccountService.Who who, boolean post, Map<String, String> q, Map<String, String> form) throws IOException {
        RateLimits.Refused over = svc.limits.take(RateLimits.Limit.JOIN, RateLimits.ofAddress(x));   // join links opened from this address (stage 4), before a code is looked at
        if (over != null) { RateLimits.retryAfter(x, over); Pages.send(x, 429, waitPage(svc, null, over.said(), post ? null : x.getRequestURI().toString())); return; }
        String code = post ? form.getOrDefault("code", "") : q.getOrDefault("code", "");
        if (post && (form.getOrDefault("op", "").equals("provider") || form.getOrDefault("op", "").equals("signin")) && (svc.store.invitation(code) != null || svc.store.invited(code, false) != null)) {
            // the person chose, on this page, to sign in with the provider or with a password: the code rides in a cookie for ten minutes
            // so that sign-in comes back to it (SEC 12: a page merely opened from a link someone sent binds nothing)
            x.getResponseHeaders().add("Set-Cookie", JOIN + "=" + code + "; Path=/; HttpOnly; SameSite=Lax" + (Pages.secure(x) ? "; Secure" : "") + "; Max-Age=600");
            if (form.get("op").equals("provider")) Pages.send(x, 200, onwards(svc, t("Sign in with {0}", Oidc.name()), t("You are taken to {0} to sign in.", Oidc.name()), "/login/oidc", t("Sign in with {0}", esc(Oidc.name()))));   // on by itself, as the sign-out is
            else Pages.redirect(x, "/login");
            return;
        }
        AccountStore.Invitation inv = svc.store.invitation(code);
        if (inv != null) { invitation(svc, x, who != null && who.browser() ? who : null, post, code, inv, form); return; }
        AccountStore.Account a = svc.store.account(svc.store.invited(code, false));
        if (a == null || !a.active()) {
            Pages.send(x, 403, shell(svc, null, t("This link does not open"), "<p class=\"err\">" + t("A join link works once, for {0} days. This one was used, has expired, or was replaced by a newer one. Ask whoever sent it for a new one.", AccountService.JOIN_DAYS) + "</p><p class=\"k\"><a href=\"/login\">" + t("Sign in another way") + "</a></p>"));
            return;
        }
        if (!post) { Pages.send(x, 200, joinPage(svc, code, a.name(), null)); return; }
        String password = form.getOrDefault("password", ""), why = Passwords.why(password);
        if (why != null) { Pages.send(x, 200, joinPage(svc, code, a.name(), why)); return; }
        if (!password.equals(form.getOrDefault("again", ""))) { Pages.send(x, 200, joinPage(svc, code, a.name(), t("The two passwords differ. Type the same one twice."))); return; }
        if (svc.store.invited(code, true) == null) { Pages.redirect(x, "/join?code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)); return; }
        svc.store.setPassword(a.id(), Passwords.hash(password));
        svc.store.endSessions(a.id());   // a new password from a join link signs out every browser of the account, as a change of password does (round F)
        signedIn(svc, x, a, "password", "");
        AccountActs.audit(svc.store, AccountActs.By.person(a.id(), "join", x), "password_changed", a.id(), "", null);
        AccountActs.audit(svc.store, AccountActs.By.person(a.id(), "join", x), "signin", a.id(), "", null);
        Pages.redirect(x, "/");
    }

    /** What a link that stands no more says: an invitation used, run out or withdrawn. */
    private static void linkGone(AccountService svc, HttpExchange x) throws IOException {
        Pages.send(x, 403, shell(svc, null, t("This link does not open"), "<p class=\"err\">" + t("A link works once, for {0} days. This one was used, has run out, or was withdrawn. Ask whoever sent it for a new one.", AccountService.JOIN_DAYS)
                + "</p><p class=\"k\"><a href=\"/login\">" + t("Sign in another way") + "</a></p>"));
    }

    /**
     * An invitation into a library, opened (0.8.0 review, round A). Signed in: one button lets this account in. Not signed in: sign in
     * (and come back here), or make an account of one's own choosing (a name and a password; no mail), or sign in through the provider;
     * and be let in. The keeper opening it, or one let in already, is let in nowhere and the invitation stands. It works once.
     */
    private static void invitation(AccountService svc, HttpExchange x, AccountService.Who who, boolean post, String code, AccountStore.Invitation inv, Map<String, String> form) throws IOException {
        AccountStore store = svc.store;
        AccountStore.Library lib = store.library(inv.library());
        AccountStore.Account keeper = lib == null ? null : store.account(lib.owner());
        if (lib == null || !lib.active() || keeper == null || !keeper.active()) {
            Pages.send(x, 403, shell(svc, who, t("This link does not open"), "<p class=\"err\">" + t("This library is not open now, so the link lets nobody in.") + "</p>"));
            return;
        }
        String title = t("{0} invites you to {1}", keeper.name(), lib.title());
        String may = "<p>" + (inv.level().equals("write") ? t("You will read it and write to it.") : t("You will read it.")) + "</p>";
        if (who != null) {
            AccountStore.Account me = who.account();
            String level = store.level(lib, me.id());
            if (level != null) {   // its keeper, or let in already: nobody is let in, and the link stands for the one it is for
                Pages.send(x, 200, shell(svc, who, title, "<p class=\"note\">" + (level.equals(Statement.KEEPER) ? t("You keep {0}. This link is for the person you send it to, and it still stands.", esc(lib.title()))
                        : t("You are one of the people of {0} already.", esc(lib.title()))) + "</p><p><a href=\"/\">" + t("Back to the library") + "</a></p>"));
                return;
            }
            if (!post) {
                Pages.send(x, 200, shell(svc, who, title, may + "<form method=\"post\" action=\"/join\"><input type=\"hidden\" name=\"code\" value=\"" + esc(code) + "\"><input type=\"hidden\" name=\"op\" value=\"take\"><button>"
                        + tc("Join", "a button that takes an invitation into a library") + "</button></form>"));
                return;
            }
            if (!me.active() || !letInBy(svc, inv, code, me, AccountActs.By.person(me.id(), "join", x))) { linkGone(svc, x); return; }
            if (who.session() != null) store.sessionLibrary(who.session(), lib.id());   // and the library opens
            Pages.redirect(x, "/");
            return;
        }
        if (!post || !"new".equals(form.get("op"))) { Pages.send(x, 200, invitationPage(svc, title, may, code, inv.label(), null)); return; }
        String name = form.getOrDefault("name", "").strip().replaceAll("\\s+", " "), password = form.getOrDefault("password", ""), why = Passwords.why(password);
        String again = null;
        if (name.isEmpty()) again = t("Type the name you will sign in with.");
        else if (name.length() > 120) again = t("Keep the name under 120 characters.");
        else if (store.accountByLogin(name) != null) again = t("Someone signs in here as {0} already. Choose another name.", name);
        else if (why != null) again = why;
        else if (!password.equals(form.getOrDefault("again", ""))) again = t("The two passwords differ. Type the same one twice.");
        if (again != null) { Pages.send(x, 200, invitationPage(svc, title, may, code, name, again)); return; }
        if (!store.takeInvitation(code)) { linkGone(svc, x); return; }   // taken by another a moment before
        AccountService.Made made;
        try { made = svc.newAccount(name, "", false, inv.by(), AccountService.signupGivesALibrary("", false)); }   // no mail: nobody verified one; a library of its own only where anyone may sign up
        catch (IOException e) { store.untakeInvitation(code); Pages.send(x, 200, invitationPage(svc, title, may, code, name, e.getMessage())); return; }
        AccountStore.Account a = made.account();
        store.setPassword(a.id(), Passwords.hash(password));
        store.setMember(lib.id(), a.id(), inv.level(), inv.by());
        AccountActs.By by = AccountActs.By.person(a.id(), "join", x);
        AccountActs.audit(store, by, "account_made", a.id(), "", null);
        AccountActs.audit(store, by, "member_let_in", a.id(), lib.id(), AccountActs.detail().put("level", inv.level()));
        String secret = signedIn(svc, x, a, "password", "");
        store.sessionLibrary(secret, lib.id());
        AccountActs.audit(store, by, "signin", a.id(), "", null);
        Pages.redirect(x, "/");
    }

    /** An invitation's page for someone not signed in: sign in and come back, or make an account here, or sign in through the provider. */
    private static String invitationPage(AccountService svc, String title, String may, String code, String name, String note) {
        return shell(svc, null, title, (note == null ? "" : "<p class=\"err\">" + esc(note) + "</p>") + may
                + "<p>" + t("Have an account here? {0}, and you come back to this page.", joinButton(code, "signin", t("Sign in"))) + "</p>"
                + "<p>" + t("New here? Choose the name you will sign in with, and a password.") + "</p>"
                + "<form method=\"post\" action=\"/join\" class=\"stack\"><input type=\"hidden\" name=\"code\" value=\"" + esc(code) + "\"><input type=\"hidden\" name=\"op\" value=\"new\">"
                + "<label>" + t("Your name") + "<br><input name=\"name\" size=\"40\" value=\"" + esc(name) + "\" autocomplete=\"username\"></label>"
                + "<label>" + t("Password (at least {0} characters)", Passwords.MIN) + "<br><input type=\"password\" name=\"password\" size=\"40\" autocomplete=\"new-password\"></label>"
                + "<label>" + t("The same again") + "<br><input type=\"password\" name=\"again\" size=\"40\" autocomplete=\"new-password\"></label><button>" + t("Make my account") + "</button></form>"
                + (Oidc.configured() ? "<p class=\"k\">" + t("Or sign in with {0}:", esc(Oidc.name())) + " " + joinButton(code, "provider", t("Sign in with {0}", esc(Oidc.name()))) + "</p>" : ""));
    }

    /** A button on a join or invitation page that signs in from it (with a password, or the provider) and comes back to it. */
    private static String joinButton(String code, String op, String label) {
        return "<form class=\"inline\" method=\"post\" action=\"/join\"><input type=\"hidden\" name=\"code\" value=\"" + esc(code) + "\"><input type=\"hidden\" name=\"op\" value=\"" + op + "\"><button class=\"btn\">" + label + "</button></form>";
    }

    private static String joinPage(AccountService svc, String code, String name, String note) {
        return shell(svc, null, t("Welcome, {0}", name), (note == null ? "" : "<p class=\"err\">" + esc(note) + "</p>")
                + "<p>" + t("An account was made for you here. Choose a password: with your name and it you sign in from any phone or computer.") + "</p>"
                + "<form method=\"post\" action=\"/join\" class=\"stack\"><input type=\"hidden\" name=\"code\" value=\"" + esc(code) + "\">"
                + "<label>" + t("Your name") + "<br><input value=\"" + esc(name) + "\" disabled></label>"
                + "<label>" + t("Password (at least {0} characters)", Passwords.MIN) + "<br><input type=\"password\" name=\"password\" size=\"40\" autocomplete=\"new-password\" autofocus></label>"
                + "<label>" + t("The same again") + "<br><input type=\"password\" name=\"again\" size=\"40\" autocomplete=\"new-password\"></label><button>" + t("Sign in") + "</button></form>"
                + (Oidc.configured() ? "<p class=\"k\">" + t("Or sign in with {0}, and that becomes how you sign in here:", esc(Oidc.name())) + " " + joinButton(code, "provider", t("Sign in with {0}", esc(Oidc.name()))) + "</p>" : ""));
    }

    private static String levelSaid(String level) {
        return level.equals(Statement.KEEPER) ? t("You keep it.") : level.equals(Statement.WRITE) ? t("You read it and write to it.") : t("You read it.");
    }

    static String accountPage(AccountService svc, AccountService.Who who, String note, String shown) throws IOException {
        AccountStore store = svc.store;
        AccountStore.Account me = who.account();
        StringBuilder b = new StringBuilder();
        if (note != null && !note.isBlank()) b.append("<p class=\"note\">").append(esc(note)).append("</p>");
        if (!me.active()) {   // being deleted: the day, and the cancel (stage 4)
            b.append("<p class=\"notice\">").append(esc(AccountActs.notActiveSaid(store, me))).append("</p>");
            // a deletion its person asked for is theirs to call off; one whoever runs the service made is theirs alone (the owner, 2026-11-18)
            if (AccountActs.deletedByAnother(store, me)) b.append("<p>").append(t("Whoever runs this service deleted your account; only they can keep it.")).append("</p>");
            else b.append("<form method=\"post\" action=\"/account\"><input type=\"hidden\" name=\"op\" value=\"cancel-deletion\"><button>").append(t("Keep my account")).append("</button></form>");
            return shell(svc, who, t("Your account"), b.toString());
        }
        b.append(shown);
        b.append("<p>").append(t("You sign in as {0}.", "<b>" + esc(me.name()) + "</b>"));
        if (me.admin()) b.append(" <a href=\"").append(OPERATOR).append("\">").append(t("Every account's month, and the caps")).append("</a>");
        b.append("</p>");
        month(b, store, me);
        ownKeys(b, store, me);
        List<AccountStore.Library> libs = store.librariesOf(me.id());
        AccountStore.Library in = svc.libraryOf(who);
        b.append("<h2>").append(t("Your libraries")).append("</h2>");
        if (libs.isEmpty()) b.append("<p class=\"k\">").append(t("You have no library yet. Whoever runs this service makes one for you.")).append("</p>");
        else {
            b.append("<table>\n");
            for (AccountStore.Library l : libs) {
                boolean here = in != null && in.id().equals(l.id());
                b.append("<tr><td><b>").append(esc(l.title())).append("</b></td><td>").append(levelSaid(store.level(l, me.id()))).append("</td><td>")
                 .append(here ? t("It is open in this browser.") + " <a href=\"/\">" + t("Go to the library") + "</a>"
                         : "<form class=\"inline\" method=\"post\" action=\"/account\"><input type=\"hidden\" name=\"op\" value=\"open\"><input type=\"hidden\" name=\"library\" value=\"" + esc(l.id()) + "\"><button class=\"quiet\">" + t("Open it in this browser") + "</button></form>")
                 .append(l.owner().equals(me.id()) ? " · <a href=\"" + AccountService.ARCHIVE + "?library=" + esc(l.id()) + "\" download>" + t("Take it home") + "</a>"   // its keeper's alone (stage 4)
                         + " <form class=\"inline\" method=\"post\" action=\"/account\"><input type=\"hidden\" name=\"op\" value=\"library-let-go\"><input type=\"hidden\" name=\"library\" value=\"" + esc(l.id()) + "\"><button class=\"quiet\">" + t("Let it go") + "</button></form>" : "")
                 .append("</td></tr>\n");
            }
            b.append("</table>\n");
            if (!store.librariesKeptBy(me.id()).isEmpty()) b.append("<p class=\"k\">").append(t("Take it home downloads a library you keep as one file, with its shelves, its reports and its settings. ResearchZosho on your own computer opens it with {0}.", "<code>researchzosho take &lt;file&gt;</code>")).append("</p>");
        }
        makeLibrary(b, store, me);
        b.append("<h2>").append(t("For a program of yours")).append("</h2><p class=\"k\">").append(t("A program (an MCP host, a script, an agent) signs in with a key. A key is for one library, and reads it or reads and writes it. It is shown once when it is made; the service keeps only its hash. The program sends it as {0} to {1}.", "<code>Authorization: Bearer rz_…</code>", "<code>" + esc(svc.address()) + "</code>")).append("</p>");
        List<AccountStore.Key> keys = store.keys(me.id());
        if (!keys.isEmpty()) {   // a table whose rows stack on a phone, each cell under its heading's word (the pages' own "stack"): nothing to scroll sideways
            b.append("<table class=\"stack\"><thead><tr><th>").append(t("key")).append("</th><th>").append(t("for")).append("</th><th>").append(t("library")).append("</th><th>").append(t("made")).append("</th><th>").append(t("last used")).append("</th><th></th></tr></thead><tbody>\n");
            for (AccountStore.Key k : keys) {
                AccountStore.Library l = store.library(k.library());
                b.append("<tr><td data-l=\"").append(esc(t("key"))).append("\"><code>").append(esc(k.prefix())).append("…</code> ").append(k.reads() ? t("reads") : t("reads and writes")).append("</td><td data-l=\"").append(esc(t("for"))).append("\">").append(esc(k.label()))
                 .append("</td><td data-l=\"").append(esc(t("library"))).append("\">").append(esc(l == null ? "" : l.title())).append("</td><td data-l=\"").append(esc(t("made"))).append("\">")
                 .append(esc(Chrome.now().day(Instant.ofEpochMilli(k.madeAt()).atZone(ZoneId.systemDefault()).toLocalDate().toString()))).append("</td><td data-l=\"").append(esc(t("last used"))).append("\">").append(k.usedDay().isEmpty() ? t("never") : esc(Chrome.now().day(k.usedDay())))
                 .append("</td><td><form class=\"inline\" method=\"post\" action=\"/account\"><input type=\"hidden\" name=\"op\" value=\"key-revoke\"><input type=\"hidden\" name=\"id\" value=\"").append(esc(k.id())).append("\"><button class=\"quiet\">").append(t("revoke")).append("</button></form></td></tr>\n");
            }
            b.append("</tbody></table>\n");
        }
        if (!libs.isEmpty()) {
            b.append("<form method=\"post\" action=\"/account\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"key-new\"><label>").append(t("What the key is for")).append("<br><input name=\"label\" size=\"40\"></label><label>").append(t("Library")).append("<br><select name=\"library\">");
            for (AccountStore.Library l : libs) b.append("<option value=\"").append(esc(l.id())).append("\">").append(esc(l.title())).append("</option>");
            b.append("</select></label><label>").append(t("The key")).append("<br><select name=\"level\"><option value=\"read\">").append(t("reads")).append("</option><option value=\"write\">").append(t("reads and writes")).append("</option></select></label><button>").append(t("Make a key")).append("</button></form>");
        }
        for (AccountStore.Library l : store.librariesKeptBy(me.id())) {
            b.append("<h2>").append(t("The people of {0}", esc(l.title()))).append("</h2>");
            List<AccountStore.Member> members = store.members(l.id());
            if (members.isEmpty()) b.append("<p class=\"k\">").append(t("Nobody but you opens this library.")).append("</p>");
            else {
                b.append("<table class=\"stack\"><tbody>\n");   // the rows stack on a phone: the name, what they may do, the two buttons
                for (AccountStore.Member m : members) {
                    AccountStore.Account a = store.account(m.account());
                    boolean writes = m.level().equals("write");
                    b.append("<tr><td><b>").append(esc(a == null ? m.account() : a.name())).append("</b></td><td>").append(writes ? t("reads and writes") : t("reads")).append("</td><td>")
                     .append(memberForm(l.id(), m.account(), "member-level", writes ? "read" : "write", writes ? t("let them read only") : t("let them write too"))).append(" ")
                     .append(memberForm(l.id(), m.account(), "member-remove", "", t("remove"))).append("</td></tr>\n");
                }
                b.append("</tbody></table>\n");
            }
            List<AccountStore.Invitation> open = store.invitations(l.id());
            if (!open.isEmpty()) {   // the links sent and not taken yet, each withdrawn with a button
                b.append("<p class=\"k\">").append(t("Links not taken yet")).append("</p><table class=\"stack\"><tbody>\n");
                for (AccountStore.Invitation i : open) {
                    b.append("<tr><td><b>").append(esc(i.label())).append("</b></td><td>").append(i.level().equals("write") ? t("reads and writes") : t("reads")).append("</td><td>")
                     .append(t("works until {0}", esc(Chrome.now().day(Instant.ofEpochMilli(i.expiresAt()).atZone(ZoneId.systemDefault()).toLocalDate().toString())))).append(" ")
                     .append("<form class=\"inline\" method=\"post\" action=\"/account\"><input type=\"hidden\" name=\"op\" value=\"invite-withdraw\"><input type=\"hidden\" name=\"library\" value=\"").append(esc(l.id()))
                     .append("\"><input type=\"hidden\" name=\"id\" value=\"").append(esc(i.id())).append("\"><button class=\"quiet\">").append(tc("Withdraw", "a button that withdraws an invitation into a library")).append("</button></form></td></tr>\n");
                }
                b.append("</tbody></table>\n");
            }
            b.append("<form method=\"post\" action=\"/account\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"member-let-in\"><input type=\"hidden\" name=\"library\" value=\"").append(esc(l.id())).append("\"><label>").append(t("Let someone in: a name for them, to know the link by")).append("<br><input name=\"name\" size=\"40\"></label>")
             .append("<label>").append(t("What they may do")).append("<br><select name=\"level\"><option value=\"read\">").append(t("read")).append("</option><option value=\"write\">").append(t("read and write")).append("</option></select></label><button>").append(t("Let them in")).append("</button></form>")
             .append("<p class=\"k\">").append(t("You get a link to send them. Whoever opens it is let in, once, within {0} days: someone with an account here signs in, and someone new makes one.", AccountService.JOIN_DAYS)).append("</p>");
        }
        b.append("<h2>").append(t("Password")).append("</h2><form method=\"post\" action=\"/account\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"password\">");
        if (me.hasPassword()) b.append("<label>").append(t("Current password")).append("<br><input type=\"password\" name=\"current\" size=\"40\" autocomplete=\"current-password\"></label>");
        b.append("<label>").append(t("New password (at least {0} characters)", Passwords.MIN)).append("<br><input type=\"password\" name=\"password\" size=\"40\" autocomplete=\"new-password\"></label>")
         .append("<label>").append(t("The same again")).append("<br><input type=\"password\" name=\"again\" size=\"40\" autocomplete=\"new-password\"></label><button>").append(t("Save")).append("</button></form>");
        b.append("<h2>").append(t("Where you are signed in")).append("</h2><p>").append(Chrome.now().n(store.sessions(me.id()), "One browser is signed in to your account.", "{0} browsers are signed in to your account.")).append("</p>")
         .append("<form method=\"post\" action=\"/account\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"signout-all\"><button class=\"quiet\">").append(t("Sign out everywhere")).append("</button></form>");
        recentActivity(b, store, me);
        deleteSection(b, store, me);
        return shell(svc, who, t("Your account"), b.toString());
    }

    // ---- a person's own libraries (0.8.0, stage 4) ----

    /** "Make a library": a title, within the libraries an account may keep; or the sentence that says it keeps as many as it may. */
    static void makeLibrary(StringBuilder b, AccountStore store, AccountStore.Account me) throws IOException {
        int kept = store.librariesKeptBy(me.id()).size();
        Map<String, Long> cap = Caps.of(store, me.id());
        long most = cap.get(Caps.LIBRARIES), storage = cap.get(Caps.STORAGE_MB);
        b.append("<h2>").append(t("Make a library")).append("</h2>");
        if (most >= 0 && kept >= most) { b.append("<p class=\"k\">").append(tooManyLibraries(most)).append("</p>"); return; }
        b.append("<form method=\"post\" action=\"/account\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"library-make\"><label>").append(t("Its title")).append("<br><input name=\"title\" size=\"40\" maxlength=\"120\"></label><button>").append(t("Make a library")).append("</button></form>");
        b.append("<p class=\"k\">").append(most < 0 ? t("You keep {0} libraries.", kept) : t("You keep {0} of the {1} libraries an account may keep here.", kept, most))
         .append(storage < 0 ? "" : " " + t("What they hold together counts against your {0} of storage.", Caps.size(storage))).append("</p>");
    }

    static String tooManyLibraries(long most) { return t("You keep as many libraries as an account may keep here ({0}). Let one go, or ask whoever runs this service for more.", most); }

    /** A library made by its keeper (stage 4): within the libraries an account keeps and its storage; written to the audit log. */
    static Done libraryMade(AccountService svc, AccountStore.Account me, String title, AccountActs.By by) throws IOException {
        AccountStore store = svc.store;
        String tt = title == null ? "" : title.strip();
        if (tt.isEmpty()) return Done.note(t("Give the new library a title."));
        if (tt.length() > 120) tt = tt.substring(0, 120);
        Map<String, Long> cap = Caps.of(store, me.id());
        long most = cap.get(Caps.LIBRARIES), storage = cap.get(Caps.STORAGE_MB);
        if (most >= 0 && store.librariesKeptBy(me.id()).size() >= most) return Done.note(tooManyLibraries(most));
        if (storage >= 0 && store.storedBytes(me.id()) >= storage * 1_000_000L) return Done.note(CapsSpending.storageFull(storage));
        AccountStore.Library l = AccountService.newLibrary(store, AccountService.root(), me, tt);
        AccountActs.audit(store, by, "library_made", me.id(), l.id(), null);
        return Done.note(t("The library {0} is made. Open it from your libraries.", l.title()));
    }

    /** A library its keeper lets go (stage 4): asked on a page of its own first; then out of service, set aside for its days of grace. */
    static Done letGo(AccountService svc, HttpExchange x, AccountService.Who who, AccountStore.Library l, boolean confirmed, AccountActs.By by) throws IOException {
        String until = Chrome.now().dayTime(Instant.ofEpochMilli(System.currentTimeMillis() + LibraryPlaces.GRACE_MS).atZone(ZoneId.systemDefault()).toLocalDate().toString());
        int people = svc.store.members(l.id()).size();
        if (!confirmed) {
            String body = "<p>" + t("{0} will go out of service at once: you, and the people you let in, will no longer open it. It will be kept aside until {1}, and then removed with its copy; until then whoever runs this service can bring it back.", "<b>" + esc(l.title()) + "</b>", esc(until)) + "</p>"
                    + (people > 0 ? "<p class=\"k\">" + Chrome.now().n(people, "One person is let into it.", "{0} people are let into it.") + "</p>" : "")
                    + "<form method=\"post\" action=\"/account\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"library-let-go\"><input type=\"hidden\" name=\"library\" value=\"" + esc(l.id()) + "\"><input type=\"hidden\" name=\"confirm\" value=\"yes\"><button class=\"danger\">" + t("Let {0} go", esc(l.title())) + "</button></form> <a href=\"/account\">" + tc("Keep it", "a button that keeps an account or a library as it is") + "</a>";
            return Done.page(shell(svc, who, t("Let {0} go?", l.title()), body));
        }
        AccountsCli.places(svc.store, l, "delete", null, false, new PrintStream(OutputStream.nullOutputStream()));
        AccountActs.audit(svc.store, by, "library_deleted", l.owner(), l.id(), null);
        return Done.note(t("{0} is let go. It is kept aside until {1}; whoever runs this service can bring it back until then.", l.title(), until));
    }

    // ---- this month, and the person's own keys (0.8.0, stage 3, step 4) ----

    /** A cap as a person reads it on a page: dollars, a size, a count, or no cap. */
    static String capSaid(String name, long value) {
        if (value < 0) return t("no cap");
        return name.equals(Caps.STORAGE_MB) ? Caps.size(value) : Caps.said(name, value);
    }

    /** A bar of how much of a cap is used; nothing where there is no cap. */
    private static String bar(long used, long cap) {
        if (cap <= 0) return "";
        long pct = Math.min(100, Math.round(used * 100.0 / cap));
        return "<div class=\"meter\" role=\"img\" aria-label=\"" + pct + "%\"><i style=\"width:" + pct + "%\"" + (pct >= 100 ? " class=\"full\"" : "") + "></i></div>";
    }

    /** One row of this month: what it is, what was used, the cap, what is left. */
    private static void monthRow(StringBuilder b, String what, String used, String cap, String left, String bar) {
        b.append("<tr><td><b>").append(what).append("</b>").append(bar).append("</td>").append(cell(t("used"), used)).append(cell(t("cap"), cap)).append(cell(t("left"), left)).append("</tr>\n");
    }

    /** A cell with its heading's word for a phone, where the rows stack; an empty one says nothing there. */
    private static String cell(String heading, String value) {
        return value.isEmpty() ? "<td></td>" : "<td data-l=\"" + esc(heading) + "\">" + value + "</td>";
    }

    /** One row of an account's month: what it is, used, its cap and what is left as a person reads them, and the numbers behind them. */
    record MonthRow(String kind, String what, String used, String cap, String left, long usedValue, long capValue, String unit) { }

    /** An account's month as every surface gives it (0.8.0, stage 3): the account page, the service's usage call, a library's spend. */
    record Month(String month, boolean caps, List<MonthRow> rows, long estimated, long calls, String startsAgain, String jobs) { }

    /** This month of an account, by kind, against each cap, with what is left. */
    static Month monthOf(AccountStore store, String account) throws IOException {
        String month = AccountStore.month(System.currentTimeMillis());
        long dollars = 0, own = 0, searches = 0, fetches = 0, speech = 0, helper = 0, estimated = 0, calls = 0;
        for (AccountStore.MonthTotal t : store.month(account, month)) {
            if (t.payer().equals("operator")) dollars += t.micros(); else own += t.micros();
            if (t.kind().equals("search") && t.payer().equals("operator")) searches += t.quantity();
            if (t.kind().equals("fetch")) fetches += t.quantity();
            if (t.kind().equals("speech")) speech += t.quantity();
            if (t.kind().equals("helper")) helper += t.quantity();
            if (t.kind().equals("model")) { estimated += t.estimated(); calls += t.quantity(); }
        }
        long storedMb = (store.storedBytes(account) + 999_999) / 1_000_000;
        boolean caps = Uses.builtIn();
        Map<String, Long> cap = Caps.of(store, account);
        long dc = caps ? cap.get(Caps.MONTH_DOLLARS) : -1, sc = caps ? cap.get(Caps.SEARCHES) : -1, fc = caps ? cap.get(Caps.FETCHES) : -1, mc = caps ? cap.get(Caps.STORAGE_MB) : -1;
        List<MonthRow> rows = new ArrayList<>();
        rows.add(new MonthRow("dollars", t("The service's model and searches"), Caps.dollars(dollars), dc >= 0 ? Caps.dollars(dc) : caps ? t("no cap") : "", dc >= 0 ? Caps.dollars(Math.max(0, dc - dollars)) : "", dollars, dc, "micros"));
        rows.add(new MonthRow("searches", t("Searches"), String.valueOf(searches), sc >= 0 ? String.valueOf(sc) : caps ? t("no cap") : "", sc >= 0 ? String.valueOf(Math.max(0, sc - searches)) : "", searches, sc, "count"));
        rows.add(new MonthRow("fetches", t("Pages fetched"), String.valueOf(fetches), fc >= 0 ? String.valueOf(fc) : caps ? t("no cap") : "", fc >= 0 ? String.valueOf(Math.max(0, fc - fetches)) : "", fetches, fc, "count"));
        rows.add(new MonthRow("storage", t("Stored in your libraries"), Caps.size(storedMb), mc >= 0 ? Caps.size(mc) : caps ? t("no cap") : "", mc >= 0 ? Caps.size(Math.max(0, mc - storedMb)) : "", storedMb, mc, "MB"));
        if (speech > 0) rows.add(new MonthRow("speech", t("Speech turned into text"), t("{0} min", String.valueOf((speech + 59) / 60)), "", "", speech, -1, "seconds"));
        if (helper > 0) rows.add(new MonthRow("helpers", t("The helpers' time"), t("{0} min", String.valueOf((helper + 59) / 60)), "", "", helper, -1, "seconds"));
        if (own > 0) rows.add(new MonthRow("own", t("On your own keys"), Caps.dollars(own), t("yours"), "", own, -1, "micros"));
        String jobs;
        if (caps) {   // each limit a sentence of its own, said only when it is set
            StringBuilder j = new StringBuilder(t("The month starts again on {0}.", CapsSpending.nextMonth()));
            if (cap.get(Caps.JOB_DOLLARS) >= 0) j.append(' ').append(t("A job may cost at most {0}.", Caps.dollars(cap.get(Caps.JOB_DOLLARS))));
            if (cap.get(Caps.JOB_MINUTES) >= 0) j.append(' ').append(t("A job may take at most {0} minutes.", String.valueOf(cap.get(Caps.JOB_MINUTES))));
            if (cap.get(Caps.JOBS_AT_ONCE) >= 0) j.append(' ').append(t("Jobs going at once: at most {0}.", String.valueOf(cap.get(Caps.JOBS_AT_ONCE))));
            jobs = j.toString();
        } else jobs = t("Whoever runs this service decides what you may spend; this is what you used.");
        return new Month(month, caps, rows, estimated, calls, CapsSpending.nextMonth(), jobs);
    }

    /** The line about counts worked out from their size; "" when there are none. */
    static String estimatedSaid(Month m) {
        if (m.estimated() <= 0) return "";
        return m.estimated() == 1 ? t("One of this month's {0} model calls was counted from its size, because the model server did not say what it read and wrote.", String.valueOf(m.calls()))
                : t("{0} of this month's {1} model calls were counted from their size, because the model server did not say what they read and wrote.", String.valueOf(m.estimated()), String.valueOf(m.calls()));
    }

    /** The month in words, a line a row, as a program shows it to a person. */
    static String monthText(Month m) {
        StringBuilder b = new StringBuilder();
        for (MonthRow r : m.rows()) b.append(r.left().isEmpty() ? t("{0}: {1}", r.what(), r.used()) : t("{0}: {1} of {2}, {3} left", r.what(), r.used(), r.cap(), r.left())).append('\n');
        b.append(m.jobs()).append('\n');
        String e = estimatedSaid(m);
        if (!e.isEmpty()) b.append(e).append('\n');
        return b.toString();
    }

    /** The month as the service's usage call and a library's spend give it to a program. */
    static ObjectNode monthJson(AccountStore store, AccountStore.Account a) throws IOException {
        Month m = monthOf(store, a.id());
        ObjectNode o = Statement.M.createObjectNode();
        o.put("account", a.id()); o.put("name", a.name()); o.put("month", m.month()); o.put("caps_from", m.caps() ? "built-in" : "plug-in");
        ArrayNode rows = o.putArray("rows");
        for (MonthRow r : m.rows()) rows.addObject().put("kind", r.kind()).put("what", r.what()).put("used", r.used()).put("cap", r.cap()).put("left", r.left())
                .put("used_value", r.usedValue()).put("cap_value", r.capValue()).put("unit", r.unit());
        o.put("model_calls", m.calls()); o.put("estimated_calls", m.estimated()); o.put("starts_again", m.startsAgain());
        ArrayNode keys = o.putArray("own_keys");
        for (AccountStore.OwnKey k : store.ownKeys(a.id())) keys.addObject().put("host", k.host()).put("kind", k.kind()).put("readable", !OwnKeys.lost(k));
        o.put("text", monthText(m));
        MonthRow first = m.rows().get(0);
        o.put("summary", first.left().isEmpty() ? t("{0}: {1}", first.what(), first.used()) : t("{0}: {1} of {2}, {3} left", first.what(), first.used(), first.cap(), first.left()));
        return o;
    }

    /** This month of an account, by kind, against each cap, with what is left: what the account page shows. */
    static void month(StringBuilder b, AccountStore store, AccountStore.Account me) throws IOException {
        Month m = monthOf(store, me.id());
        b.append("<h2>").append(t("This month")).append("</h2>");
        b.append("<table class=\"stack month\"><thead><tr><th></th><th>").append(t("used")).append("</th><th>").append(m.caps() ? t("cap") : "").append("</th><th>").append(m.caps() ? t("left") : "").append("</th></tr></thead><tbody>\n");
        for (MonthRow r : m.rows()) monthRow(b, r.what(), r.used(), r.cap(), r.left(), r.capValue() >= 0 ? bar(r.usedValue(), r.capValue()) : "");
        b.append("</tbody></table>\n");
        b.append("<p class=\"k\">").append(m.jobs()).append("</p>");
        String e = estimatedSaid(m);
        if (!e.isEmpty()) b.append("<p class=\"k\">").append(e).append("</p>");
    }

    /** The person's own keys: one for each model API and search place of the service, kept sealed, never shown again. */
    static void ownKeys(StringBuilder b, AccountStore store, AccountStore.Account me) throws IOException {
        List<OwnKeys.Host> hosts = OwnKeys.hosts();
        boolean any = !store.ownKeys(me.id()).isEmpty();   // folded away until the person keeps a key of their own
        b.append("<details class=\"fold\"").append(any ? " open" : "").append("><summary><h2>").append(t("Use your own keys (optional)")).append("</h2></summary><p class=\"k\">").append(t("With a key of your own for one of these, your work there goes on your key: it is counted as yours and not against this service's dollars. The service keeps it sealed. It is not shown again, never given to a library, and never written to a log.")).append("</p>");
        b.append("<table class=\"stack\"><tbody>\n");
        for (OwnKeys.Host h : hosts) {
            AccountStore.OwnKey row = store.ownKey(me.id(), h.host());
            String state = row == null ? t("not set") : OwnKeys.lost(row) ? "<span class=\"err\">" + t("It can no longer be read: set it again.") + "</span>" : setOn(row.madeAt());
            b.append("<tr><td><b>").append(esc(h.said())).append("</b> <span class=\"k\">").append(h.kind().equals(OwnKeys.MODEL) ? t("model") : t("search")).append("</span></td><td>").append(state).append("</td><td>")
             .append("<form class=\"inline\" method=\"post\" action=\"/account\"><input type=\"hidden\" name=\"op\" value=\"own-key\"><input type=\"hidden\" name=\"host\" value=\"").append(esc(h.host())).append("\">")
             .append("<input type=\"password\" name=\"secret\" size=\"24\" autocomplete=\"off\" aria-label=\"").append(esc(t("your key for {0}", h.said()))).append("\" placeholder=\"").append(esc(t("your key"))).append("\"> <button class=\"quiet\">").append(tc("Keep", "a button that saves the thing beside it")).append("</button></form>");
            if (row != null) b.append(" <form class=\"inline\" method=\"post\" action=\"/account\"><input type=\"hidden\" name=\"op\" value=\"own-key\"><input type=\"hidden\" name=\"host\" value=\"").append(esc(h.host())).append("\"><input type=\"hidden\" name=\"secret\" value=\"\"><button class=\"quiet\">").append(t("take it away")).append("</button></form>");
            b.append("</td></tr>\n");
        }
        b.append("</tbody></table></details>\n");
    }

    /** When a person's key was kept, as they read it: today, yesterday, or on a day. */
    static String setOn(long madeAt) {
        LocalDate d = Instant.ofEpochMilli(madeAt).atZone(ZoneId.systemDefault()).toLocalDate(), today = LocalDate.now(ZoneId.systemDefault());
        if (d.equals(today)) return t("set today");
        if (d.equals(today.minusDays(1))) return t("set yesterday");
        return t("set on {0}", esc(Chrome.now().dayTime(d.toString())));
    }

    // ---- the operator's page ----

    static String operatorPage(AccountService svc, AccountService.Who who, String note, Map<String, String> q) throws IOException {
        AccountStore store = svc.store;
        String month = AccountStore.month(System.currentTimeMillis());
        StringBuilder b = new StringBuilder();
        if (note != null && !note.isBlank()) b.append("<p class=\"note\">").append(esc(note)).append("</p>");
        String plugin = PluginSpending.url();
        if (plugin != null) b.append("<p class=\"notice\">").append(t("A spending plug-in answers for every account, at {0}. What was spent and waits to be taken by it: {1}.", "<code>" + esc(plugin) + "</code>", String.valueOf(store.outboxSize()))).append("</p>");
        List<AccountStore.Outgoing> parked = store.outboxParked();
        if (!parked.isEmpty()) {   // what the plug-in refused again and again, set aside so the rest go on (BILL 12)
            b.append("<div class=\"notice\"><p>").append(Chrome.now().n(parked.size(), "The spending plug-in refused one entry again and again; it is set aside, and what came after it was sent.", "The spending plug-in refused {0} entries again and again; they are set aside, and what came after them was sent.")).append("</p><table class=\"stack\"><tbody>\n");
            for (AccountStore.Outgoing o : parked)
                b.append("<tr><td><code>").append(esc(o.id())).append("</code></td><td>").append(esc(o.last())).append("</td><td><form class=\"inline\" method=\"post\" action=\"").append(OPERATOR).append("\"><input type=\"hidden\" name=\"op\" value=\"outbox-retry\"><input type=\"hidden\" name=\"seq\" value=\"").append(o.seq()).append("\"><button class=\"quiet\">").append(t("Send it again")).append("</button></form></td></tr>\n");
            b.append("</tbody></table></div>");
        }
        for (AccountStore.Unpriced u : store.unpriced()) {   // a hosted model called with no prices in its entry: counted at an estimate until it has them (BILL 9)
            if (Spend.entryOf("https://" + u.origin(), u.model()) != null && Spend.entryOf("https://" + u.origin(), u.model()).prices() != null) { store.unpricedGone(u.origin(), u.model()); continue; }
            b.append("<p class=\"notice\">").append(t("Calls to {0} for the model {1} have no prices in the model entries, so they are counted at ${2} and ${3} a million tokens read and written, marked as estimates. Give the entry its prices: researchzosho models set <entry> --prices <in>/<out>.",
                    "<code>" + esc(u.origin()) + "</code>", "<code>" + esc(u.model().isEmpty() ? "?" : u.model()) + "</code>", NodeGates.UNPRICED[0], NodeGates.UNPRICED[1])).append("</p>");
        }
        b.append(OperatorPages.nav());
        b.append("<h2 id=\"accounts\">").append(t("Every account's month")).append("</h2>");
        b.append("<table class=\"stack accounts\"><thead><tr><th>").append(t("account")).append("</th><th>").append(t("the service's dollars")).append("</th><th>").append(t("searches")).append("</th><th>").append(t("pages fetched")).append("</th><th>")
         .append(t("stored")).append("</th><th>").append(t("on their own keys")).append("</th></tr></thead><tbody>\n");
        for (AccountStore.Account a : store.accounts()) {
            long dollars = 0, own = 0, searches = 0, fetches = 0;
            for (AccountStore.MonthTotal t : store.month(a.id(), month)) {
                if (t.payer().equals("operator")) dollars += t.micros(); else own += t.micros();
                if (t.kind().equals("search") && t.payer().equals("operator")) searches += t.quantity();
                if (t.kind().equals("fetch")) fetches += t.quantity();
            }
            Map<String, Long> cap = Caps.of(store, a.id());
            boolean ownCaps = false;
            for (String name : Caps.DEFAULTS.keySet()) ownCaps |= store.cap(a.id(), name) != null;
            b.append("<tr class=\"has-acts\"><td data-l=\"").append(esc(t("account"))).append("\"><b>").append(esc(a.name())).append("</b>").append(ownCaps ? " <span class=\"k\">" + t("caps of its own") + "</span>" : "").append(OperatorPages.accountState(store, a)).append(bar(dollars, plugin == null ? cap.get(Caps.MONTH_DOLLARS) : -1))
             .append("</td><td data-l=\"").append(esc(t("the service's dollars"))).append("\">").append(Caps.dollars(dollars)).append(plugin == null ? " / " + capSaid(Caps.MONTH_DOLLARS, cap.get(Caps.MONTH_DOLLARS)) : "")
             .append("</td><td data-l=\"").append(esc(t("searches"))).append("\">").append(searches).append(plugin == null ? " / " + capSaid(Caps.SEARCHES, cap.get(Caps.SEARCHES)) : "")
             .append("</td><td data-l=\"").append(esc(t("pages fetched"))).append("\">").append(fetches).append(plugin == null ? " / " + capSaid(Caps.FETCHES, cap.get(Caps.FETCHES)) : "")
             .append("</td><td data-l=\"").append(esc(t("stored"))).append("\"><span class=\"nw\">").append(Caps.size((store.storedBytes(a.id()) + 999_999) / 1_000_000)).append(plugin == null ? " / " + capSaid(Caps.STORAGE_MB, cap.get(Caps.STORAGE_MB)) : "").append("</span>")
             .append("</td><td data-l=\"").append(esc(t("on their own keys"))).append("\">").append(Caps.dollars(own)).append("</td></tr>\n")
             .append("<tr class=\"acts\"><td colspan=\"6\">").append(OperatorPages.accountButtons(a, who)).append("</td></tr>\n");   // what may be done with it, on a line of its own under its month
        }
        b.append("</tbody></table>\n");
        OperatorPages.makeAccount(b);
        OperatorPages.libraries(b, svc);
        OperatorPages.nodes(b, svc);
        b.append("<h2 id=\"caps\">").append(t("The caps")).append("</h2><p class=\"k\">").append(plugin != null ? t("A spending plug-in answers, so these caps are not what decides; they are kept for when it is taken away.") + " " : "")
         .append(t("The service's caps hold for every account without one of its own. Write off for no cap; dollars as 5, 5.00 or 5,00.")).append("</p>");
        b.append("<form method=\"post\" action=\"").append(OPERATOR).append("\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"caps\"><div class=\"caps\">");
        Map<String, Long> service = Caps.of(store, null);
        for (String name : Caps.DEFAULTS.keySet()) {
            Long set = store.cap("*", name);
            b.append("<label>").append(esc(capName(name))).append("<br><input name=\"cap.").append(name).append("\" size=\"10\" value=\"").append(esc(set == null ? "" : capWritten(name, set))).append("\" placeholder=\"").append(esc(capWritten(name, service.get(name)))).append("\"></label>");
        }
        b.append("</div><button>").append(t("Save the service's caps")).append("</button></form>");
        b.append("<form method=\"post\" action=\"").append(OPERATOR).append("\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"account-cap\"><label>").append(t("One account's own cap")).append("<br><select name=\"account\">");
        for (AccountStore.Account a : store.accounts()) b.append("<option value=\"").append(esc(a.id())).append("\">").append(esc(a.name())).append("</option>");
        b.append("</select></label><label>").append(t("Cap")).append("<br><select name=\"name\">");
        for (String name : Caps.DEFAULTS.keySet()) b.append("<option value=\"").append(name).append("\">").append(esc(capName(name))).append("</option>");
        b.append("</select></label><label>").append(t("Value")).append("<br><input name=\"value\" size=\"10\"></label><button>").append(t("Set")).append("</button></form>")
         .append("<p class=\"k\">").append(t("An empty value, or the word default, puts the account back on the service's cap.")).append("</p>");
        OperatorPages.audit(b, svc, q);
        return shell(svc, who, t("Every account"), b.toString());
    }

    /** A cap's name as a person reads it. */
    static String capName(String name) {
        return switch (name) {
            case Caps.MONTH_DOLLARS -> t("dollars a month"); case Caps.SEARCHES -> t("searches a month"); case Caps.FETCHES -> t("pages fetched a month");
            case Caps.JOBS_AT_ONCE -> t("jobs at once"); case Caps.JOB_MINUTES -> t("minutes a job"); case Caps.JOB_DOLLARS -> t("dollars a job"); case Caps.STORAGE_MB -> t("megabytes stored");
            case Caps.LIBRARIES -> t("libraries an account keeps");
            default -> name;
        };
    }

    /** A cap as it is written in the form: off, dollars as 5.00, a number. */
    static String capWritten(String name, long value) {
        if (value < 0) return "off";
        return name.equals(Caps.MONTH_DOLLARS) || name.equals(Caps.JOB_DOLLARS) ? String.format(Locale.ROOT, "%.2f", value / 1_000_000.0) : String.valueOf(value);
    }

    static String operatorPost(AccountService svc, AccountService.Who who, Map<String, String> form) throws IOException {
        AccountStore store = svc.store;
        String op = form.getOrDefault("op", "");
        try {
            if (op.equals("caps")) {
                int changed = 0;
                for (String name : Caps.DEFAULTS.keySet()) {
                    String v = form.getOrDefault("cap." + name, "").strip();
                    Long was = store.cap("*", name);
                    if (v.isEmpty()) { if (was != null) { store.unsetCap("*", name); changed++; AccountActs.audit(store, AccountActs.By.of(who, null), "cap_changed", "*", "", AccountActs.detail().put("name", name).put("value", "default")); } continue; }
                    long n = Caps.parse(name, v);
                    if (was == null || was != n) { store.setCap("*", name, n); changed++; AccountActs.audit(store, AccountActs.By.of(who, null), "cap_changed", "*", "", AccountActs.detail().put("name", name).put("value", n)); }
                }
                return Chrome.now().n(changed, "One cap of the service's is changed.", "{0} caps of the service's are changed.");
            }
            if (op.equals("outbox-retry")) {
                long seq;
                try { seq = Long.parseLong(form.getOrDefault("seq", "")); } catch (NumberFormatException e) { return t("That is not an entry of the outbox."); }
                return store.outboxRetry(seq) ? t("It is sent again, in its place.") : t("That entry is not set aside.");
            }
            if (op.equals("account-cap")) {
                AccountStore.Account a = store.account(form.getOrDefault("account", ""));
                String name = form.getOrDefault("name", ""), v = form.getOrDefault("value", "").strip();
                if (a == null || !Caps.known(name)) return t("That is not an account and a cap of this service's.");
                if (v.isEmpty() || v.equalsIgnoreCase("default")) { store.unsetCap(a.id(), name); AccountActs.audit(store, AccountActs.By.of(who, null), "cap_changed", a.id(), "", AccountActs.detail().put("name", name).put("value", "default")); return t("{0} has the service's {1} again.", a.name(), capName(name)); }
                store.setCap(a.id(), name, Caps.parse(name, v));
                AccountActs.audit(store, AccountActs.By.of(who, null), "cap_changed", a.id(), "", AccountActs.detail().put("name", name).put("value", Caps.parse(name, v)));
                return t("{0}'s own {1}: {2}.", a.name(), capName(name), capSaid(name, Caps.parse(name, v)));
            }
            return t("That is not a change this page knows.");
        } catch (NumberFormatException e) { return t("A cap is a number, dollars as 5, 5.00 or 5,00, or off."); }
    }

    // ---- what was done with the account, and its own deletion (0.8.0, stage 4) ----

    /** An act of the audit log as a person reads it. */
    static String actSaid(String act) {
        return switch (act) {
            case "signin" -> t("signed in"); case "signin_failed" -> t("a sign-in that failed"); case "signout_everywhere" -> t("signed out everywhere");
            case "password_changed" -> t("password changed"); case "key_made" -> t("a key made for a program"); case "key_revoked" -> t("a program's key revoked");
            case "own_key_set" -> t("your own key kept"); case "own_key_removed" -> t("your own key taken away");
            case "member_let_in" -> t("let into a library"); case "member_level" -> t("what may be done in a library changed"); case "member_removed" -> t("taken out of a library");
            case "cap_changed" -> t("a cap changed"); case "account_made" -> t("account made"); case "account_suspended" -> t("account suspended");
            case "account_unsuspended" -> t("account let back"); case "deletion_asked" -> t("deletion asked for"); case "deletion_cancelled" -> t("deletion called off");
            case "library_made" -> t("a library made"); case "library_handed_over" -> t("a library handed over"); case "library_deleted" -> t("a library deleted");
            case "library_moved" -> t("a library moved"); case "library_taken_in" -> t("a library taken in"); case "library_released" -> t("a library released");
            case "library_restored" -> t("a library brought back from its copy");
            case "limit_reached" -> t("too many requests: some were refused");
            case "library_archived" -> t("a library packed to take home");
            case "join_link_made" -> t("a new join link made");
            case "account_removed" -> t("account removed");
            case "member_invited" -> t("someone invited into a library"); case "invitation_withdrawn" -> t("an invitation withdrawn");
            default -> act;
        };
    }

    /**
     * What an entry's detail says, in words, to go after its act (stage 4): what may be done (to read only, to read and write), a cap
     * and its new value, a key's place, the days of grace or at once, where a library went or came from, the machine, the archive, the
     * limit reached. Never the detail as it is kept, and never an id: a key this does not know is left out. "" when there is nothing.
     */
    static String detailSaid(AccountStore store, String detail) {
        if (detail == null || detail.isBlank()) return "";
        JsonNode d;
        try { d = Statement.M.readTree(detail); } catch (IOException | RuntimeException e) { return ""; }
        if (!d.isObject()) return "";
        List<String> said = new ArrayList<>();
        if (d.has("level")) said.add(d.path("level").asText().equals("write") ? t("to read and write") : t("to read only"));
        if (d.has("host")) { OwnKeys.Host h = OwnKeys.host(d.path("host").asText()); said.add(t("for {0}", h == null ? d.path("host").asText() : h.said())); }
        if (d.has("name") && d.has("value")) {
            String name = d.path("name").asText();
            said.add(d.path("value").asText().equals("default") ? t("{0} back to the service's", capName(name)) : t("{0} set to {1}", capName(name), capSaid(name, d.path("value").asLong())));
        }
        if (d.has("days")) { long n = d.path("days").asLong(); said.add(n <= 0 ? t("removed at once") : Chrome.now().n(n, "with {0} day of grace", "with {0} days of grace")); }
        if (d.path("now").asBoolean(false)) said.add(t("removed at once"));
        if (d.has("to")) said.add(t("to {0}", d.path("to").asText()));
        if (d.has("from")) {
            String from = d.path("from").asText("");
            AccountStore.Account was = null;
            try { was = store.account(from); } catch (IOException ignored) { }
            if (was != null) said.add(t("kept before by {0}", was.name()));
        }
        if (d.has("node")) {
            String machine = "";
            try { for (AccountStore.NodeRow n : store.nodes()) if (n.id().equals(d.path("node").asText())) machine = n.address().isEmpty() ? n.root() : n.address(); } catch (IOException ignored) { }
            said.add(machine.isEmpty() ? t("on another machine") : t("on {0}", machine));
        }
        if (d.has("label")) said.add(t("the link for {0}", d.path("label").asText()));
        if (d.has("archive_from")) said.add(d.path("archive_from").asText("").isEmpty() ? t("from its archive") : t("from its archive of {0}", d.path("archive_from").asText()));
        if (d.has("limit")) said.add(t("the limit on {0}", switch (d.path("limit").asText()) {
            case "signin" -> t("failed sign-ins"); case "signup" -> t("new accounts"); case "join" -> t("join links opened"); case "changes" -> t("changes to the password and keys");
            case "address" -> t("requests with no account"); case "api" -> t("a program's requests"); default -> t("a browser's requests");
        }));
        return String.join(", ", said);
    }

    /** The account's own entries of the audit log, the last {@link AccountActs#OWN_DAYS} days: when, what, in which library, from where. */
    static void recentActivity(StringBuilder b, AccountStore store, AccountStore.Account me) throws IOException {
        List<AccountStore.Audit> all = store.audits(me.id(), System.currentTimeMillis() - AccountActs.OWN_DAYS * 86_400_000L, 30);
        b.append("<details class=\"fold\"><summary><h2>").append(t("Recent activity")).append("</h2></summary><p class=\"k\">").append(t("What was done with your account in the last {0} days, the latest first.", AccountActs.OWN_DAYS)).append("</p>");
        if (all.isEmpty()) b.append("<p class=\"k\">").append(t("Nothing yet.")).append("</p>");
        else {
            b.append("<table class=\"stack\"><tbody>\n");
            for (AccountStore.Audit a : all) {
                AccountStore.Library l = a.library().isEmpty() ? null : store.library(a.library());
                String by = a.actor().equals(me.id()) || a.actor().isEmpty() ? "" : a.via().equals("cli") || a.via().equals("service") ? t("by whoever runs this service") : t("by another account");
                b.append("<tr><td>").append(esc(Chrome.now().dayTime(Instant.ofEpochMilli(a.at()).toString()))).append("</td><td>").append(actSaid(a.act())).append(l == null ? "" : " · " + esc(l.title()))
                 .append(detailSaid(store, a.detail()).isEmpty() ? "" : ", " + esc(detailSaid(store, a.detail()))).append(by.isEmpty() ? "" : " · " + by).append("</td><td class=\"k\">").append(a.address().isEmpty() || !(a.actor().equals(me.id()) || a.act().startsWith("signin")) ? "" : esc(t("from {0}", a.address()))).append("</td></tr>\n");
            }
            b.append("</tbody></table>\n");
        }
        b.append("</details>");
    }

    /** A person's own deletion: what goes and when, a library with people in it handed to one of them or let go, and their name typed. */
    static void deleteSection(StringBuilder b, AccountStore store, AccountStore.Account me) throws IOException {
        int days = AccountActs.deleteDays();
        b.append("<details class=\"fold\"><summary><h2>").append(t("Delete this account")).append("</h2></summary><p>")
         .append(days == 0 ? t("Your account is removed when you confirm, with the libraries you keep and their copies.")
                 : days == 1 ? t("Your account is removed a day after you ask, with the libraries you keep and their copies. Until that day, sign in again to keep it.")
                 : t("Your account is removed {0} days after you ask, with the libraries you keep and their copies. Until that day, sign in again to keep it.", days)).append("</p>")
         .append("<form method=\"post\" action=\"/account\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"delete-account\">");
        for (AccountStore.Library l : store.librariesKeptBy(me.id())) {
            List<AccountStore.Member> members = store.members(l.id());
            if (members.isEmpty()) continue;
            b.append("<label>").append(t("{0} has people in it. Hand it to one of them, or let it go with your account:", esc(l.title()))).append("<br><select name=\"handto.").append(esc(l.id())).append("\"><option value=\"\">").append(t("let it go with my account")).append("</option>");
            for (AccountStore.Member m : members) { AccountStore.Account a = store.account(m.account()); if (a != null && a.active()) b.append("<option value=\"").append(esc(a.id())).append("\">").append(esc(t("hand it to {0}", a.name()))).append("</option>"); }
            b.append("</select></label>");
        }
        b.append("<label>").append(t("Type the name you sign in with, {0}, to confirm", esc(me.name()))).append("<br><input name=\"confirm\" size=\"40\" autocomplete=\"off\"></label><button class=\"danger\">").append(t("Delete my account")).append("</button></form></details>");
    }

    private static String memberForm(String library, String account, String op, String level, String label) {
        return "<form class=\"inline\" method=\"post\" action=\"/account\"><input type=\"hidden\" name=\"op\" value=\"" + op + "\"><input type=\"hidden\" name=\"library\" value=\"" + esc(library) + "\"><input type=\"hidden\" name=\"account\" value=\"" + esc(account) + "\">"
                + (level.isEmpty() ? "" : "<input type=\"hidden\" name=\"level\" value=\"" + level + "\">") + "<button class=\"quiet\">" + label + "</button></form>";
    }

    /** What a change on the account page comes to: a note for the page, somewhere else to go, or something shown this once (a key, a join link). */
    record Done(String note, String to, String shown, long retryAfter, String page) {
        Done(String note, String to, String shown) { this(note, to, shown, 0, ""); }
        Done(String note, String to, String shown, long retryAfter) { this(note, to, shown, retryAfter, ""); }
        /** A page of its own: what an act will do, and the button that does it (stage 4). */
        static Done page(String html) { return new Done("", "", "", 0, html); }
        static Done note(String n) { return new Done(n, "", ""); }
        /** A change a limit stopped (stage 4): the page again with the sentence, answered 429 with Retry-After. */
        static Done tooMany(String said, long seconds) { return new Done(said, "", "", Math.max(1, seconds)); }
    }

    static Done accountPost(AccountService svc, HttpExchange x, AccountService.Who who, Map<String, String> form) throws IOException {
        AccountStore store = svc.store;
        AccountStore.Account me = who.account();
        String op = form.getOrDefault("op", "");
        AccountActs.By by = AccountActs.By.of(who, x);
        if (!me.active() && !op.equals("cancel-deletion")) return Done.note(AccountActs.notActiveSaid(store, me));   // being deleted: the cancel alone
        // a key the proof page asked for again and that came back empty: asked again, never done as an empty key (which takes a key away)
        for (String f : form.getOrDefault(ASKED_AGAIN, "").split(","))
            if (!f.isBlank() && form.getOrDefault(f.strip(), "").isBlank()) { x.getResponseHeaders().set("Cache-Control", "no-store"); return Done.page(provePage(svc, who, form, t("Type your key again as well."))); }
        // a key, a key of one's own, and a deletion: the person shows it is them first, when the last proof is older than five minutes (SEC 7)
        boolean guarded = op.equals("key-new") || op.equals("own-key") && !form.getOrDefault("secret", "").isBlank() || op.equals("delete-account")
                || op.equals("library-let-go") && "yes".equals(form.get("confirm"))
                || op.equals("member-let-in");   // an invitation lets someone in for good: guarded as a key is (round F, S17)
        if (guarded && !proven(svc, who)) {
            String typed = form.get("prove_password");
            x.getResponseHeaders().set("Cache-Control", "no-store");
            if (typed == null) return Done.page(provePage(svc, who, form, null));
            RateLimits.Refused over = svc.limits.take(RateLimits.Limit.CHANGES, RateLimits.ofAccount(me.id()));   // a wrong password typed here counts as the password change's does
            if (over != null) { svc.limits.noted(me, by, over.limit()); return Done.tooMany(over.said(), over.seconds()); }
            if (!me.hasPassword() || !Passwords.check(me.passwordHash(), typed)) return Done.page(provePage(svc, who, form, t("That is not your password.")));
            store.sessionProven(who.session());
        }
        try {
            switch (op) {
                case "cancel-deletion" -> {
                    if (AccountActs.deletedByAnother(store, me)) return Done.note(t("Whoever runs this service deleted your account; only they can keep it."));
                    return Done.note(AccountActs.cancelDeletion(store, me, by) ? t("Your account is kept, as it was.") : t("Your account is not being deleted."));
                }
                case "delete-account" -> {
                    if (!Accounts.login(form.getOrDefault("confirm", "")).equals(me.login())) return Done.note(t("Type the name you sign in with, {0}, to confirm.", me.name()));
                    for (AccountStore.Library l : store.librariesKeptBy(me.id())) {
                        String to = form.getOrDefault("handto." + l.id(), "");
                        if (!to.isEmpty()) { AccountStore.Account m = store.account(to); if (m != null) AccountActs.handOver(store, l, m, by); }
                    }
                    int days = AccountActs.deleteDays();
                    long at = AccountActs.delete(store, me, days, by);
                    x.getResponseHeaders().add("Set-Cookie", AccountService.COOKIE + "=; Path=/; HttpOnly; SameSite=Lax" + (Pages.secure(x) ? "; Secure" : "") + "; Max-Age=0");
                    String said = days == 0 ? t("Your account is removed, with the libraries you kept.") : AccountActs.deletingSaid(at);
                    return new Done("", svc.noted("/login", said, null), "");
                }
                case "library-make" -> { return libraryMade(svc, me, form.getOrDefault("title", ""), by); }
                case "library-let-go" -> {
                    AccountStore.Library l = store.library(form.getOrDefault("library", ""));
                    if (l == null || !l.active() || !l.owner().equals(me.id())) return Done.note(t("Only the person who keeps a library lets it go."));
                    return letGo(svc, x, who, l, "yes".equals(form.get("confirm")), by);
                }
                case "open" -> {
                    AccountStore.Library l = store.library(form.getOrDefault("library", ""));
                    if (store.level(l, me.id()) == null) return Done.note(t("That library is not one you can open."));
                    store.sessionLibrary(who.session(), l.id());
                    return new Done("", "/", "");
                }
                case "key-new" -> {
                    AccountStore.Library l = store.library(form.getOrDefault("library", ""));
                    AccountStore.Minted m = mintKey(svc, l, me, form.getOrDefault("level", "read"), form.getOrDefault("label", ""));
                    AccountActs.audit(store, by, "key_made", me.id(), l.id(), AccountActs.detail().put("level", m.key().level()));
                    return new Done(t("Your key is made."), "", "<div class=\"notice\"><p>" + t("Your new key for {0}, which {1}. It is shown this once; copy it now.", "<b>" + esc(m.key().label()) + "</b>", m.key().reads() ? t("reads") : t("reads and writes")) + "</p><pre>" + esc(m.secret()) + "</pre></div>");
                }
                case "key-revoke" -> {
                    if (!store.revokeKey(me.id(), form.getOrDefault("id", ""))) return Done.note(t("There is no such key of yours."));
                    AccountActs.audit(store, by, "key_revoked", me.id(), "", null);
                    return Done.note(t("The key is revoked; a program that used it is refused from now on."));
                }
                case "member-let-in" -> {   // an invitation, whoever the name is: the keeper is told the same either way
                    AccountStore.Library l = kept(svc, me, form.getOrDefault("library", ""));
                    LetIn in = letIn(svc, l, me, form.getOrDefault("name", ""), form.getOrDefault("level", "read"), by);
                    return new Done(t("A link is made for {0}.", in.label()), "", "<div class=\"notice\"><p>" + t("Send {0} this link. It works once, for {1} days.", "<b>" + esc(in.label()) + "</b>", AccountService.JOIN_DAYS) + "</p><pre>" + esc(in.link()) + "</pre></div>");
                }
                case "invite-withdraw" -> {
                    AccountStore.Library l = kept(svc, me, form.getOrDefault("library", ""));
                    String id = form.getOrDefault("id", "");
                    AccountStore.Invitation i = store.invitations(l.id()).stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
                    if (i == null || !store.withdrawInvitation(l.id(), id)) return Done.note(t("There is no such link of this library's, or it was taken already."));
                    AccountActs.audit(store, by, "invitation_withdrawn", "", l.id(), AccountActs.detail().put("label", i.label()));
                    return Done.note(t("The link for {0} is withdrawn: it opens nothing now.", i.label()));
                }
                case "member-level" -> {
                    AccountStore.Library l = kept(svc, me, form.getOrDefault("library", ""));
                    // only someone already let in: an account comes in by taking an invitation, never by a level set on it (round F, S10)
                    if (!store.changeMemberLevel(l.id(), form.getOrDefault("account", ""), form.getOrDefault("level", "read"))) return Done.note(t("They were not one of its people."));
                    AccountActs.audit(store, by, "member_level", form.getOrDefault("account", ""), l.id(), AccountActs.detail().put("level", form.getOrDefault("level", "read")));
                    return Done.note(t("Changed."));
                }
                case "member-remove" -> {
                    AccountStore.Library l = kept(svc, me, form.getOrDefault("library", ""));
                    if (!store.removeMember(l.id(), form.getOrDefault("account", ""))) return Done.note(t("They were not one of its people."));
                    AccountActs.audit(store, by, "member_removed", form.getOrDefault("account", ""), l.id(), null);
                    return Done.note(t("Removed. Their next request to this library is refused, and their keys for it are gone."));
                }
                case "password" -> {
                    RateLimits.Refused over = svc.limits.take(RateLimits.Limit.CHANGES, RateLimits.ofAccount(me.id()));   // a wrong current password counts too (stage 4)
                    if (over != null) { svc.limits.noted(me, by, over.limit()); return Done.tooMany(over.said(), over.seconds()); }
                    String pw = form.getOrDefault("password", ""), why = Passwords.why(pw);
                    if (me.hasPassword() && !Passwords.check(me.passwordHash(), form.getOrDefault("current", ""))) return Done.note(t("The current password is not right."));
                    if (why != null) return Done.note(why);
                    if (!pw.equals(form.getOrDefault("again", ""))) return Done.note(t("The two passwords differ. Type the same one twice."));
                    store.setPassword(me.id(), Passwords.hash(pw));
                    store.endSessionsBut(me.id(), who.session());   // every other browser signs in again, with the new password
                    AccountActs.audit(store, by, "password_changed", me.id(), "", null);
                    return Done.note(t("Your password is changed."));
                }
                case "own-key" -> {
                    RateLimits.Refused over = svc.limits.take(RateLimits.Limit.CHANGES, RateLimits.ofAccount(me.id()));
                    if (over != null) { svc.limits.noted(me, by, over.limit()); return Done.tooMany(over.said(), over.seconds()); }
                    OwnKeys.Host h = OwnKeys.host(form.getOrDefault("host", ""));
                    if (h == null) return Done.note(t("That is not a place this service has keys for."));
                    String secret = form.getOrDefault("secret", "");
                    OwnKeys.set(store, me.id(), h, secret);
                    AccountActs.audit(store, by, secret.isBlank() ? "own_key_removed" : "own_key_set", me.id(), "", AccountActs.detail().put("host", h.host()));
                    return Done.note(secret.isBlank() ? t("Your key for {0} is taken away; your work there goes on this service's key.", h.said()) : t("Your key for {0} is kept. Your work there goes on it from now on.", h.said()));
                }
                case "signout-all" -> {
                    store.endSessions(me.id());
                    AccountActs.audit(store, by, "signout_everywhere", me.id(), "", null);
                    x.getResponseHeaders().add("Set-Cookie", AccountService.COOKIE + "=; Path=/; HttpOnly; SameSite=Lax" + (Pages.secure(x) ? "; Secure" : "") + "; Max-Age=0");
                    return new Done("", svc.noted("/login", t("Every browser is signed out."), null), "");
                }
                default -> { return Done.note(t("That is not a change the account page knows.")); }
            }
        } catch (ProtocolError e) { return e.code.equals(ProtocolError.RATE_LIMITED) ? Done.tooMany(e.getMessage(), e.extra.path("retry_after").asLong(60)) : Done.note(e.getMessage()); }
        catch (IOException e) { return Done.note(e.getMessage()); }
    }

    // ---- the acts themselves, shared by the page and by the library's access call ----

    /** The library by its id, when this account keeps it; a refusal otherwise. */
    private static AccountStore.Library kept(AccountService svc, AccountStore.Account me, String library) throws IOException {
        AccountStore.Library l = svc.store.library(library);
        if (l == null || !l.active() || !l.owner().equals(me.id())) throw ProtocolError.forbidden(t("Only the person who keeps a library lets people into it."));
        return l;
    }

    /** A key of this account for a library it can open; a key that writes only where the account may write. */
    static AccountStore.Minted mintKey(AccountService svc, AccountStore.Library l, AccountStore.Account me, String level, String label) throws IOException {
        String mine = svc.store.level(l, me.id());
        if (mine == null) throw ProtocolError.forbidden(t("That library is not one you can open."));
        String lv = level == null ? "read" : level.strip().toLowerCase(Locale.ROOT);
        if (lv.equals("write") && mine.equals(Statement.READ)) throw ProtocolError.forbidden(t("You read this library and do not write to it, so a key of yours for it reads only."));
        return svc.store.mintKey(me.id(), l.id(), lv, label);
    }

    /** Someone let into a library: an invitation, by the name the keeper gave it, at a level, and its link to send. */
    record LetIn(String label, String level, String link) { }

    /**
     * Someone let into a library (0.8.0 review, round A, the owner's word): an invitation for the library at a level, named by what the
     * keeper typed. The name is matched against no account and no account is made, so the keeper is told the same whoever it is; whoever
     * opens the link is let in, once, within {@link AccountService#JOIN_DAYS} days (signed in, or with an account they make there). Counted
     * against the account that makes it, as a sign-up is against its address (stage 4).
     */
    static LetIn letIn(AccountService svc, AccountStore.Library l, AccountStore.Account by, String name, String level, AccountActs.By acting) throws IOException {
        String n = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (n.isEmpty()) throw new IOException(t("Say who it is for: a name, to know the link by."));
        if (n.length() > 120) n = n.substring(0, 120);
        String lv = level != null && level.strip().equalsIgnoreCase("write") ? "write" : "read";
        RateLimits.Refused over = svc.limits.full(RateLimits.Limit.SIGNUP, RateLimits.ofAccount(by.id()));
        if (over != null) { svc.limits.noted(by, acting, over.limit()); throw ProtocolError.rateLimited(over.said(), over.seconds()); }
        String code = svc.store.newInvitation(l.id(), lv, n, by.id(), AccountService.JOIN_DAYS);
        svc.limits.mark(RateLimits.Limit.SIGNUP, RateLimits.ofAccount(by.id()));
        AccountActs.audit(svc.store, acting, "member_invited", "", l.id(), AccountActs.detail().put("level", lv).put("label", n));
        return new LetIn(n, lv, svc.address() + "/join?code=" + code);
    }

    /**
     * The library's {@code access} call, answered for the account its statement named: the people of the library (the keeper's to see and
     * change) and the caller's own keys for it. {@code level} is what the account may do in the library now; {@code via} how it signed in.
     */
    static ObjectNode access(AccountService svc, AccountStore.Library lib, AccountStore.Account who, String level, String via, JsonNode args) throws IOException {
        AccountStore store = svc.store;
        String op = args.path("op").asText("list").strip().toLowerCase(Locale.ROOT).replace('-', '_');
        boolean keeper = level.equals(Statement.KEEPER), changes = !op.equals("list") && !op.equals("keys");
        if (changes && via.equals(Statement.KEY_READ)) throw ProtocolError.forbidden("This key reads only. A key that writes is made on the account page.");
        ObjectNode r = Statement.M.createObjectNode();
        r.put("op", op);
        switch (op) {
            case "list" -> {
                if (!keeper) throw ProtocolError.forbidden("Only the person who keeps this library sees who reads it.");
                AccountStore.Account k = store.account(lib.owner());
                r.put("keeper", k == null ? "" : k.name());
                ArrayNode people = r.putArray("people");
                for (AccountStore.Member m : store.members(lib.id())) { AccountStore.Account a = store.account(m.account()); people.addObject().put("name", a == null ? "" : a.name()).put("did", Hosted.DID + m.account()).put("level", m.level()); }
                ArrayNode invited = r.putArray("invited");   // the links sent and not taken yet
                for (AccountStore.Invitation i : store.invitations(lib.id())) invited.addObject().put("name", i.label()).put("level", i.level()).put("until", Instant.ofEpochMilli(i.expiresAt()).atZone(ZoneId.systemDefault()).toLocalDate().toString());
                r.put("summary", people.isEmpty() ? "Nobody but its keeper opens this library." : people.size() + " " + (people.size() == 1 ? "person is" : "people are") + " let into this library.");
            }
            case "invite", "allow" -> {
                if (!keeper) throw ProtocolError.forbidden("Only the person who keeps this library lets people into it.");
                // from a page in a browser, people are let in on the account page, where the keeper shows it is them first (round F, S17)
                if (via.equals(Statement.SESSION)) throw ProtocolError.forbidden(t("People are let into a library on your account page, where you show it is you first."));
                String lv = args.path("level").asText("read");
                AccountActs.By by = new AccountActs.By(who.id(), via, "");
                LetIn in = letIn(svc, lib, who, args.path("name").asText(""), lv, by);   // an invitation, whoever the name is: the same answer either way
                r.put("name", in.label()); r.put("level", in.level()); r.put("link", in.link()); r.put("days", AccountService.JOIN_DAYS);
                r.put("note", t("Send {0} this link. It works once, for {1} days.", in.label(), AccountService.JOIN_DAYS));
            }
            case "remove" -> {
                if (!keeper) throw ProtocolError.forbidden("Only the person who keeps this library takes people out of it.");
                AccountStore.Account a = store.accountByLogin(args.path("name").asText(""));
                if (a == null && args.path("did").asText("").startsWith(Hosted.DID)) a = store.account(args.path("did").asText("").substring(Hosted.DID.length()));
                if (a == null || !store.removeMember(lib.id(), a.id())) throw ProtocolError.invalidArgs("Nobody of that name is let into this library. op=list names them.");
                AccountActs.audit(store, new AccountActs.By(who.id(), via, ""), "member_removed", a.id(), lib.id(), null);
                r.put("name", a.name()); r.put("note", a.name() + " is removed. Their next request to this library is refused, and their keys for it are gone.");
            }
            case "keys" -> {
                ArrayNode ks = r.putArray("keys");
                for (AccountStore.Key k : store.keys(who.id())) if (k.library().equals(lib.id())) ks.addObject().put("id", k.id()).put("prefix", k.prefix()).put("level", k.level()).put("label", k.label()).put("used", k.usedDay());
            }
            // a key is made on the account page, where the person shows it is them first (SEC 7), never through a library, which could ask for one
            // with any request's statement it was given (round F, S8)
            case "key_new" -> throw ProtocolError.forbidden(t("A key is made on your account page, where you show it is you first."));
            case "key_revoke" -> {
                String which = args.path("id").asText(args.path("label").asText("")).strip();
                // only a key for this library: a library is not the place to end the person's keys for their other libraries (round F, S8)
                AccountStore.Key k = null;
                for (AccountStore.Key one : store.keys(who.id())) if (one.library().equals(lib.id()) && (one.id().equals(which) || one.prefix().equals(which) || one.label().equals(which))) { k = one; break; }
                if (k == null || !store.revokeKey(who.id(), k.id())) throw ProtocolError.invalidArgs("There is no key of yours for this library named " + which + ". op=keys lists them.");
                AccountActs.audit(store, new AccountActs.By(who.id(), via, ""), "key_revoked", who.id(), lib.id(), null);
                r.put("note", "The key is revoked; a program that used it is refused from now on.");
            }
            default -> throw ProtocolError.invalidArgs("op is list, invite, allow, remove, keys, key_new or key_revoke.");
        }
        return r;
    }
}
