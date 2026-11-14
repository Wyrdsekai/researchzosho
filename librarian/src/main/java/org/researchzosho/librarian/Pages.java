package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import org.researchzosho.Stopping;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HexFormat;
import java.security.SecureRandom;
import java.util.Map;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.net.URI;
import java.nio.file.Files;
import java.net.URLDecoder;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;
import org.researchzosho.tools.ContentPolicy;
import org.researchzosho.tools.Recalls;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.librarian.dna.Dna;
import org.researchzosho.librarian.dna.ShelfDna;
import org.researchzosho.Version;
import org.researchzosho.drive.Declined;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
/**
 * The library in a browser: plain pages the daemon renders itself, no scripts, no build step. Open
 * {@code http://127.0.0.1:4649/} and you can search the shelves, ask the desk, read an entry with its
 * sources, walk the subjects, see what changed, what is open, what the overnight runs are doing, and
 * file a question for the night. Reading needs whatever the library's default level allows (read, as
 * shipped); filing research needs a token, pasted once on the login page and kept in a cookie.
 */
final class Pages {

    private static final ObjectMapper M = new ObjectMapper();
    static final String COOKIE = "rz_token";
    /** The address being served on this thread, so the rail can mark the page it is on; null outside a request. */
    static final ThreadLocal<String> PATH = new ThreadLocal<>();
    /** The page's own address with its query, less the language switch's, for the switch's links. */
    static final ThreadLocal<String> HREF = new ThreadLocal<>();
    /** The cookie a visitor who asked to be let in carries: the request's id and claim secret, until the owner answers. */
    static final String ASK = "rz_ask";

    private Pages() { }

    static boolean isPage(String path) {
        return path.equals("/") || path.equals("/keep") || path.equals("/past") || path.equals("/updates") || path.equals("/connect") || path.equals("/add") || path.equals("/translate") || path.equals("/search") || path.equals("/ask") || path.startsWith("/entry/") || path.equals("/read")
                || path.equals("/subjects") || path.equals("/changes") || path.equals("/questions") || path.equals("/dossier") || path.equals("/suggest") || path.equals("/inbox") || path.equals("/jobs") || path.startsWith("/jobs/")
                || path.equals("/research") || path.equals("/help") || path.equals("/sources") || path.equals("/login") || path.equals("/login/code") || path.equals("/login/oidc") || path.equals("/login/oidc/callback") || path.equals("/login/passkey") || path.equals("/login/passkey/options") || path.equals("/logout") || path.equals("/join") || path.equals("/account") || path.equals("/account/passkey") || path.equals("/account/passkey/options") || path.equals("/explain") || path.equals("/download") || path.startsWith("/exports/") || path.startsWith("/images/") || path.equals(ImagesPage.PATH) || path.equals("/map") || path.equals("/chat") || path.equals("/chat/runs") || path.equals("/chat/live") || path.equals("/remove") || path.equals("/settings") || path.equals("/owned")
                || path.equals(SeeksPage.PATH) || path.equals(PerspectivesPage.PATH) || path.equals(SubmitPage.PATH) || path.equals(ForecastsPage.PATH) || path.equals(BridgesPage.PATH) || path.equals(LaunchPage.PATH) || path.equals(DbPage.PATH)
                || Profiles.known().stream().anyMatch(p -> p.pagePaths().contains(path));   // a field's own pages
    }

    /** A field's own page at this address, where the field is on; null when no field has one there. */
    static Profile.Page fieldPage(LibraryStore store, Patrons.Patron patron, String path, String method, Map<String, String> q, Map<String, String> form) throws IOException {
        for (Profile p : Fields.enabled(store)) if (p.pagePaths().contains(path)) return p.page(store, patron, path, method, q, form);
        return null;
    }

    /** One page. {@code form} holds the POSTed fields (empty on GET); {@code patron} is who the cookie or header proved. */
    static void handle(HttpExchange x, LibrarianDaemon d, LibraryStore store, Patrons.Patron patron, Map<String, String> q, Map<String, String> form) throws IOException {
        String path = x.getRequestURI().getPath();
        String method = x.getRequestMethod();
        PATH.set(path);
        String rest = withoutLang(x.getRequestURI().getRawQuery());
        HREF.set(path + (rest.isEmpty() ? "" : "?" + rest));
        // the language of this request: the switch, the cookie it set, the person's own, the browser's, the library's, English
        String lang = Chrome.choose(store, patron, q.get(Chrome.QUERY), cookie(x, Chrome.COOKIE), x.getRequestHeaders().getFirst("Accept-Language"));
        Chrome.here(lang);
        if (Chrome.pick(q.get(Chrome.QUERY)) != null) x.getResponseHeaders().add("Set-Cookie", Chrome.COOKIE + "=" + lang + "; Path=/; SameSite=Lax" + (secure(x) ? "; Secure" : "") + "; Max-Age=31536000");
        LibraryProtocol p = new LibraryProtocol(store);
        TYPICAL = () -> RunProgress.typicalSeconds(p, LibrarianDaemon.patronNode(Patrons.Patron.PERSON));   // looked up at most every five minutes
        DRIVE_NOW = d::driveNow;
        try {
            switch (path) {
                case "/" -> send(x, 200, chat(store, p, patron, q));
                case "/past" -> send(x, 200, past(d, store, p, patron, q));
                case "/connect" -> { if ("POST".equals(method)) redirect(x, connect(store, p, patron, q, form, true)); else send(x, 200, connect(store, p, patron, q, form, false)); }
                case "/updates" -> { if ("POST".equals(method) && "seen".equals(form.get("op"))) { Patrons.check(store, patron, Patrons.Level.read); seenNow(store, patron); redirect(x, "/updates"); } else send(x, 200, updatesPage(store, p, patron)); }
                case "/search" -> send(x, 200, search(store, p, patron, q));
                case "/keep" -> {
                    if ("POST".equals(method) && form.getOrDefault("op", "").startsWith("mcp-")) {   // the section of other MCP servers: the keeper's, from this page alone
                        McpSetup.keeper(patron, true);
                        redirect(x, "/keep?note=" + URLEncoder.encode(McpPage.post(store, patron, form, x), StandardCharsets.UTF_8) + "#mcp");
                    }
                    else send(x, 200, keep(store, p, patron, q));
                }
                case "/ask" -> send(x, 200, ask(store, p, patron, q));
                case "/chat/runs" -> send(x, 200, chatRuns(store, p, patron, q.getOrDefault("session", "")));
                case "/chat/live" -> sendJson(x, 200, chatLive(store, patron, q.getOrDefault("session", "")));
                case "/chat" -> {
                    if ("POST".equals(method) && "stop".equals(form.get("op"))) {   // the person's stop beside the answer being written: the turn ends within seconds, what came is kept
                        Patrons.check(store, patron, Patrons.Level.read);
                        Librarian.Session s = chatSession(store, patron, form);
                        Live live = s == null ? null : LIVE.get(s.id);
                        if (live != null && !live.done) live.stop = true;
                        sendJson(x, 200, M.createObjectNode().put("stopping", live != null && !live.done));
                    } else if ("POST".equals(method) && "delete".equals(form.get("op"))) redirect(x, chatDelete(store, patron, form));   // Delete… on a conversation in the list, or on the one open in the page
                    else if ("POST".equals(method) && "1".equals(form.get("page"))) {   // the Chat page's own script: the reply comes back as the page's changing part; the box to say the next thing is never sent back, so what is typed in it stays
                        Turn turn = liveTurn(d, store, patron, form);
                        Librarian.Session s = turn.show() != null ? turn.show() : chatSession(store, patron, form);
                        // a command opened another conversation: the page's script goes to it, so the box to say the next thing names it
                        String open = turn.show() == null ? "" : "<span data-open-session=\"" + esc(turn.show().id) + "\" hidden></span>";
                        send(x, 200, s == null ? "<p class=\"k\">" + esc(t(CHAT_SIGN_IN)) + " <a href=\"/login\">" + t("Sign in") + "</a></p>" : chatView(store, p, patron, s, turn.note()) + open);
                    } else if ("POST".equals(method)) { Librarian.Session next = chatPost(d, store, patron, form), was = chatSession(store, patron, form); redirect(x, next == null || was == null || next.id.equals(was.id) ? "/" : "/chat?session=" + enc(next.id)); }
                    else send(x, 200, chat(store, p, patron, q));
                }
                case "/read" -> send(x, 200, read(store, p, patron, q));
                case "/subjects" -> { if ("POST".equals(method)) { String note = subjectsPost(store, patron, form); redirect(x, "/subjects?note=" + URLEncoder.encode(note, StandardCharsets.UTF_8)); } else send(x, 200, subjects(store, p, patron, q)); }
                case SeeksPage.PATH -> { if ("POST".equals(method)) redirect(x, SeeksPage.PATH + "?note=" + URLEncoder.encode(SeeksPage.post(store, patron, form), StandardCharsets.UTF_8)); else send(x, 200, page(store, patron, t("Seeks"), SeeksPage.body(store, patron, q))); }
                case PerspectivesPage.PATH -> send(x, 200, page(store, patron, t("Perspectives"), PerspectivesPage.body(store, patron, q)));
                case SubmitPage.PATH -> { if ("POST".equals(method)) redirect(x, SubmitPage.PATH + "?note=" + URLEncoder.encode(SubmitPage.post(store, patron, form), StandardCharsets.UTF_8)); else send(x, 200, page(store, patron, t("Submit a claim"), SubmitPage.body(store, patron, q))); }
                case ForecastsPage.PATH -> { if ("POST".equals(method)) redirect(x, ForecastsPage.PATH + "?note=" + URLEncoder.encode(ForecastsPage.post(store, patron, form), StandardCharsets.UTF_8) + (form.getOrDefault("subject", "").isBlank() ? "" : "&subject=" + URLEncoder.encode(form.get("subject"), StandardCharsets.UTF_8))); else send(x, 200, page(store, patron, t("Forecasts"), ForecastsPage.body(store, patron, q))); }
                case BridgesPage.PATH -> {
                    if ("POST".equals(method)) { BridgesPage.Done done = BridgesPage.post(store, patron, form); if (done.section() != null) send(x, 200, page(store, patron, t("Bridges"), BridgesPage.body(store, patron, q, done.section()))); else redirect(x, BridgesPage.PATH + "?note=" + URLEncoder.encode(done.note(), StandardCharsets.UTF_8)); }
                    else send(x, 200, page(store, patron, t("Bridges"), BridgesPage.body(store, patron, q, null)));
                }
                case LaunchPage.PATH -> {
                    if ("POST".equals(method)) { LaunchPage.Done done = LaunchPage.post(store, patron, form); if (done.section() != null) send(x, 200, page(store, patron, tc("Read in", READ_IN_PAGE), LaunchPage.body(store, patron, q, done.section()))); else redirect(x, LaunchPage.PATH + "?note=" + URLEncoder.encode(done.note(), StandardCharsets.UTF_8)); }
                    else send(x, 200, page(store, patron, tc("Read in", READ_IN_PAGE), LaunchPage.body(store, patron, q, null)));
                }
                case DbPage.PATH -> {
                    if ("POST".equals(method)) { DbPage.Done done = DbPage.post(store, patron, form); if (done.section() != null) send(x, 200, page(store, patron, t("Databases"), DbPage.body(store, patron, Map.of("database", form.getOrDefault("database", ""), "sql", form.getOrDefault("sql", "")), done.section()))); else redirect(x, DbPage.PATH + "?note=" + URLEncoder.encode(done.note(), StandardCharsets.UTF_8)); }
                    else send(x, 200, page(store, patron, t("Databases"), DbPage.body(store, patron, q, null)));
                }
                case "/changes" -> send(x, 200, changes(store, p, patron, q));
                case "/suggest" -> {
                    if ("POST".equals(method) && form.containsKey("outcome")) {
                        Patrons.check(store, patron, Patrons.Level.write);
                        try { Suggestions.mark(store, form.getOrDefault("id", ""), form.getOrDefault("outcome", ""), ""); } catch (IllegalArgumentException ignored) { }
                    }
                    send(x, 200, suggestPage(store, patron));
                }
                case "/dossier" -> {
                    if ("POST".equals(method) && form.containsKey("request")) {   // the page's Request button on a subject the library holds nothing on: the research run that starts the dossier
                        Patrons.check(store, patron, Patrons.Level.write);
                        String subject = form.getOrDefault("subject", "").strip();
                        if (subject.isEmpty()) { redirect(x, "/dossier"); return; }
                        Map<String, String> ask = new LinkedHashMap<>();
                        ask.put("question", "Who is " + subject + "? A full dossier."); ask.put("mode", "broad"); ask.put("field", "person"); ask.put("told", "1"); ask.put("once", "dossier-" + subject);
                        send(x, 200, researchPost(d, store, patron, ask));
                    } else if ("POST".equals(method) && (form.containsKey("tell") || form.containsKey("story") || form.containsKey("score"))) {   // the page's own forms: what the person tells, the story written, a forecast scored
                        String note = dossierPost(store, patron, form);
                        redirect(x, "/dossier?subject=" + URLEncoder.encode(form.getOrDefault("subject", "").strip(), StandardCharsets.UTF_8) + "&note=" + URLEncoder.encode(note, StandardCharsets.UTF_8));
                    } else if ("POST".equals(method) && form.containsKey("watch")) {   // the page's Watch button: a standing search on the subject
                        Patrons.check(store, patron, Patrons.Level.write);
                        String subject = form.getOrDefault("subject", "").strip();
                        if (!subject.isEmpty()) Serials.add(store, DossierPage.watchSlug(subject), subject, 7);
                        send(x, 200, dossierPage(store, patron, Map.of("subject", subject)));
                    } else if (!q.getOrDefault("in", "").isBlank()) send(x, 200, dossierIn(d, store, patron, q));
                    else send(x, 200, dossierPage(store, patron, q));
                }
                case "/questions" -> { if ("POST".equals(method)) { String to = questionsPost(d, store, p, patron, form); redirect(x, to); } else send(x, 200, questions(store, p, patron, q)); }
                case "/inbox" -> { if ("POST".equals(method)) { inboxPost(store, patron, form); redirect(x, "/inbox"); } else send(x, 200, inbox(store, p, patron, q)); }
                case "/remove" -> { if ("POST".equals(method)) send(x, 200, removePost(store, p, patron, form)); else redirect(x, "/"); }
                case "/jobs" -> {
                    if ("POST".equals(method) && "withdraw-scores".equals(form.get("op"))) {   // a translation's units off the record the checks are measured from: what left, and the thresholds now
                        String id = form.getOrDefault("job_id", "").strip();
                        send(x, 200, page(store, patron, t("Run {0}", id), withdrawPost(store, patron, id)));
                    } else if ("POST".equals(method)) { jobsPost(p, patron, form); redirect(x, "/jobs"); } else send(x, 200, jobs(store, p, patron, q));
                }
                case "/explain" -> send(x, 200, explain(store, p, patron, q));
                case "/download" -> download(x, store, patron, q);
                case "/translate" -> {   // a document of the person's own translated: the file, the language, the format and the tier, filed as a job
                    Patrons.check(store, patron, Patrons.Level.write);
                    if ("POST".equals(method)) send(x, 200, page(store, patron, t("Translate"), translatePost(store, patron, form)));
                    else send(x, 200, page(store, patron, t("Translate"), translateForm(store)));
                }
                case "/add" -> {   // a document of the person's own onto the shelves: a file from their computer, or a page by its address
                    Patrons.check(store, patron, Patrons.Level.write);
                    if ("POST".equals(method)) send(x, 200, page(store, patron, t("Add"), addPost(store, p, patron, form)));
                    else send(x, 200, page(store, patron, t("Add"), addPage()));
                }
                case "/images" -> {   // the pictures page (0.7.0): the shelves' pictures as tiles, and a search by words; /images/<file> below serves one picture
                    Patrons.check(store, patron, Patrons.Level.read);
                    if ("POST".equals(method)) { String note = ImagesPage.post(store, patron, form); redirect(x, ImagesPage.PATH + "?q=" + enc(form.getOrDefault("q", "")) + "&note=" + enc(note)); }
                    else send(x, 200, page(store, patron, t("Pictures"), ImagesPage.body(store, patron, q), 0, null, true));
                }
                case "/sources" -> {
                    Patrons.check(store, patron, Patrons.Level.read);
                    if ("POST".equals(method)) { String note = SourcesPage.post(store, patron, form); redirect(x, "/sources?note=" + URLEncoder.encode(note, StandardCharsets.UTF_8)); }
                    else send(x, 200, page(store, patron, t("Sources"), SourcesPage.body(store, mayWrite(store, patron), q)));
                }
                case "/settings" -> {
                    String op = form.getOrDefault("op", "");
                    if ("POST".equals(method) && op.equals("read_in")) {   // the person's own language to read reports in, from a report's page
                        ObjectNode a = M.createObjectNode(); a.put("op", "read_in"); a.put("value", form.getOrDefault("value", "")); a.set("patron", LibrarianDaemon.patronNode(patron));
                        String note = p.settings(a).path("note").asText(), back = form.getOrDefault("back", "");
                        if (back.startsWith("/entry/")) redirect(x, back); else send(x, 200, settings(store, p, patron, Map.of("note", note)));
                    }
                    else if ("POST".equals(method) && (op.equals("model_add") || op.equals("model_key"))) {   // the Settings page's Models section: an entry added, a host's key kept
                        Patrons.check(store, patron, Patrons.Level.write);
                        ObjectNode a = M.createObjectNode(); a.set("patron", LibrarianDaemon.patronNode(patron));
                        if (op.equals("model_add")) {
                            a.put("op", "add"); a.put("name", form.getOrDefault("name", "")); a.put("drive", form.getOrDefault("drive", "")); a.put("model", form.getOrDefault("model", ""));
                            if (!form.getOrDefault("prices", "").isBlank()) a.put("prices", form.get("prices").strip());
                            if (!form.getOrDefault("cap", "").isBlank()) a.put("cap", form.get("cap").strip());
                        } else { a.put("op", "key"); a.put("host", form.getOrDefault("host", "")); a.put("value", form.getOrDefault("value", "")); }
                        String note;
                        try { note = p.models(a).path("note").asText(); } catch (ProtocolError | IllegalArgumentException e) { note = e.getMessage(); }
                        send(x, 200, settings(store, p, patron, Map.of("note", note)));
                    }
                    else if ("POST".equals(method) && op.equals("check_keys")) {   // the Keep page's button: every service with a key asked whether it is accepted and what is left
                        Patrons.check(store, patron, Patrons.Level.write);
                        List<OutsideServices.Result> rs = OutsideServices.check(store);
                        redirect(x, "/keep?note=" + URLEncoder.encode(keysChecked(rs), StandardCharsets.UTF_8) + "#keys");
                    }
                    else if ("POST".equals(method) && op.equals("invite")) {
                        Patrons.check(store, patron, Patrons.Level.write);
                        Invites.Invite inv = Invites.make(store, form.getOrDefault("name", ""), Patrons.levelOf(form.getOrDefault("level", "read")));
                        send(x, 200, settings(store, p, patron, Map.of("join", inv.link())));   // the link is shown once, on the page, never in an address
                    }
                    else if ("POST".equals(method) && op.startsWith("key-")) {
                        ApiKeysPage.Done done = ApiKeysPage.post(store, patron, form);
                        if (!done.secret().isEmpty()) send(x, 200, keep(store, p, patron, Map.of("key", done.secret(), "note", done.note())));   // the key is shown once, on the page, never in an address
                        else redirect(x, "/keep?note=" + URLEncoder.encode(done.note(), StandardCharsets.UTF_8));
                    }
                    else if ("POST".equals(method) && op.equals("reader-link")) {
                        Patrons.check(store, patron, Patrons.Level.write);
                        try { send(x, 200, settings(store, p, patron, Map.of("join", SignIn.renewLink(store, form.getOrDefault("did", "").strip())))); }
                        catch (IOException e) { redirect(x, "/settings?note=" + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8)); }
                    }
                    else if ("POST".equals(method) && op.equals("reader-token")) {   // a bearer token for a listed reader's program, shown once on the page
                        Patrons.check(store, patron, Patrons.Level.write);
                        String did = form.getOrDefault("did", "").strip();
                        if (Patrons.byDid(store, did) == null) redirect(x, "/settings?note=" + URLEncoder.encode(t("{0} is not on the readers list.", did), StandardCharsets.UTF_8));
                        else send(x, 200, settings(store, p, patron, Map.of("token", Patrons.issueToken(store, did), "did", did)));
                    }
                    else if ("POST".equals(method) && op.startsWith("reader-")) { String note = readersPost(store, patron, form); redirect(x, "/settings?note=" + URLEncoder.encode(note, StandardCharsets.UTF_8)); }
                    else if ("POST".equals(method)) { String note = settingsPost(p, patron, form); redirect(x, "/settings?note=" + URLEncoder.encode(note, StandardCharsets.UTF_8)); }
                    else send(x, 200, settings(store, p, patron, q));
                }
                case "/owned" -> { if ("POST".equals(method)) { ownedPost(p, patron, form); redirect(x, "/owned"); } else send(x, 200, owned(store, p, patron, q)); }
                case "/help" -> { Patrons.check(store, patron, Patrons.Level.read); send(x, 200, page(store, patron, t("Help"), HelpPage.body(store, q.getOrDefault("q", ""), q.getOrDefault("doc", "")))); }
                case "/map" -> { Patrons.check(store, patron, Patrons.Level.read); send(x, 200, page(store, patron, t("Map"), MapPage.body(q.getOrDefault("focus", "")), 0, null, true)); }
                case "/research" -> {
                    if ("POST".equals(method) && "1".equals(form.get("sharpen"))) send(x, 200, sharpenStart(store, patron, form.getOrDefault("question", ""), form.getOrDefault("field", "")));
                    else if ("POST".equals(method)) send(x, 200, researchPost(d, store, patron, form));
                    else if (q.containsKey("sharpen")) send(x, 200, sharpenPoll(store, patron, q.get("sharpen"), q.getOrDefault("field", "")));
                    else send(x, 200, researchForm(store, patron, q.getOrDefault("q", ""), null, q.getOrDefault("field", "")));
                }
                case "/login" -> { if ("POST".equals(method)) SignIn.login(x, store, form); else send(x, 200, SignIn.loginPage(store, null, x)); }
                case "/login/code" -> { if ("POST".equals(method)) SignIn.code(x, store, form); else redirect(x, "/login"); }
                case "/login/oidc" -> {   // to the identity provider, with a state and a code challenge the callback will answer for
                    if (!Oidc.configured()) { redirect(x, "/login"); break; }
                    try { redirect(x, Oidc.start(x)); } catch (IOException e) { send(x, 503, SignIn.loginPage(store, t("The identity provider could not be reached: {0}", e.getMessage()), x)); }
                }
                case "/login/oidc/callback" -> {   // back from the provider: the code for a token, the token checked, the person signed in
                    try { Accounts.Account a = Oidc.finish(store, q); SignIn.signedIn(x, store, a); redirect(x, "/"); }
                    catch (IOException e) { send(x, 403, SignIn.loginPage(store, e.getMessage(), x)); }
                }
                case "/login/passkey/options" -> SignIn.passkeyOptions(x, store, patron, false);
                case "/login/passkey" -> SignIn.passkeyAssert(x, store);
                case "/logout" -> { String c = cookie(x, COOKIE); if (c != null && !c.isBlank()) Accounts.signOut(store, c); clearCookie(x); redirect(x, "/"); }
                case "/join" -> SignIn.join(x, store, method, q, form);   // a link the person who keeps the library sent: the invited person chooses a password and is signed in
                case "/account" -> {
                    if ("POST".equals(method) && form.getOrDefault("op", "").startsWith("key-")) {
                        if (!SignIn.hasAccountPage(patron)) throw ProtocolError.forbidden(t("Sign in to make a key."));
                        ApiKeysPage.Done done = ApiKeysPage.post(store, patron, form);
                        if (!done.secret().isEmpty()) send(x, 200, SignIn.account(store, patron, Map.of("key", done.secret(), "note", done.note()), x));
                        else redirect(x, "/account?note=" + enc(done.note()));
                    }
                    else if ("POST".equals(method)) { String note = SignIn.accountPost(x, store, patron, form); redirect(x, "signout-all".equals(form.get("op")) && cookie(x, COOKIE) != null ? "/" : "/account?note=" + enc(note)); }
                    else send(x, 200, SignIn.account(store, patron, q, x));
                }
                case "/account/passkey/options" -> SignIn.passkeyOptions(x, store, patron, true);
                case "/account/passkey" -> SignIn.passkeyRegister(x, store, patron);
                default -> {
                    Profile.Page own;
                    if (path.startsWith("/entry/")) send(x, 200, entry(d, store, p, patron, path.substring("/entry/".length()), q));
                    else if (path.startsWith("/jobs/")) send(x, 200, job(store, p, patron, path.substring("/jobs/".length()), q));
                    else if (path.startsWith("/exports/")) exportFile(x, store, patron, path.substring("/exports/".length()));
                    else if (path.startsWith("/images/")) imageFile(x, store, patron, path.substring("/images/".length()));
                    else if ((own = fieldPage(store, patron, path, method, q, form)) != null) {
                        if (own.redirect() != null) redirect(x, own.redirect()); else send(x, 200, page(store, patron, own.title(), own.body(), 0, null, own.wide()));
                    }
                    else send(x, 404, page(store, patron, t("Page not found"), "<p>" + t("There is no page at this address.") + "</p>"));
                }
            }
        } catch (Declined declined) {
            send(x, 200, page(store, patron, t("Declined by the model"), "<p>" + esc(declined.statement()) + "</p>"));
        } catch (ProtocolError e) {
            int status = LibrarianDaemon.status(e.code);
            if ("forbidden".equals(e.code) && onDoor(x) && (patron.web() || patron.anonymous()) && cookie(x, COOKIE) == null) {
                // through the door, a visitor is shown the way in rather than a refusal: sign in, or ask
                send(x, 403, SignIn.loginPage(store, t("This library is shared with named readers. Sign in with your name and password, or ask to be let in."), x));
                return;
            }
            String hint = "forbidden".equals(e.code) && patron.anonymous() ? " <a href=\"/login\">" + t("Sign in to do this.") + "</a>" : "";
            String c = cookie(x, COOKIE);
            if ("forbidden".equals(e.code) && c != null && !c.isBlank()) {
                // the browser sent a sign-in that is refused now (a token removed or denied since): forget it here, so the next load is a plain visit
                clearCookie(x);
                hint = " <b>" + t("This browser was signed in with a sign-in the library refuses now; that sign-in has been cleared.") + "</b> " + t("{0} to visit as everyone does, or {1} again.", "<a href=\"" + esc(path) + "\">" + t("Load the page again") + "</a>", "<a href=\"/login\">" + t("sign in") + "</a>");
            }
            send(x, status, page(store, patron, "forbidden".equals(e.code) ? t("Not allowed") : "not_found".equals(e.code) ? t("Not found") : t("Something went wrong"), "<p class=\"err\">" + esc(e.getMessage()) + hint + "</p>"));
        }
        finally { PATH.remove(); HREF.remove(); Chrome.clear(); }
    }

    /** The page's own address without the language switch's query, for the switch's links. */
    static String withoutLang(String rawQuery) {
        StringBuilder b = new StringBuilder();
        if (rawQuery == null || rawQuery.isBlank()) return "";
        for (String kv : rawQuery.split("&")) { if (kv.isEmpty() || kv.startsWith(Chrome.QUERY + "=")) continue; if (b.length() > 0) b.append('&'); b.append(kv); }
        return b.toString();
    }

    /** The words of the pages in the language of this request: {@link Chrome}. */
    static String t(String en) { return Chrome.now().t(en); }
    static String t(String en, Object... args) { return Chrome.now().t(en, args); }
    /** A count with its noun, {@code n(3, "{0} claim", "{0} claims")}. */
    static String n(long n, String one, String many) { return Chrome.now().n(n, one, many); }

    // ---- pages ----

    /** One row of Past: a report of some shape, its kind in a word, where it opens, its title, its line of state, and when it was last touched (ISO, for the order). */
    record PastRow(String kind, String href, String title, String state, String stateWord, String when) { }

    /**
     * /past: every report with its line of state, newest first: research reports (being made, filed, declined or failed), dossiers,
     * and the field pages as reports of a shape, a person's job search, a home search, a picture looked for in the videos. Chips by
     * kind and by state, a box to search the titles, forty rows and the rest behind a link. Above them, each field's own section and
     * the library's counts; below, the open questions.
     */
    static String past(LibrarianDaemon d, LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        ObjectNode status = p.status(args(patron));
        JsonNode c = status.path("counts");
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\">").append(t("Claims: {0} ({1} accepted, {2} disputed, {3} waiting for review). Reports: {4}. Summaries: {5}. Saved pages: {6}. Subjects: {7}. Last change {8}.",
                c.path("finding").path("total").asInt(), c.path("finding").path("accepted").asInt(), c.path("finding").path("disputed").asInt(), c.path("finding").path("draft").asInt(), c.path("investigation").asInt(), c.path("article").asInt(), c.path("raw").asInt(), c.path("subject").asInt(), when(status.path("last_updated").asText("never"))))
         .append(" ").append(esc(d.describeWorkers())).append(".</p>");
        List<Suggestions.Entry> now = new ArrayList<>(); try { for (Suggestions.Entry e : Suggestions.open(store)) if (e.klass().equals("now") && now.size() < 2) now.add(e); } catch (IOException ignored) { }
        for (Suggestions.Entry e : now) b.append("<p class=\"notice\">✦ ").append(esc(e.text())).append(" — ").append(esc(e.reason())).append(". <a href=\"/suggest\">").append(t("all suggestions")).append("</a></p>");
        for (Profile f : Librarian.chatFields(store)) if (f.joinsOnlyWhenAsked()) b.append(f.homeSection(store, mayWrite(store, patron)));   // a field whose work the library holds
        // the reports
        List<PastRow> rows = pastRows(store, p, patron);
        String kind = q.getOrDefault("kind", "").strip(), state = q.getOrDefault("state", "").strip(), words = q.getOrDefault("q", "").strip().toLowerCase(Locale.ROOT);
        Map<String, Integer> byKind = new LinkedHashMap<>(), byState = new LinkedHashMap<>();
        for (PastRow r : rows) { byKind.merge(r.kind(), 1, Integer::sum); byState.merge(r.stateWord(), 1, Integer::sum); }
        b.append("<h2 id=\"reports\">").append(t("Reports")).append("</h2><form class=\"ask\" method=\"get\" action=\"/past\"><input name=\"q\" value=\"").append(esc(q.getOrDefault("q", ""))).append("\" placeholder=\"").append(esc(t("a title, a name, a question…"))).append("\">")
         .append(kind.isEmpty() ? "" : "<input type=\"hidden\" name=\"kind\" value=\"" + esc(kind) + "\">").append(state.isEmpty() ? "" : "<input type=\"hidden\" name=\"state\" value=\"" + esc(state) + "\">")
         .append("<button class=\"btn primary\">").append(t("Search")).append("</button></form>");
        b.append("<div class=\"chips\">").append(chip("/past" + (state.isEmpty() ? "" : "?state=" + enc(state)), t("all"), rows.size(), kind.isEmpty()));
        for (var e : byKind.entrySet()) b.append(chip("/past?kind=" + enc(e.getKey()) + (state.isEmpty() ? "" : "&state=" + enc(state)), t(e.getKey()), e.getValue(), kind.equals(e.getKey())));
        b.append("</div><div class=\"chips\">");
        for (var e : byState.entrySet()) b.append(chip("/past?state=" + enc(e.getKey()) + (kind.isEmpty() ? "" : "&kind=" + enc(kind)), t(e.getKey()), e.getValue(), state.equals(e.getKey())));
        b.append("</div>");
        List<PastRow> shown = new ArrayList<>();
        for (PastRow r : rows) if ((kind.isEmpty() || r.kind().equals(kind)) && (state.isEmpty() || r.stateWord().equals(state)) && (words.isEmpty() || r.title().toLowerCase(Locale.ROOT).contains(words) || r.state().toLowerCase(Locale.ROOT).contains(words))) shown.add(r);
        if (rows.isEmpty()) b.append("<p class=\"k\">").append(t("No reports yet. Say “Find out …” in the chat, and the report it files is the first row here.")).append("</p>");
        else if (shown.isEmpty()) b.append("<p class=\"k\">").append(t("No report matches.")).append(" <a href=\"/past\">").append(t("All of them")).append("</a>.</p>");
        else {
            b.append("<p class=\"small k3\">").append(t("Every report with its line of state, newest first.")).append("</p><div class=\"past\">");
            boolean all = "1".equals(q.get("all")); int n = 0;
            for (PastRow r : shown) {
                if (!all && n++ >= 40) { b.append("</div><p class=\"k\">").append(t("and {0} earlier", shown.size() - 40)).append(" · <a href=\"/past?all=1").append(kind.isEmpty() ? "" : "&kind=" + enc(kind)).append(state.isEmpty() ? "" : "&state=" + enc(state)).append(words.isEmpty() ? "" : "&q=" + enc(q.get("q"))).append("\">").append(t("all of them")).append("</a></p><div>"); }
                b.append(rowHtml(r));
            }
            b.append("</div>");
        }
        List<ObjectNode> open = p.frontierList(false);
        if (!open.isEmpty()) {
            b.append("<h2>").append(t("Open questions")).append(" <a class=\"k\" href=\"/questions\">").append(t("all {0}", open.size())).append("</a></h2><ul class=\"tight\">");
            for (int i = open.size() - 1; i >= Math.max(0, open.size() - 6); i--) {
                ObjectNode o = open.get(i);
                b.append("<li>").append(withIdLinks(o.path("text").asText())).append(" <a class=\"k\" href=\"/research?q=").append(enc(o.path("text").asText())).append(o.hasNonNull("field") ? "&field=" + enc(o.path("field").asText()) : "").append("\">").append(t("look into this")).append("</a></li>");
            }
            b.append("</ul>");
        }
        b.append("<h2>").append(t("Also")).append("</h2><p>").append(t("{0} shows how the people, places and things in this library connect.", "<a href=\"/map\">" + t("The map") + "</a>")).append(" ").append(t("{0}, in order.", "<a href=\"/changes\">" + t("What changed") + "</a>")).append(" ")
         .append(t("To open the library in Obsidian or SoloMD, run {0} and open the folder {1} next to the library.", "<code>researchzosho vault</code>", "<code>" + esc(store.root().getFileName().toString()) + "-vault</code>")).append("</p>");
        return page(store, patron, t("Past"), b.toString());
    }

    static String chip(String href, String label, int n, boolean on) { return "<a class=\"chip" + (on ? " on" : "") + "\" href=\"" + esc(href) + "\"><b>" + esc(label) + "</b> " + n + "</a>"; }

    /** Every report as a row, newest first. */
    static List<PastRow> pastRows(LibraryStore store, LibraryProtocol p, Patrons.Patron patron) throws IOException {
        List<PastRow> rows = new ArrayList<>();
        Jobs jobs = new Jobs(store, j -> "");
        long now = System.currentTimeMillis(), typical = RunProgress.typicalSeconds(p, args(patron).path("patron"));
        // the runs: a research run going is a report being made; one that ended with nothing filed is a row that says why
        Map<String, ObjectNode> jobByReport = new HashMap<>();
        for (ObjectNode raw : jobs.active()) {
            if (!raw.path("kind").asText("").equals("research")) continue;
            RunProgress.View v = RunProgress.of(Jobs.view(raw), now, typical);
            String title = runName(store, raw);
            String kind = reportKind(raw.path("args").path("field").asText(""));
            String line = v.state().equals("running") ? t("Still being made: {0}, {1} in; the report fills as it goes.", esc(RunProgress.stageInSentence(raw.path("progress"))), esc(RunProgress.elapsed(v.elapsedSeconds())))
                    : v.state().equals("queued") ? t("Waiting to start; the runs ahead of it go first.") : t("Waiting for your answer in the chat; with none, it starts as it was filed.");
            rows.add(new PastRow(kind, "/jobs/" + enc(v.id()), title, line, "being made", raw.path("started_at").asText(raw.path("queued_at").asText(""))));
        }
        for (ObjectNode raw : jobs.recent(80, null)) {
            if (!raw.path("kind").asText("").equals("research")) continue;
            ObjectNode view = Jobs.view(raw);
            String inv = view.path("investigation").asText("");
            if (!inv.isEmpty()) { jobByReport.put(inv, raw); continue; }
            RunProgress.View v = RunProgress.of(view, now, typical);
            String title = runName(store, raw), again = " <a href=\"/research?q=" + enc(raw.path("args").path("question").asText("")) + "\">" + t("Ask again") + "</a>.", kind = reportKind(raw.path("args").path("field").asText(""));
            if (raw.path("declined").path("run").asBoolean(false)) rows.add(new PastRow(kind, "/jobs/" + enc(v.id()), title, t("Declined by the model: {0}", esc(Acquisitions.compress(raw.path("declined").path("statement").asText(""), 200))) + " " + t("Nothing filed.") + again, "declined", raw.path("ended_at").asText("")));
            else if (v.state().equals("failed")) rows.add(new PastRow(kind, "/jobs/" + enc(v.id()), title, t("Failed after {0}: {1}", esc(RunProgress.elapsed(v.elapsedSeconds())), esc(Acquisitions.compress(raw.path("result").asText(""), 160))) + " " + t("Nothing filed.") + again, "failed", raw.path("ended_at").asText("")));
            else if (v.state().equals("stopped")) rows.add(new PastRow(kind, "/jobs/" + enc(v.id()), title, t("Stopped after {0}.", esc(RunProgress.elapsed(v.elapsedSeconds()))) + " " + t("Nothing filed.") + again, "stopped", raw.path("ended_at").asText("")));
        }
        // the ledger: the cite-check's counts, by the run
        Map<String, JsonNode> ledger = new HashMap<>();
        Path lf = RunLedger.file(store);
        if (Files.isRegularFile(lf)) for (String line : Files.readAllLines(lf, StandardCharsets.UTF_8)) { if (line.isBlank()) continue; try { JsonNode r = M.readTree(line); ledger.put(r.path("job_id").asText(""), r); } catch (IOException ignored) { } }
        // the claims waiting for review, by the report they came from
        Map<String, Integer> waiting = new HashMap<>();
        for (ObjectNode o : p.inboxList()) { String id = o.path("report").asText(""); if (!id.isEmpty()) waiting.merge(id, 1, Integer::sum); }
        // the reports
        List<String> ids = new ArrayList<>();
        if (Files.isDirectory(store.investigationsDir())) try (var s = Files.list(store.investigationsDir())) { for (Path f : s.toList()) { String n = f.getFileName().toString(); if (n.endsWith(".md")) ids.add(n.substring(0, n.length() - 3)); } }
        ids.sort(Comparator.reverseOrder());
        String mine = ReadIn.of(store, patron);   // the language this person reads in: a row says what a report in another was written in
        for (String id : ids.size() > 300 ? ids.subList(0, 300) : ids) {
            Investigation inv; try { inv = store.investigation(id); } catch (Exception e) { continue; }
            if (inv == null) continue;
            ObjectNode job = jobByReport.get(id); JsonNode led = job == null ? null : ledger.get(job.path("job_id").asText(""));
            rows.add(new PastRow(reportKind(job == null ? "" : job.path("args").path("field").asText("")), "/entry/" + enc(id) + "?beside=1", inv.title(), reportStateLine(inv, job, led, waiting.getOrDefault(id, 0)) + languageNote(store, inv, mine), "done", inv.recordedAt()));
        }
        // the dossiers
        try {
            ObjectNode a = args(patron); a.put("op", "list");
            for (JsonNode e : p.dossier(a).path("dossiers")) {
                int claims = e.path("claims").asInt(), reports = e.path("reports").asInt();
                String last = e.path("last_run").asText("");
                rows.add(new PastRow("dossier", "/dossier?subject=" + enc(e.path("subject").asText()) + "&beside=1", e.path("subject").asText(), dossierState(last, claims, reports, e.path("story").asBoolean(false)), "done", last));
            }
        } catch (Exception ignored) { }   // a library with no dossiers, or a reader the dossiers refuse: no rows
        // the fields: a person's job search, a home search, a picture looked for in the videos
        try {
            for (Career.Seeker s : Career.seekers(store)) {
                JsonNode run = newestRun(CareerFind.dir(store, s).resolve("runs"));
                String line, at = run == null ? "" : run.path("at").asText("");
                if (run == null) line = t("Not searched yet. The page has the search.");
                else {
                    int looked = 0; for (JsonNode x : run.path("companies")) if (x.path("looked").asBoolean(!x.path("system").asText("").isEmpty())) looked++;
                    line = t("Searched {0}.", esc(dayTime(at))) + " " + t("{0} looked at, {1}, {2} read.", n(looked, "{0} employer", "{0} employers"), n(run.path("passing").size(), "{0} opening fits", "{0} openings fit"), run.path("read").size());
                }
                rows.add(new PastRow("job search", "/career?seeker=" + enc(s.slug()), t("Job search for {0}", s.name()), line, run == null ? "not yet" : "done", at));
            }
        } catch (Exception ignored) { }
        try {
            for (Home.Search s : Home.searches(store)) {
                JsonNode run = newestRun(s.dir(store).resolve("search").resolve("runs"));
                String line, at = run == null ? "" : run.path("at").asText("");
                if (run == null) line = t("Not searched yet. The page has the search.");
                else { int listings = 0; for (JsonNode y : run.path("sources")) listings += y.path("listings").asInt(0); line = t("Searched {0}.", esc(dayTime(at))) + " " + t("{0} from {1}; {2} pass, {3} set aside.", n(listings, "{0} listing", "{0} listings"), n(run.path("sources").size(), "{0} source", "{0} sources"), run.path("passing").size(), run.path("aside").asInt(0)); }
                rows.add(new PastRow("home search", "/homes?search=" + enc(s.slug()), t("Home search: {0}", s.name()), line, run == null ? "not yet" : "done", at));
            }
        } catch (Exception ignored) { }
        try {
            ObjectNode shelf = Shelf.profile(store);
            if (shelf.path("calibre").asBoolean()) { String last = ""; for (JsonNode x : shelf.path("added_lately")) { String t = x.asText(); if (t.length() >= 10 && t.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}") && t.compareTo(last) > 0) last = t.substring(0, 10); }
                rows.add(new PastRow("books", BooksPage.PATH, t("Your shelf"), esc(BooksPage.stateLine(shelf, Chrome.now())), "done", last)); }
        } catch (Exception ignored) { }
        List<Serials.Shelf> shows = PodcastsPage.followed(store);
        if (!shows.isEmpty()) { String last = ""; for (Serials.Shelf s : shows) if (PodcastsPage.checked(s) && s.lastChecked().compareTo(last) > 0) last = s.lastChecked(); rows.add(new PastRow("podcasts", PodcastsPage.PATH, t("Podcasts followed"), esc(PodcastsPage.stateLine(shows, Chrome.now())), "done", last)); }
        List<VideoRecall.Held> videos = VideoRecall.held(store);   // the videos the library has read, with their three tracks: a row of the videos' kind, with the Video page
        if (!videos.isEmpty()) { String last = ""; for (VideoRecall.Held h : videos) if (h.readOn().compareTo(last) > 0) last = h.readOn(); rows.add(new PastRow("videos", "/video#held", t("Videos read"), esc(VideoRecallPage.heldLine(videos)), "done", last)); }
        for (ObjectNode k : VideoRecall.kept(store))
            rows.add(new PastRow("videos", "/video", t("A picture in the videos: {0}", Acquisitions.compress(k.path("description").asText(""), 60)), (k.path("model").asText("").isEmpty() ? t("Looked {0}: {1}", esc(dayTime(k.path("at").asText(""))), esc(Acquisitions.compress(k.path("answer").asText(""), 140))) : t("Looked {0} with {1}: {2}", esc(dayTime(k.path("at").asText(""))), esc(k.path("model").asText()), esc(Acquisitions.compress(k.path("answer").asText(""), 140)))), "done", k.path("at").asText("")));
        rows.sort(Comparator.comparing(PastRow::when).reversed());
        return rows;
    }

    /** A dossier's line of state on Past and on its page: the last run, the claims and the reports, and whether a story is written. */
    static String dossierState(String last, int claims, int reports, boolean story) {
        return (last.isEmpty() ? t("No run yet.") : t("Last run {0}.", esc(dayTime(last)))) + " " + t("{0} from {1}.", n(claims, "{0} claim", "{0} claims"), n(reports, "{0} report", "{0} reports")) + (story ? " " + t("A story is written.") : "");
    }

    /** A report's kind on Past: the field it was run in when the field has reports of its own shape (books, podcasts, videos, shopping, software…); else a research report. */
    static String reportKind(String field) {
        if (field == null) return "research report";
        return switch (field) { case "books", "podcasts", "shopping", "software" -> field; case "youtube", "video" -> "videos"; default -> "research report"; };
    }

    /** One row of Past as HTML. */
    static String rowHtml(PastRow r) {
        return "<div class=\"prow " + esc(r.stateWord().replace(' ', '-')) + "\"><span class=\"kind\">" + esc(t(r.kind())) + "</span><span><span class=\"t\"><a href=\"" + esc(r.href()) + "\">" + esc(r.title()) + "</a></span>"
             + "<span class=\"state " + esc(r.stateWord().replace(' ', '-')) + "\">" + r.state() + "</span></span><span class=\"w\">" + esc(Chrome.now().day(r.when().length() >= 10 ? r.when().substring(0, 10) : r.when())) + "</span></div>";
    }

    /** A field's reports on its own page: Past's rows of that kind under a heading, or the words for none. */
    static String rowsSection(LibraryStore store, Patrons.Patron patron, String kind, String heading, String none) throws IOException {
        StringBuilder b = new StringBuilder("<h2>").append(esc(heading)).append("</h2>");
        List<PastRow> rows = new ArrayList<>();
        for (PastRow r : pastRows(store, new LibraryProtocol(store), patron)) if (r.kind().equals(kind) && !r.href().equals(BooksPage.PATH) && !r.href().equals(PodcastsPage.PATH)) rows.add(r);
        if (rows.isEmpty()) return b.append("<p class=\"k\">").append(esc(none)).append("</p>").toString();
        b.append("<div class=\"past\">"); for (PastRow r : rows) b.append(rowHtml(r)); return b.append("</div>").toString();
    }

    /** The newest run record in a folder of them (named by their time), or null. */
    static JsonNode newestRun(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return null;
        Path newest = null;
        try (var s = Files.list(dir)) { for (Path f : s.toList()) if (f.getFileName().toString().endsWith(".json") && (newest == null || f.getFileName().toString().compareTo(newest.getFileName().toString()) > 0)) newest = f; }
        return newest == null ? null : M.readTree(newest.toFile());
    }

    /** A moment as a person reads it: "today, 08:22", "yesterday, 05:08", "1 Nov, 05:08", "14 Oct 2025"; the text itself when it is not a moment. */
    static String dayTime(String iso) { return Chrome.now().dayTime(iso); }

    /** The run that filed a report and its ledger row, either null when the library has no record of it. */
    record ReportRun(ObjectNode job, JsonNode ledger) { }

    /** The run behind one report, from the finished runs of the last while and the ledger. */
    static ReportRun reportRun(LibraryStore store, String reportId) {
        try {
            Jobs jobs = new Jobs(store, j -> "");
            for (ObjectNode raw : jobs.recent(120, null)) {
                if (!reportId.equals(Jobs.view(raw).path("investigation").asText(""))) continue;
                Path lf = RunLedger.file(store);
                if (Files.isRegularFile(lf)) for (String line : Files.readAllLines(lf, StandardCharsets.UTF_8)) { if (line.isBlank()) continue; try { JsonNode r = M.readTree(line); if (raw.path("job_id").asText("").equals(r.path("job_id").asText(""))) return new ReportRun(raw, r); } catch (IOException ignored) { } }
                return new ReportRun(raw, null);
            }
        } catch (IOException ignored) { }
        return new ReportRun(null, null);
    }

    /** A report's line of state: when it was filed and what it holds, the cite-check's counts when the run left them, what changed since. */
    static String reportStateLine(Investigation inv, ObjectNode job, JsonNode led, int waiting) {
        StringBuilder s = new StringBuilder(t("Done {0}.", esc(dayTime(inv.recordedAt())))).append(" ").append(t("Report filed"));
        if (led != null && led.path("cite_checked").asInt(0) > 0) s.append("; ").append(t("{0} of {1} cited sentences checked against their sources", led.path("cite_supported").asInt(), led.path("cite_checked").asInt())).append(led.path("cite_unsupported").asInt(0) > 0 ? ", " + t("{0} marked", led.path("cite_unsupported").asInt()) : "");
        s.append("; ").append(n(inv.findings().size(), "{0} claim", "{0} claims")).append(", ").append(n(inv.open().size(), "{0} open question", "{0} open questions")).append(".");
        if (waiting > 0) s.append(" ").append(n(waiting, "Since then: {0} claim waits for your review.", "Since then: {0} claims wait for your review."));
        if (job != null && job.path("declined").isObject() && !job.path("declined").path("run").asBoolean(false)) s.append(" ").append(t("The model declined part of it."));
        if (inv.state() == Finding.State.draft && job != null && job.path("checks").path("held").asBoolean(false)) s.append(" ").append(t("It is not accepted and waits for you to look at it."));   // one of its own checks holds it back; Updates says why
        return s.toString();
    }

    static String legend() { return "<p class=\"legend\"><span class=\"est\">●</span> " + t("established: accepted") + " · <span class=\"est no\">○</span> " + t("not yet: waiting for review, disputed or retired.") + " " + t("The mark stands by each claim; the claim's own page has the fuller standing.") + "</p>"; }

    /** A claim's one mark for a reader: established, or not yet, linking to its page; nothing when the shelves do not hold it. */
    static String estMark(LibraryStore store, String fid) {
        try {
            Finding f = store.finding(fid);
            if (f == null) return "";
            boolean est = f.state() == Finding.State.accepted;
            return "<a class=\"est" + (est ? "" : " no") + "\" href=\"/entry/" + enc(fid) + "\" title=\"" + (est ? t("established: accepted") : t("not yet: {0}", t(f.state().name()))) + "\">" + (est ? "●" : "○") + "</a>";
        } catch (Exception e) { return ""; }
    }

    /** A claim's title after its id, for a list of claims. */
    static String claimTitle(LibraryStore store, String fid) {
        try { Finding f = store.finding(fid); return f == null ? "" : " " + esc(f.title()); } catch (Exception e) { return ""; }
    }

    /** How a report was made, behind a fold: who asked and when, the model, the time, the run's turns, the cite-check, and the record with its downloads. */
    static String howMade(LibraryStore store, String id, ObjectNode e, Investigation inv, ReportRun run, String record) {
        ObjectNode job = run.job(); JsonNode led = run.ledger();
        StringBuilder in = new StringBuilder("<dl class=\"made\">");
        if (job != null) {
            RunProgress.View v = RunProgress.of(Jobs.view(job));
            String who = job.path("patron").asText(""), model = job.path("args").path("model_entry").asText(led == null ? "" : led.path("model").asText("")), drive = job.path("drive").asText("");
            in.append("<dt>").append(t("Asked by")).append("</dt><dd>").append(esc(who.isEmpty() || who.equals("person") ? t("you") : who)).append(", ").append(esc(dayTime(job.path("queued_at").asText("")))).append("</dd>");
            in.append("<dt>").append(t("Model")).append("</dt><dd>").append(esc(model.isEmpty() ? (drive.isEmpty() ? t("the library's model") : drive) : drive.isEmpty() ? model : t("{0} on {1}", model, drive))).append("</dd>");
            in.append("<dt>").append(t("Time")).append("</dt><dd>").append(t("{0}, started {1}", esc(RunProgress.elapsed(v.elapsedSeconds())), esc(dayTime(job.path("started_at").asText(""))))).append("</dd>");
            String result = job.path("result").asText("").replaceFirst("(?s)^investigation \\S+\\s*", "").strip();
            in.append("<dt>").append(t("The run")).append("</dt><dd>").append(esc(result.isEmpty() ? "" : result + " · ")).append("<a href=\"/jobs/").append(enc(job.path("job_id").asText())).append("\">").append(t("the run's record")).append("</a></dd>");
            if (led != null && led.path("cite_checked").asInt(0) > 0) in.append("<dt>").append(t("Cite-check")).append("</dt><dd>").append(t("{0} of {1} cited sentences supported by their sources, {2} marked", led.path("cite_supported").asInt(), led.path("cite_checked").asInt(), led.path("cite_unsupported").asInt())).append(led.path("cite_unretrieved").asInt(0) > 0 ? ", " + t("{0} with a source that could not be fetched", led.path("cite_unretrieved").asInt()) : "").append("</dd>");
            if (job.path("declined").isObject()) in.append("<dt>").append(t("Declined")).append("</dt><dd>").append(esc(job.path("declined").path("statement").asText(t("the model declined part of the run")))).append("</dd>");
        } else in.append("<dt>").append(t("The run")).append("</dt><dd>").append(t("No record of the run that filed it; the report was filed by {0}.", esc(inv.writer()))).append("</dd>");
        if (!inv.options().isEmpty()) in.append("<dt>").append(t("Written as asked")).append("</dt><dd>").append(esc(inv.options().described())).append("</dd>");
        in.append("<dt>").append(t("Record")).append("</dt><dd>").append(record).append(ladder(id, Explain.Rung.written)).append(downloads(id, null)).append("</dd></dl>");
        String gist = job == null ? t("filed by {0}", esc(inv.writer())) : esc(RunProgress.elapsed(RunProgress.of(Jobs.view(job)).elapsedSeconds())) + (led == null ? "" : ", " + t("{0} of {1} cites held", led.path("cite_supported").asInt(), led.path("cite_checked").asInt()));
        return "<details class=\"sec made\"><summary><h2>" + t("How this was made") + "</h2><span class=\"gist\">" + gist + "</span></summary><div class=\"in\">" + in + "</div></details>";
    }

    /**
     * A report beside the chat: the conversation on the left with the strip under its composer, the reading on the right with a way to
     * close it; on a phone the reading alone with a way back to Past. The reading's own heading stands in its column.
     */
    static String beside(LibraryStore store, LibraryProtocol lp, Patrons.Patron patron, String title, String kicker, String closeHref, String body) throws IOException {
        Librarian.Session s = Librarian.Session.latest(store, patron);
        StringBuilder b = new StringBuilder("<div class=\"cols\">");
        if (s != null) b.append("<section class=\"conv\">").append(conv(store, lp, patron, s, true)).append("</section>");
        b.append("<section class=\"reading\"><a class=\"back small\" href=\"/past\">← ").append(t("Past")).append("</a><div class=\"rhead\"><p class=\"kicker\">").append(kicker).append("</p><a class=\"close\" href=\"").append(esc(closeHref)).append("\" title=\"").append(esc(t("Close"))).append("\">×</a></div><h1>").append(esc(title)).append("</h1>").append(body).append("</section></div>");
        if (s != null) b.append("<div id=\"scrim\" class=\"scrim\"></div>").append(sessionsDrawer(store, patron, s)).append("<script>").append(chatPageJs()).append("</script>");
        return page(store, patron, title, b.toString(), 0, null, "split", true);
    }

    /** One row of Updates: when, the report or claim it is about and where it opens, what happened in a few words, the gist, the actions, and whether it is older than the person's last look. */
    record Update(long atMs, String href, String about, String what, String gist, String acts, String key) { }

    /** Where a person's last look at Updates is kept: the change log's line and the moment. The person who keeps the library and those who write share one; a reader has their own. */
    static Path seenFile(LibraryStore store, Patrons.Patron patron) {
        String key = Patrons.mayWrite(store, patron) ? "keeper" : patron == null || patron.did().isEmpty() ? "visitor" : Patrons.sha256(patron.did()).substring(0, 16);
        return store.root().resolve("catalog").resolve("updates-seen").resolve(key + ".json");
    }

    /** The person's last look: [the change log's line, the moment in ms]; zeros when they never looked. */
    static long[] lastLook(LibraryStore store, Patrons.Patron patron) {
        try { Path f = seenFile(store, patron); if (!Files.isRegularFile(f)) return new long[]{0, 0}; JsonNode j = M.readTree(f.toFile()); return new long[]{j.path("seq").asLong(0), j.path("at").asLong(0)}; }
        catch (IOException e) { return new long[]{0, 0}; }
    }

    /** The person has looked: everything so far is seen. */
    static void seenNow(LibraryStore store, Patrons.Patron patron) throws IOException {
        long seq = 0; for (Changes.Change c : Changes.tail(store, 1)) seq = c.seq();
        Path f = seenFile(store, patron); Files.createDirectories(f.getParent());
        Files.writeString(f, M.createObjectNode().put("seq", seq).put("at", System.currentTimeMillis()).toString(), StandardCharsets.UTF_8);
        UPDATES_COUNT.remove(store.root().toString() + "|" + f.getFileName());
    }

    /** The count beside Updates in the rail, kept for a minute per person: the rows newer than their last look. */
    static final Map<String, long[]> UPDATES_COUNT = new ConcurrentHashMap<>();

    static int updatesCount(LibraryStore store, Patrons.Patron patron) {
        String key = store.root().toString() + "|" + seenFile(store, patron).getFileName();
        long now = System.currentTimeMillis();
        long[] c = UPDATES_COUNT.get(key);
        if (c != null && now - c[1] < 60_000) return (int) c[0];
        int n = 0;
        try { long[] look = lastLook(store, patron); for (Update u : updates(store, new LibraryProtocol(store), patron, look)) if (u.atMs() > look[1]) n++; } catch (Exception e) { n = 0; }
        UPDATES_COUNT.put(key, new long[]{n, now});
        return n;
    }

    /**
     * /updates: what changed on the person's reports, newest first. A run that landed, with the claims it left for review; a run that
     * started; a question the library waits on; the claims waiting for review, by report; a claim that came back, was retracted,
     * revised, accepted, disputed or retired; who asks to be let in; a recall notice. Everything newer than the last look is marked
     * new; "Mark all as seen" makes now the last look. The Inbox page is where the claims are reviewed; its address still answers.
     */
    static String updatesPage(LibraryStore store, LibraryProtocol p, Patrons.Patron patron) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        long[] look = lastLook(store, patron);
        List<Update> rows = updates(store, p, patron, look);
        int fresh = 0; for (Update u : rows) if (u.atMs() > look[1]) fresh++;
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"lede\">").append(look[1] > 0 ? t("What changed on your reports since you last looked, {0}.", esc(dayTime(Instant.ofEpochMilli(look[1]).toString()))) : t("What changed on your reports.")).append(" ").append(t("A run that landed, a claim that came back, a question the library waits on, the claims that wait for your review."))
         .append(rows.isEmpty() ? "" : " <form class=\"inline\" method=\"post\" action=\"/updates\"><input type=\"hidden\" name=\"op\" value=\"seen\">" + nonceField() + "<button class=\"btn quiet\">" + t("Mark all as seen") + "</button></form>").append("</p>");
        if (rows.isEmpty()) { b.append("<p class=\"k\">").append(t("Nothing yet. A research run that lands, and the claims it leaves for review, appear here.")).append("</p>"); return page(store, patron, t("Updates"), b.toString()); }
        b.append("<div class=\"updates\">");
        for (Update u : rows) {
            b.append("<div class=\"urow").append(u.atMs() > look[1] ? "" : " seen").append("\"><span class=\"w\">").append(esc(dayTime(Instant.ofEpochMilli(u.atMs()).toString()))).append("</span><span><span class=\"rep\"><a href=\"").append(esc(u.href())).append("\">").append(esc(u.about())).append("</a><span class=\"kind\"> · ").append(esc(u.what())).append("</span></span><span class=\"g\">").append(u.gist()).append("</span></span><span class=\"acts\">").append(u.acts()).append("</span></div>");
        }
        b.append("</div>");
        b.append("<p class=\"k small\">").append(t("{0} since your last look.", n(fresh, "{0} is new", "{0} are new"))).append(" ").append(t("The whole record is on {0}; the claims are reviewed on the {1} page.", "<a href=\"/changes\">" + t("Changes") + "</a>", "<a href=\"/inbox\">" + t("Inbox") + "</a>")).append("</p>");
        return page(store, patron, t("Updates"), b.toString());
    }

    /** The button that accepts a report as it stands, the keeper having looked. */
    static String acceptReport(String id) {
        return "<form class=\"inline\" method=\"post\" action=\"/inbox\"><input type=\"hidden\" name=\"do\" value=\"accept-report\"><input type=\"hidden\" name=\"report\" value=\"" + esc(id) + "\">" + nonceField() + "<button class=\"btn\">" + t("Accept the report") + "</button></form>";
    }

    /** The rows of Updates, newest first, at most a hundred. */
    static List<Update> updates(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, long[] look) throws IOException {
        List<Update> rows = new ArrayList<>();
        Jobs jobs = new Jobs(store, j -> "");
        long now = System.currentTimeMillis(), typical = RunProgress.typicalSeconds(p, args(patron).path("patron"));
        // the claims waiting for review, by the report they came from
        Map<String, Integer> waiting = new LinkedHashMap<>(); Map<String, String> titles = new HashMap<>(); Map<String, String> latest = new HashMap<>();
        for (ObjectNode o : p.inboxList()) {
            String id = o.path("report").asText("");
            if (id.isEmpty()) continue;
            waiting.merge(id, 1, Integer::sum); titles.putIfAbsent(id, o.path("report_title").asText(id));
            String d = o.path("date").asText(""); if (d.compareTo(latest.getOrDefault(id, "")) > 0) latest.put(id, d);
        }
        // the runs: landed, going, or waiting on an answer
        Set<String> landed = new HashSet<>(), held = new HashSet<>();
        for (ObjectNode raw : jobs.recent(60, null)) {
            if (!raw.path("kind").asText("").equals("research")) continue;
            ObjectNode view = Jobs.view(raw);
            String inv = view.path("investigation").asText(""); long at = RunProgress.millis(raw.path("ended_at").asText(""));
            if (inv.isEmpty() || at <= 0) continue;
            landed.add(inv);
            Investigation report; try { report = store.investigation(inv); } catch (Exception e) { report = null; }
            if (report == null) continue;
            int w = waiting.getOrDefault(inv, 0);
            String gist = t("Report filed; {0}, {1}", n(report.findings().size(), "{0} claim", "{0} claims"), n(report.open().size(), "{0} open question", "{0} open questions")) + (w > 0 ? "; " + n(w, "{0} waits for your review.", "{0} wait for your review.") : ".");
            String acts = (w > 0 ? "<a class=\"btn\" href=\"/inbox?report=" + enc(inv) + "\">" + t("Review the {0}", w) + "</a> " : "") + "<a class=\"btn quiet\" href=\"/entry/" + enc(inv) + "?beside=1\">" + t("Open") + "</a>";
            // a report that one of its own checks holds back is not accepted: the row says so and why, and the keeper accepts it here
            ReportStanding.Row hold = report.state() == Finding.State.draft ? ReportStanding.held(store, report) : null;
            if (hold != null) { held.add(inv); gist = t("Report filed. It is not accepted and waits for you to look at it: {0}", Acquisitions.compress(hold.why(), 320)) + " " + gist; if (Patrons.mayWrite(store, patron)) acts = acceptReport(inv) + " " + acts; }
            rows.add(new Update(at, "/entry/" + enc(inv) + "?beside=1", report.title(), hold != null ? t("a run landed; its report waits for you") : t("a run landed"), esc(gist), acts, "landed:" + inv));
        }
        // the reports that wait and whose run did not land lately: held by the review, or by a run of some time ago
        for (ReportStanding.Waiting wt : ReportStanding.waiting(store)) {
            String inv = wt.report().id();
            if (held.contains(inv)) continue;
            long at = RunProgress.millis(wt.hold().date() + "T00:00:00Z");
            rows.add(new Update(at, "/entry/" + enc(inv) + "?beside=1", wt.report().title(), t("a report waits for you to look at it"), esc(t("It is not accepted and waits for you to look at it: {0}", Acquisitions.compress(wt.hold().why(), 320))),
                    (Patrons.mayWrite(store, patron) ? acceptReport(inv) + " " : "") + "<a class=\"btn quiet\" href=\"/entry/" + enc(inv) + "?beside=1\">" + t("Open") + "</a>", "held:" + inv));
        }
        for (ObjectNode raw : jobs.active()) {
            if (!raw.path("kind").asText("").equals("research")) continue;
            RunProgress.View v = RunProgress.of(Jobs.view(raw), now, typical);
            long at = RunProgress.millis(raw.path("started_at").asText(raw.path("queued_at").asText("")));
            if (v.state().equals(Jobs.OFFERED)) rows.add(new Update(at, "/", runName(store, raw), t("the library asks"), esc(Acquisitions.compress(Jobs.view(raw).path("waiting").asText(t("The library asked whether to research this; with no answer it starts as filed.")).replace("\n\n", " "), 220)), "<a class=\"btn\" href=\"/\">" + t("Answer in the chat") + "</a>", "asks:" + v.id()));
            else rows.add(new Update(at, "/jobs/" + enc(v.id()), runName(store, raw), t("a run started"), esc(v.state().equals("queued") ? t("Waiting to start.") : t("{0}, {1} in; the report fills as it goes.", v.stage(), RunProgress.elapsed(v.elapsedSeconds()))), "<a class=\"btn quiet\" href=\"/\">" + t("Watch it") + "</a>", "going:" + v.id()));
        }
        // claims waiting whose report did not land lately: a row of their own
        for (var e : waiting.entrySet()) {
            if (landed.contains(e.getKey())) continue;
            long at = RunProgress.millis(latest.getOrDefault(e.getKey(), "") + "T00:00:00Z");
            rows.add(new Update(at, "/entry/" + enc(e.getKey()) + "?beside=1", titles.get(e.getKey()), t("claims wait for your review"), n(e.getValue(), "{0} claim waits for your review.", "{0} claims wait for your review."), "<a class=\"btn\" href=\"/inbox?report=" + enc(e.getKey()) + "\">" + t("Review the {0}", e.getValue()) + "</a>", "waiting:" + e.getKey()));
        }
        // claims that changed: came back, were retracted or revised, accepted, disputed, retired; the newest change of each claim
        Map<String, Changes.Change> byClaim = new LinkedHashMap<>();
        for (Changes.Change c : Changes.tail(store, 200)) if (c.kind().equals("finding") && !c.event().equals("added") && !c.event().equals("removed")) byClaim.put(c.id(), c);
        for (Changes.Change c : byClaim.values()) {
            String ev = c.event(), what, gist;
            if (ev.equals("retracted")) { what = t("a claim's source was retracted"); gist = t("The paper behind it was retracted ({0}).", c.detail()); }
            else if (ev.equals("concern")) { what = t("a claim's source has an expression of concern"); gist = c.detail(); }
            else if (ev.equals("revised")) { what = t("a claim's source was revised"); gist = t("A newer version of the paper behind it is out ({0}).", c.detail()); }
            else if (ev.startsWith("state:")) { String to = ev.substring(ev.lastIndexOf('→') + 1); what = to.equals("accepted") ? t("a claim was accepted") : to.equals("disputed") ? t("a claim was disputed") : to.equals("retired") ? t("a claim was retired") : t("a claim changed state"); gist = c.detail().isEmpty() ? ev.substring(6).replace("→", " → ") : c.detail(); }
            else if (ev.equals("edited")) { what = t("a claim was edited"); gist = c.detail(); }
            else { what = t("a claim changed"); gist = ev + (c.detail().isBlank() ? "" : ": " + c.detail()); }
            String title = c.id(); try { Finding f = store.finding(c.id()); if (f != null) title = f.title(); } catch (Exception ignored) { }
            rows.add(new Update(RunProgress.millis(c.at()), "/entry/" + enc(c.id()), title, what, esc(Acquisitions.compress(gist, 200)), "<a class=\"btn quiet\" href=\"/entry/" + enc(c.id()) + "\">" + t("Open") + "</a>", "change:" + c.id()));
        }
        // the keeper's: who asks to be let in; a recall notice that may concern something owned
        if (Patrons.mayWrite(store, patron)) {
            for (AccessRequests.Request r : AccessRequests.list(store)) if (r.state().equals("pending"))
                rows.add(new Update(RunProgress.millis(r.date().length() == 10 ? r.date() + "T00:00:00Z" : r.date()), "/inbox", r.name().isEmpty() ? r.did() : r.name(), t("asks to be let in"), esc(r.note()), "<a class=\"btn\" href=\"/inbox\">" + t("Answer") + "</a>", "ask:" + r.id()));
            List<Recalls.Hit> hits = Recalls.hits(store, true);
            if (!hits.isEmpty()) rows.add(new Update(now, "/owned", n(hits.size(), "{0} recall notice", "{0} recall notices"), t("may concern something you own"), t("Each can be put away on the Owned page."), "<a class=\"btn\" href=\"/owned\">" + t("See them") + "</a>", "recalls"));
        }
        rows.sort(Comparator.comparingLong(Update::atMs).reversed());
        return rows.size() > 100 ? new ArrayList<>(rows.subList(0, 100)) : rows;
    }

    /** The daemon's drive, for the pages that ask whether a model answers; set at each request. */
    static volatile Supplier<String> DRIVE_NOW = () -> Config.get("RESEARCHZOSHO_DRIVE", "http://localhost:8080");

    /** The file that says a model has answered this library once: from then on the chat is the home whatever the model does today. */
    static Path answeredMarker(LibraryStore store) { return store.root().resolve("catalog").resolve("model-answered"); }

    static final long[] DRIVE_UP = {0, 0};   // [answers: 1 or 0, the moment it was asked]

    /**
     * Whether a model has ever answered this library, or answers it now: the marker once a model answered; else the drive probed with a
     * short timeout, at most once a minute, a model still loading counting as answering, and a rented model with its key kept counting too.
     */
    static boolean modelAnswers(LibraryStore store) {
        if (Files.exists(answeredMarker(store))) return true;
        long now = System.currentTimeMillis();
        synchronized (DRIVE_UP) { if (now - DRIVE_UP[1] < 60_000) return DRIVE_UP[0] == 1; }
        boolean up;
        try { up = Crews.driveState(DRIVE_NOW.get(), Config.get("RESEARCHZOSHO_MODEL", "local-model"), Duration.ofSeconds(3)) != Crews.DriveState.DOWN; } catch (RuntimeException e) { up = false; }
        if (!up) for (ModelEntries.Entry e : ModelEntries.all().values()) if (e.hosted() && DriveClient.hostKey(e.host()) != null) { up = true; break; }
        if (up) modelAnswered(store);
        synchronized (DRIVE_UP) { DRIVE_UP[0] = up ? 1 : 0; DRIVE_UP[1] = now; }
        return up;
    }

    /** A model answered: written down once, so the chat is the home from now on. */
    static void modelAnswered(LibraryStore store) {
        try { Path f = answeredMarker(store); if (!Files.exists(f)) { Files.createDirectories(f.getParent()); Files.writeString(f, Instant.now().toString()); } } catch (IOException ignored) { }
    }

    static final String MODELS_PAGE = "https://researchzosho.org/models/";
    static final String[][] HOSTS = {{"anthropic", "Anthropic (Claude)"}, {"openai", "OpenAI"}, {"openrouter", "OpenRouter"}, {"gemini", "Gemini"}, {"deepseek", "DeepSeek"}, {"moonshot", "Moonshot (Kimi)"}, {"nebius", "Nebius Token Factory"}, {"huggingface", "Hugging Face"}, {"address", "Another OpenAI-style address"}};   // the last is a sentence: translated where it is shown

    /**
     * The first screen when no model answers, and the page at /connect: a model you own on this machine, found on the usual ports or
     * given by its address; one you rent, by host, key and model; a test of the connection that says what answered; a way to add
     * things while you choose; the models page; and Keep for the keeper. {@code result} is the last test's or change's word.
     */
    static String connectBlock(LibraryStore store, Patrons.Patron patron, String result, boolean look) {
        boolean keeper = mayWrite(store, patron);
        StringBuilder b = new StringBuilder("<div class=\"connect\"><p class=\"kicker\">").append(t("The first screen")).append("</p><h2 class=\"plain\">").append(t("Connect a model")).append("</h2>");
        b.append("<p class=\"lede\">").append(t("The library needs a model to answer and to research. There are two ways; pick one, test it, and you are in. Without a model it still takes files and finds them by words.")).append("</p>");
        if (!result.isEmpty()) b.append("<p class=\"notice\">").append(esc(result)).append("</p>");
        b.append("<div class=\"cards\"><div class=\"card\"><h2>").append(t("A model you own, on this machine")).append("</h2><p class=\"k\">").append(t("The library looks on the usual ports, as setup does: llama.cpp, Ollama, LM Studio.")).append("</p>");
        String drive = DRIVE_NOW.get();
        if (look) {
            List<String[]> found = localServers(drive);
            if (found.isEmpty()) b.append("<div class=\"nf\">").append(t("Nothing answers on the usual ports, nor at {0}.", esc(drive))).append("</div>");
            for (String[] f : found) {
                b.append("<div class=\"found\"><b>").append(t("Found:")).append("</b> ").append(esc(f[0])).append(f[1].isEmpty() ? "" : ", " + t("offering {0}", esc(f[1])));
                if (keeper) b.append(" <form class=\"inline\" method=\"post\" action=\"/connect\"><input type=\"hidden\" name=\"op\" value=\"use\"><input type=\"hidden\" name=\"address\" value=\"").append(esc(f[0])).append("\"><input type=\"hidden\" name=\"model\" value=\"").append(esc(f[2])).append("\"><button class=\"btn\">").append(t("Use it")).append("</button></form>");
                b.append("</div>");
            }
        } else b.append("<form class=\"inline\" method=\"post\" action=\"/connect\"><input type=\"hidden\" name=\"op\" value=\"look\"><button class=\"btn\">").append(t("Look on the usual ports")).append("</button></form>");
        if (keeper) b.append("<form method=\"post\" action=\"/connect\"><input type=\"hidden\" name=\"op\" value=\"use\"><p><label class=\"small k\">").append(t("Or an address")).append(" <input class=\"line\" name=\"address\" placeholder=\"http://127.0.0.1:8080\" value=\"").append(esc(drive)).append("\"></label></p><p><label class=\"small k\">").append(t("Its model, when it serves more than one")).append(" <input class=\"line\" name=\"model\" placeholder=\"").append(esc(t("the model's id, or leave empty"))).append("\"></label></p><p><button class=\"btn\">").append(t("Use this address")).append("</button> <button class=\"btn quiet\" name=\"op\" value=\"test\">").append(t("Test it")).append("</button></p></form>");
        b.append("<p class=\"small k3\">").append(t("No model yet? {0} prints the best choice for your graphics card and the command that serves it; so does {1}.", "<code>researchzosho models</code>", "<a href=\"" + MODELS_PAGE + "\">" + t("the models page") + "</a>")).append("</p></div>");
        b.append("<div class=\"card\"><h2>").append(t("One you rent")).append("</h2><p class=\"k\">").append(t("A key for a hosted drive; the key goes only to that host.")).append("</p>");
        if (keeper) {
            b.append("<form method=\"post\" action=\"/connect\"><input type=\"hidden\" name=\"op\" value=\"rented\"><p><label class=\"small k\">").append(t("Host")).append(" <select class=\"line\" name=\"host\">");
            for (String[] h : HOSTS) b.append("<option value=\"").append(h[0]).append("\">").append(esc(h[0].equals("address") ? t(h[1]) : h[1])).append("</option>");
            b.append("</select></label></p><p><label class=\"small k\">").append(t("Its address, for another OpenAI-style host")).append(" <input class=\"line\" name=\"address\" placeholder=\"https://…/v1\"></label></p>")
             .append("<p><label class=\"small k\">").append(t("Key")).append(" <input class=\"line\" type=\"password\" name=\"key\" autocomplete=\"off\"></label></p><p><label class=\"small k\">").append(t("Model")).append(" <input class=\"line\" name=\"model\" placeholder=\"").append(esc(t("the model's id at that host"))).append("\"></label></p>")
             .append("<p><button class=\"btn\">").append(t("Keep the key and test it")).append("</button></p></form>");
        } else b.append("<p class=\"k\">").append(t("The person who keeps this library adds one.")).append("</p>");
        b.append("<p class=\"small k3\">").append(t("A research run costs about a dollar and a half on a frontier model, cents on a small one; a cap a month can be set on each model in Keep.")).append("</p></div></div>");
        b.append("<div class=\"drop\"><p><b>").append(t("Add something while you choose.")).append("</b> ").append(t("Files, a folder, or a page: the library reads them now and finds them by words; the model, once connected, reads them for claims.")).append("</p><div class=\"btns\"><a class=\"btn\" href=\"/add\">").append(t("Add a file or a page")).append("</a></div></div>");
        b.append("<p class=\"models small k3\">").append(t("Which model to run, by your graphics card's memory: {0}.", "<a href=\"" + MODELS_PAGE + "\">" + t("the models page") + "</a>"));
        if (keeper) b.append(" ").append(t("Keeping this library, sources and their yield, keys and models, people and access, the tally and settings, is behind {0}.", "<a href=\"/keep\">" + t("Keep") + "</a>"));
        return b.append("</p></div>").toString();
    }

    /** The model servers answering on this machine's usual ports and at the configured drive: address, the models offered, the first of them; a few seconds at most. */
    static List<String[]> localServers(String drive) {
        List<String> tried = new ArrayList<>();
        if (drive != null && !drive.isBlank()) tried.add(drive);
        for (String p : Setup.LOCAL_PORTS) { String c = "http://localhost:" + p; if (!tried.contains(c)) tried.add(c); }
        Setup.Probe probe = Setup.liveProbe();
        List<CompletableFuture<String[]>> fs = new ArrayList<>();
        for (String base : tried) fs.add(CompletableFuture.supplyAsync(() -> {
            List<String> ids = probe.models(base, Setup.local(base) ? null : DriveClient.keyFor(base));
            return ids == null ? null : new String[]{base, String.join(", ", ids.subList(0, Math.min(5, ids.size()))) + (ids.size() > 5 ? ", …" : ""), ids.isEmpty() ? "" : ids.get(0)};
        }));
        List<String[]> out = new ArrayList<>();
        long until = System.currentTimeMillis() + 5_000;
        for (var f : fs) { try { String[] r = f.get(Math.max(1, until - System.currentTimeMillis()), TimeUnit.MILLISECONDS); if (r != null) out.add(r); } catch (Exception ignored) { } }
        return out;
    }

    /** /connect, GET: the screen; POST: look on the ports, use an address, test one, keep a rented host's key and add its model. */
    static String connect(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q, Map<String, String> form, boolean post) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        String result = q.getOrDefault("note", ""), op = post ? form.getOrDefault("op", "") : "";
        boolean look = "look".equals(op) || "1".equals(q.get("look"));
        if (post && !op.equals("look")) {
            Patrons.check(store, patron, Patrons.Level.write);
            String address = form.getOrDefault("address", "").strip(), model = form.getOrDefault("model", "").strip();
            switch (op) {
                case "test" -> result = testLine(address, model);
                case "use" -> {
                    if (address.isEmpty()) result = t("Say the address.");
                    else {
                        Map<String, String> set = new LinkedHashMap<>(); set.put("name", "RESEARCHZOSHO_DRIVE"); set.put("value", address);
                        String note = settingsPost(p, patron, set);
                        if (!model.isEmpty()) { set.put("name", "RESEARCHZOSHO_MODEL"); set.put("value", model); note = note + " " + settingsPost(p, patron, set); }
                        synchronized (DRIVE_UP) { DRIVE_UP[1] = 0; }
                        result = note + " " + testLine(address, model);
                    }
                }
                case "rented" -> {
                    String host = form.getOrDefault("host", "").strip(), key = form.getOrDefault("key", "").strip();
                    String driveWord = host.equals("address") ? address : host;
                    if (driveWord.isEmpty() || model.isEmpty()) result = t("Say the host and the model.");
                    else {
                        ObjectNode a = args(patron);
                        StringBuilder note = new StringBuilder();
                        if (!key.isEmpty()) { a.put("op", "key"); a.put("host", host.equals("address") ? DriveClient.hostOf(address) : host); a.put("value", key); note.append(p.models(a).path("note").asText()).append(' '); }
                        ObjectNode add = args(patron); add.put("op", "add"); add.put("name", (host.equals("address") ? DriveClient.hostOf(address) : host) + "-" + model.replaceAll("[^A-Za-z0-9.-]+", "-")); add.put("drive", driveWord); add.put("model", model);
                        try { note.append(p.models(add).path("note").asText()); } catch (ProtocolError e) { note.append(e.getMessage()); }
                        synchronized (DRIVE_UP) { DRIVE_UP[1] = 0; }
                        result = note + " " + testLine(ModelEntries.hostAddress(driveWord) == null ? driveWord : ModelEntries.hostAddress(driveWord), model);
                    }
                }
                default -> result = t("That is not a thing this page does.");
            }
        }
        if (post) return "/connect?note=" + enc(result) + (look ? "&look=1" : "");
        return page(store, patron, t("Connect a model"), connectBlock(store, patron, result, look), 0, null, "", true);
    }

    /** One call to a model server, a few seconds: what answered, and whether it can do the work. */
    static String testLine(String address, String model) {
        if (address == null || address.isBlank()) return t("Say the address to test.");
        long t0 = System.currentTimeMillis();
        Crews.DriveState st;
        try { st = Crews.driveState(address, model == null || model.isBlank() ? Config.get("RESEARCHZOSHO_MODEL", "local-model") : model, Duration.ofSeconds(20)); } catch (RuntimeException e) { st = Crews.DriveState.DOWN; }
        String took = took(System.currentTimeMillis() - t0);
        return st == Crews.DriveState.ANSWERS ? (model == null || model.isBlank() ? t("{0} answered in {1}: it can answer and research.", address, took) : t("{0} answered in {1} with {2}: it can answer and research.", address, took, model))
             : st == Crews.DriveState.STARTING ? t("{0} is starting; ask again in a moment.", address) : DriveClient.hostedApi(address) && DriveClient.keyFor(address) == null ? t("{0} does not answer: no key is kept for it.", address) : t("{0} does not answer.", address);
    }

    /** One fold of Keep: its heading, a gist on the fold, and what is inside. */
    static String fold(String id, String title, String gist, String in) {
        return "<details class=\"sec\" id=\"" + id + "\"><summary><h2>" + title + "</h2><span class=\"gist\">" + gist + "</span></summary><div class=\"in\">" + in + "</div></details>";
    }

    /**
     * /keep: the keeper's things behind one link, each behind a fold with its gist: the connection and the models, the sources and
     * their yield, people and access, the tally, the settings, and the rest of the keeper's pages. A reader is told the keeper looks
     * after these.
     */
    static String keep(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        if (!mayWrite(store, patron)) return page(store, patron, t("Keep"), "<p>" + t("Keep is the page of the person who keeps this library: the model it runs on, the sources it mines, who may read it, and its settings. You read this library; its keeper looks after those.") + "</p>");
        ObjectNode a = M.createObjectNode(); a.put("op", "list"); a.set("patron", LibrarianDaemon.patronNode(patron));
        JsonNode r = p.settings(a);
        int set = 0, n = 0; for (JsonNode s : r.path("settings")) { n++; if (!s.path("value").asText("").isEmpty()) set++; }
        Map<String, ModelEntries.Entry> entries = ModelEntries.all(); int hosted = 0; for (ModelEntries.Entry e : entries.values()) if (e.hosted()) hosted++;
        ObjectNode sum = Spend.summary(store); double month = 0; for (JsonNode row : sum.path("hosts")) month += row.path("month").asDouble();
        int sources = Sources.all(store).size();
        Patrons.Policy pol = Patrons.load(store); int people = pol.listed().size();
        boolean yours = patron.person() || (patron.web() && !WebAccess.signInRequired());
        StringBuilder b = new StringBuilder("<p class=\"lede\">").append(t("What the person who keeps the library looks after, behind this one link.")).append("</p>");
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"notice\">").append(esc(note)).append("</p>");
        String refused = OutsideServices.refusedNotice(store, Chrome.now());   // a key the last check refused is said at the top, until the next check
        if (!refused.isEmpty()) b.append("<p class=\"notice\">").append(esc(refused)).append("</p>");
        if (!WebAccess.signInRequired()) b.append("<p class=\"notice\">").append(t("Access is open. To require sign-in, run {0}.", "<code>" + esc(WebAccess.OPEN_COMMAND) + "</code>")).append("</p>");
        b.append(fold("connection", t("Connection and models"), n(entries.size(), "{0} model named", "{0} models named") + (hosted > 0 ? ", " + t("{0} rented", hosted) : "") + " · " + t("the drive at {0}", esc(DRIVE_NOW.get())),
                modelsSection(store).replace("<h2>" + t("Models") + "</h2>", "") + "<p><a class=\"btn\" href=\"/connect\">" + t("Connect another, or test one") + "</a> <a class=\"k\" href=\"" + MODELS_PAGE + "\">" + t("the models page") + "</a></p>"));
        b.append(fold("sources", t("Sources and their yield"), n(sources, "{0} source", "{0} sources"), SourcesPage.body(store, true, q)));
        if (yours) b.append(fold("mcp", t("Other MCP servers"), McpPage.gist(store), McpPage.section(store)));   // a server may be a program this computer starts: the keeper's alone
        b.append(fold("people", t("People and access"), n(people, "{0} person listed", "{0} people listed") + "; " + t("anyone else may {0}", esc(t(String.valueOf(pol.dflt())))), readersSection(store, patron, q).replace("<h2>" + t("Readers") + "</h2>", "")));
        List<ApiKeys.Key> keys = ApiKeys.list(store, ApiKeysPage.owner(patron) == null ? "" : ApiKeysPage.owner(patron));
        b.append(fold("keys", t("Keys for programs"), keys.isEmpty() ? t("none yet") : n(keys.size(), "{0} key", "{0} keys"), ApiKeysPage.section(store, patron, q, "/settings")));
        b.append(fold("tally", t("The tally"), sum.path("hosts").isEmpty() ? t("nothing spent") : t("{0} this month, at list prices", Spend.dollars(month)),
                sum.path("hosts").isEmpty() ? "<p class=\"k\">" + t("Nothing spent yet: the runs so far were on a model of your own, or none has run. A rented model's runs are counted here at its list prices.") + "</p>" : spendSection(store).replace("<h2>" + t("Spend") + "</h2>", "")));
        b.append(fold("settings", t("Settings"), t("{0} of {1} set", set, n), "<p class=\"k\">" + t("From {0}.", esc(r.path("config_file").asText())) + " " + t("A change takes effect at once, except where a setting says otherwise. A key is shown only as set or not.") + "</p>" + settingsForm(r)));
        b.append(fold("keys", t("Sources and keys"), keysGist(store), keysSection(store)));
        b.append(fold("translate", t("Translate a document"), translateGist(store), translateForm(store)));
        b.append(fold("more", t("Also"), t("runs, what you own, your account, the guide"),
                "<ul class=\"tight\"><li><a href=\"/jobs\">" + t("Runs") + "</a>: " + t("what runs now, what waits, what finished, and what the housekeeping will do tonight.") + "</li>"
                + (yours ? "<li><a href=\"/owned\">" + t("Owned") + "</a>: " + t("what you own, and the recall notices that may concern it.") + "</li>" : "")
                + (SignIn.hasAccountPage(patron) ? "<li><a href=\"/account\">" + t("Account") + "</a>: " + t("your name, your password and your passkeys.") + "</li>" : "")
                + "<li><a href=\"/help\">" + t("Help") + "</a>: " + t("the guide, searched.") + "</li><li><a href=\"/changes\">" + t("Changes") + "</a>: " + t("everything that changed on the shelves, in order.") + "</li><li><a href=\"/map\">" + t("The map") + "</a>: " + t("how the people, places and things in this library connect.") + "</li>"
                + "<li>" + t("To open the library in Obsidian or SoloMD, run {0} and open the folder {1} next to the library.", "<code>researchzosho vault</code>", "<code>" + esc(store.root().getFileName().toString()) + "-vault</code>") + "</li></ul>"));
        return page(store, patron, t("Keep"), b.toString(), 0, null, true);
    }

    /** The glossary's size, for the fold's gist. */
    static String translateGist(LibraryStore store) {
        int n = Glossary.list(store).size();
        return n == 0 ? t("txt, md, pdf, docx, epub; quick or checked") : n(n, "{0} glossary entry", "{0} glossary entries") + "; " + t("txt, md, pdf, docx, epub; quick or checked");
    }

    /** The Translate form: a file, the language, the format out, the tier; the glossary beneath, with a line to add to it. */
    static String translateForm(LibraryStore store) {
        StringBuilder b = new StringBuilder("<p class=\"k\">").append(t("A document of your own (txt, md, pdf, docx or epub) translated into another language, unit by unit: headings, paragraphs, list items, table cells, captions. The structure is kept; the layout is not. Checked, the default, is one pass with the program's checks and scores and a revision of what they flag; quick is the one pass alone; reviewed adds a second reading of the flagged units and a sample of the rest; thorough reads every unit. What the checks still doubt is listed in the note beside the translation, never in its text; the box below puts it at the end of the document too, numbered. The time is said when the run is filed; the run's page has the file, its note and where both are on the shelves.")).append("</p>");
        b.append("<p class=\"k\">").append(t("A comic is translated too (manga, manhwa, manhua, a webtoon, a comic book, a graphic novel, a bande dessinée): a CBZ, a CBR or a ZIP of page pictures, or a PDF you say is one, up to {0}. Its pages are read by the comic helper, on the library's machine or on the machine that serves its heavy helpers, in Japanese, Chinese, Korean or a language of the Latin, Cyrillic or Greek alphabet, and the translation is set into the speech balloons in any of those. The helper finds the language of the lettering on a sample of the pages; the choice below names it when it finds another.", (Uploads.translateLimit() >> 20) + " MB")).append("</p>");
        b.append("<form method=\"post\" action=\"/translate\" enctype=\"multipart/form-data\" class=\"stack\"><input type=\"file\" name=\"file\" required aria-label=\"").append(esc(t("the file to translate"))).append("\"> ");
        b.append("<select name=\"to\" aria-label=\"").append(esc(t("the language"))).append("\">");
        for (var e : new TreeMap<>(Translate.LANGUAGES).entrySet()) b.append("<option value=\"").append(e.getKey()).append("\"").append(e.getKey().equals("ja") ? " selected" : "").append(">").append(esc(Chrome.now().languageLabel(e.getKey()))).append("</option>");
        // the language the file is written in, or a comic is lettered in: found by the program unless the person names it here
        b.append("</select> <select name=\"from\" aria-label=\"").append(esc(t("the language it is written in"))).append("\"><option value=\"\">").append(t("its own language is found by itself")).append("</option>");
        for (var e : new TreeMap<>(Translate.LANGUAGES).entrySet()) b.append("<option value=\"").append(e.getKey()).append("\">").append(esc(t("it is written in {0}", Chrome.now().languageLabel(e.getKey())))).append("</option>");
        // the way a comic's pages are read: found from the pages unless the person says it (an edition of a manga in another language reads from the right)
        b.append("</select> <select name=\"reads\" aria-label=\"").append(esc(t("the way its pages are read"))).append("\"><option value=\"\">").append(t("which way it reads is found from the pages")).append("</option><option value=\"rtl\">").append(t("it reads from right to left (a manga)")).append("</option><option value=\"ltr\">").append(t("it reads from left to right")).append("</option>");
        b.append("</select> <select name=\"as\" aria-label=\"").append(esc(t("the format"))).append("\"><option value=\"\">").append(t("the same format")).append("</option><option value=\"md\">Markdown</option><option value=\"pdf\">PDF</option><option value=\"docx\">Word</option><option value=\"epub\">EPUB</option><option value=\"txt\">").append(t("plain text")).append("</option><option value=\"cbz\">CBZ</option></select> ");
        b.append("<select name=\"comic\" aria-label=\"").append(esc(t("whether the file is a comic"))).append("\"><option value=\"\">").append(t("a comic is recognised by itself")).append("</option><option value=\"on\">").append(t("this file is a comic (manga, a webtoon, a graphic novel)")).append("</option><option value=\"off\">").append(t("this file is a document")).append("</option></select> ");
        b.append("<select name=\"quality\" aria-label=\"").append(esc(t("the tier"))).append("\"><option value=\"checked\" selected>").append(t("checked (the default)")).append("</option><option value=\"quick\">").append(t("quick")).append("</option><option value=\"reviewed\">").append(t("reviewed")).append("</option><option value=\"thorough\">").append(t("thorough")).append("</option></select> ");
        List<ModelEntries.Entry> models = new ArrayList<>(); for (ModelEntries.Entry e : ModelEntries.all().values()) if (!e.name().equals(ModelEntries.DEFAULT)) models.add(e);
        if (!models.isEmpty()) {
            b.append("<select name=\"model\" aria-label=\"").append(esc(t("the translator"))).append("\"><option value=\"\">").append(t("translated by the library's model")).append("</option>");
            for (ModelEntries.Entry e : models) b.append("<option value=\"").append(esc(e.name())).append("\">").append(esc(t("translated by {0}", e.name() + (e.hosted() ? " (" + t("hosted, paid") + (ModelEntries.keyKept(e) ? "" : "; " + t("no key kept yet")) + ")" : "")))).append("</option>");
            b.append("</select> ");
        }
        b.append("<select name=\"judge\" aria-label=\"").append(esc(t("the checker"))).append("\"><option value=\"\">").append(t("checked by the library's judge seat")).append("</option>");
        if (!models.isEmpty()) b.append("<option value=\"auto\">").append(t("checked by the strongest other model (auto)")).append("</option>");
        for (ModelEntries.Entry e : models) b.append("<option value=\"").append(esc(e.name())).append("\">").append(esc(t("checked by {0}", e.name() + (e.hosted() ? " (" + t("hosted, paid") + (ModelEntries.keyKept(e) ? "" : "; " + t("no key kept yet")) + ")" : "")))).append("</option>");
        b.append("</select> <label><input type=\"checkbox\" name=\"back\" value=\"on\"> ").append(t("translate a sample back and compare (on by itself when one model checks its own work)")).append("</label> ");
        // the notes at the end: ticked when the setting makes them the default; the hidden field says the box was on the form, so an unticked box is an off
        b.append("<input type=\"hidden\" name=\"notes_box\" value=\"1\"><label><input type=\"checkbox\" name=\"notes\" value=\"end\"").append(Translation.NOTES_END.equals(Translation.notes("")) ? " checked" : "").append("> ").append(t(Translation.NOTES_BOX)).append("</label> ");
        if (models.stream().anyMatch(e -> !ModelEntries.keyKept(e))) b.append("<small class=\"k\">").append(t("A hosted model runs on its host's key; one without it is listed but no run uses it. The Settings page keeps keys under Models.")).append("</small> ");
        // the person's direction for the voice, in their own words: told to the translator, the reviser and the reader
        b.append("<input name=\"direction\" maxlength=\"").append(Translation.DIRECTION_MAX).append("\" size=\"48\" placeholder=\"").append(esc(t("a direction for its voice, in your words (optional): as if you were Shakespeare"))).append("\" aria-label=\"").append(esc(t("the direction"))).append("\"> ");
        b.append("<input name=\"collection\" placeholder=\"").append(esc(t("a collection to shelve it under (optional)"))).append("\" aria-label=\"").append(esc(t("the collection"))).append("\"> <button>").append(t("Translate the file")).append("</button></form>");
        b.append(scorersLine());   // after the form, never inside it: a form inside a form ends the outer one in every browser, and the button after it then belongs to no form and does nothing
        b.append(comicLine());   // the same: a form of its own, after the translate form
        b.append("<h3>").append(t("Glossary")).append("</h3><p class=\"k\">").append(t("A term and the rendering chosen for it in a language, used in every translation into that language and listed in each translation's note.")).append("</p>");
        List<Glossary.Entry> entries = Glossary.list(store);
        if (!entries.isEmpty()) { b.append("<ul class=\"tight\">"); for (Glossary.Entry e : entries) b.append("<li>").append(esc(e.term())).append(" → ").append(esc(e.rendering())).append(" <span class=\"k\">(").append(esc(ReadIn.name(e.lang()))).append(")</span> <form method=\"post\" action=\"/translate\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"glossary-remove\"><input type=\"hidden\" name=\"term\" value=\"").append(esc(e.term())).append("\"><input type=\"hidden\" name=\"lang\" value=\"").append(esc(e.lang())).append("\"><button>").append(t("remove")).append("</button></form></li>"); b.append("</ul>"); }
        b.append("<form method=\"post\" action=\"/translate\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"glossary-add\"><input name=\"term\" placeholder=\"").append(esc(t("the term, as it stands in the source"))).append("\" required aria-label=\"").append(esc(t("the term"))).append("\"> <select name=\"lang\" aria-label=\"").append(esc(t("the language"))).append("\">");
        for (var e : new TreeMap<>(Translate.LANGUAGES).entrySet()) b.append("<option value=\"").append(e.getKey()).append("\"").append(e.getKey().equals("ja") ? " selected" : "").append(">").append(esc(Chrome.now().languageLabel(e.getKey()))).append("</option>");
        b.append("</select> <input name=\"rendering\" placeholder=\"").append(esc(t("its rendering"))).append("\" required aria-label=\"").append(esc(t("the rendering"))).append("\"> <button>").append(t("Add to the glossary")).append("</button></form>");
        return b.toString();
    }

    /** The Translate form's post: the uploaded file kept under the library, the plan made, the job filed; or a glossary entry added or removed. */
    /**
     * The Translate fold's line on the scorers: both there, or what is missing on this machine with the button that sets it up, or the
     * setup running. It is a form of its own and stands after the translate form; a scorer that a helpers machine or an address serves is
     * not missing ({@link TranslationScores#missingSaid}).
     */
    static String scorersLine() {
        if (TranslationScores.setupRunning()) return "<p class=\"k\">" + esc(t("LaBSE and MetricX-24 are being set up in the background; the plan says which are there when a run is filed.")) + "</p>";
        String missing = TranslationScores.missingSaid(Chrome.now());
        if (missing.isEmpty() || !TranslationScores.setupAllowed() || !Helpers.localAllowed()) return "";   // nothing is set up on a machine that runs no heavy helper itself (local_helpers off)
        return "<form method=\"post\" action=\"/translate\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"scorers\"><span class=\"k\">" + esc(t("A checked translation scores every line with LaBSE and MetricX-24; {0} not set up yet.", missing))
                + "</span> <button>" + t("Set them up now") + "</button></form>";
    }

    /**
     * The Translate fold's line on the comic helper: nothing where it is set up or where this machine may not run it (the helper is off,
     * or the machine runs no heavy helper: {@link ComicHelper#barred}); else what a set-up fetches, in the three sentences every surface
     * says, with the button that sets it up; or that a set-up runs. A form of its own, after the translate form.
     */
    static String comicLine() {
        if (ComicHelper.setupRunning()) return "<p class=\"k\">" + esc(t("The comic helper is being set up in the background; a comic can be translated when it is done.")) + "</p>";
        Chrome c = Chrome.now();
        if (ComicHelper.readyOrQuiet() || !ComicHelper.barred(c).isEmpty()) return "";   // set up, or set up by an earlier release and short only of what the next comic fetches without a question
        return "<form method=\"post\" action=\"/translate\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"comic-helper\"><span class=\"k\">" + esc(ComicHelper.questionSaid(c)) + "</span> <button>" + t("Set the comic helper up") + "</button></form>";
    }

    /** The fields of the Translate form that say how a file is translated: carried by the button that sets the comic helper up and translates the comic that was just sent. */
    static final List<String> TRANSLATE_CHOICES = List.of("to", "from", "reads", "as", "quality", "model", "judge", "back", "notes_box", "notes", "direction", "collection", "comic");

    /**
     * A comic sent where the comic helper is not set up: what a set-up fetches, and one button that says yes to it and translates the
     * comic. The file is kept under the library already; the button names it by its name there and carries the form's choices.
     */
    static String comicOffer(Path kept, Map<String, String> form) {
        StringBuilder b = new StringBuilder("<form method=\"post\" action=\"/translate\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"comic-go\"><input type=\"hidden\" name=\"kept\" value=\"").append(esc(kept.getFileName().toString())).append("\">");
        for (String k : TRANSLATE_CHOICES) if (form.containsKey(k)) b.append("<input type=\"hidden\" name=\"").append(k).append("\" value=\"").append(esc(form.get(k))).append("\">");
        return b.append("<span>").append(esc(ComicHelper.questionSaid(Chrome.now()))).append("</span> <button>").append(t("Set it up and translate the comic")).append("</button></form>").toString();
    }

    static String translatePost(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        try {
            if (form.containsKey(Uploads.REFUSED)) return "<p class=\"notice\">" + esc(form.get(Uploads.REFUSED)) + "</p>" + translateForm(store);   // a file larger than the form takes: nothing of it was kept
            String op = form.getOrDefault("op", "");
            if (op.equals("comic-helper")) {
                String r = ComicHelper.setupInBackground();
                String said = !r.isEmpty() ? t("The comic helper is being set up in the background; a comic can be translated when it is done.") : ComicHelper.usable() ? t("The comic helper is set up.") : ComicHelper.barred(Chrome.now());   // set up here, or on the machine that serves it
                return "<p class=\"notice\">" + esc(said) + "</p>" + translateForm(store);
            }
            if (op.equals("comic-go")) {   // the yes to the set-up, for the comic sent a moment ago: named by its name under the library's own folder, never by a path
                String name = form.getOrDefault("kept", "");
                Path kept = name.isEmpty() || name.contains("/") || name.contains("\\") || name.startsWith(".") ? null : Translation.inbox(store).resolve(name);
                if (kept == null || !Files.isRegularFile(kept)) return "<p class=\"notice\">" + esc(t("That file is no longer kept here; choose it again.")) + "</p>" + translateForm(store);
                return translateFiled(store, patron, kept, name, form, true);
            }
            if (op.equals("scorers")) { String r = TranslationScores.setupInBackground("asked from the Translate fold"); return "<p class=\"notice\">" + esc(r.isEmpty() ? t("Both scorers are set up.") : r) + "</p>" + translateForm(store); }
            if (op.equals("glossary-add")) { try { return "<p class=\"notice\">" + esc(Glossary.add(store, form.getOrDefault("term", ""), form.getOrDefault("lang", ""), form.getOrDefault("rendering", ""))) + "</p>" + translateForm(store); } catch (ProtocolError e) { return "<p class=\"notice\">" + esc(e.getMessage()) + "</p>" + translateForm(store); } }
            if (op.equals("glossary-remove")) { Glossary.remove(store, form.getOrDefault("term", ""), form.getOrDefault("lang", "")); return "<p class=\"notice\">" + esc(t("Removed from the glossary.")) + "</p>" + translateForm(store); }
            List<Uploads.Uploaded> files = Uploads.files(form, "file");
            if (files.isEmpty()) return "<p>" + t("Choose a file to translate.") + "</p>" + translateForm(store);
            String name = files.get(0).name();
            return translateFiled(store, patron, Translation.keepCopy(store, files.get(0).path(), name), name, form, false);
        } finally { Uploads.drop(form); }
    }

    /**
     * A file kept under the library planned and filed as a translation, with the form's choices; the notice it comes to, and the form
     * again. {@code setup}: the person's yes to setting the comic helper up, which the job then does first.
     */
    static String translateFiled(LibraryStore store, Patrons.Patron patron, Path kept, String name, Map<String, String> form, boolean setup) throws IOException {
        String notes = form.containsKey("notes_box") ? (form.containsKey("notes") ? Translation.NOTES_END : Translation.NOTES_OFF) : form.getOrDefault("notes", "");   // the box as the person left it: ticked, end; unticked, off; no box on the form, the setting's
        if (Translation.notes(notes) == null) return "<p class=\"notice\">" + esc(t(Translation.NOTES_SAID)) + "</p>" + translateForm(store);   // a post of a program's own with a word that is neither
        String comic = form.getOrDefault("comic", "").strip();
        if (!comic.isEmpty() && !comic.equals("on") && !comic.equals("off")) comic = "";
        String from = form.getOrDefault("from", "").strip();   // the language the person says the file is in; blank for what the program finds
        String reads = ComicTranslation.readsWord(form.getOrDefault("reads", ""));   // the way they say a comic's pages are read; blank, or a word that is neither, for what the program finds
        if (reads == null || reads.equals(ComicTranslation.READS_MANGA)) reads = "";
        String to = form.getOrDefault("to", "ja"), as = form.getOrDefault("as", ""), quality = form.getOrDefault("quality", Translation.DEFAULT), collection = form.getOrDefault("collection", "").strip(), judge = form.getOrDefault("judge", "").strip(), model = form.getOrDefault("model", "").strip(), back = form.containsKey("back") ? "on" : "";
        if (!Translation.anyModel()) return "<p class=\"notice\">" + esc(t(Translation.NO_MODEL)) + "</p>" + translateForm(store);
        Translation.Plan plan;
        try { plan = Translation.plan(store, kept, to, as, quality, Translation.configuredSeat()[1], model, judge, from, comic, reads).withDirection(Translation.direction(form.getOrDefault("direction", ""))); }
        catch (Translation.Refused e) { return "<p class=\"notice\">" + esc(e.in(Chrome.now())) + "</p>" + translateForm(store); }
        catch (IOException e) { return "<p class=\"notice\">" + esc(e.getMessage()) + "</p>" + translateForm(store); }
        boolean isComic = plan.comic() != null;
        // a comic where the helper is not set up: nothing is filed; the notice says what a set-up fetches and its button says yes and translates this comic
        if (isComic && plan.comic().helper().equals(ComicTranslation.NEEDS) && !setup) return "<p class=\"notice\">" + esc(t("{0} is a comic, and it is kept here; it is not translated yet.", kept.getFileName().toString())) + "</p>" + comicOffer(kept, form) + translateForm(store);
        ObjectNode filed = Translation.args(kept, name, plan, null, collection, "web", judge, model, back, notes, from);
        if (isComic && setup && plan.comic().helper().equals(ComicTranslation.NEEDS)) filed.put("comic_setup", "yes");
        if (plan.reading() != null) filed.set("pdf_preflight", plan.reading().json());
        String id = new Jobs(store, j -> "").submit(Translation.JOB_KIND, patron == null ? "" : patron.did(), filed);
        String who = TranslationWho.said(TranslationWho.facts(Translation.JOB_DRAFT.apply(filed)), Chrome.now(), false, !TranslationWho.planSaysScores(plan, Chrome.now()));   // who translates, who reads, where the scores are made: as the run's page says it, a hosted model's cost said in the plan above
        String read = plan.readingSaid(Chrome.now());
        Translation.Plan bare = plan.withDirection("");   // the direction is said once, at the end of the run's description
        return "<p><b>" + t("Filed as {0}.", "<a href=\"/jobs/" + enc(id) + "\">" + esc(id) + "</a>") + "</b> " + (read.isEmpty() ? "" : esc(read) + " ") + esc(isComic ? t("{0}: {1}, {2}. Into {3} as {4}, {5}: {6}.", kept.getFileName().toString(), n(plan.comic().pages(), "a comic of {0} page", "a comic of {0} pages"), plan.comic().lettering(Chrome.now()), languageName(plan.to()), plan.as(), t(plan.quality()), bare.saidFor(Chrome.now()))
                : t("{0}: {1} units with words, {2} characters, written in {3}. Into {4} as {5}, {6}: {7}.", kept.getFileName().toString(), plan.units(), Chrome.now().number(plan.chars()), languageName(plan.from()), languageName(plan.to()), plan.as(), t(plan.quality()), bare.saidFor(Chrome.now())))
                + " " + esc(t("All four tiers: {0}.", bare.timesSaid(Chrome.now()))) + " " + esc(who) + " " + t("The run's page shows its progress and, when it is done, the file, its note and where both are on the shelves.") + "</p>" + translateForm(store);
    }

    /** The check's outcome in a sentence, for a page's notice and the commands. */
    static String keysChecked(List<OutsideServices.Result> rs) {
        if (rs.isEmpty()) return t("No key is set, so there was nothing to check.");
        int ok = 0, bad = 0, unsure = 0; List<String> refused = new ArrayList<>();
        for (OutsideServices.Result r : rs) { if (r.accepted() == null) unsure++; else if (r.accepted()) ok++; else { bad++; refused.add(r.service()); } }
        return n(rs.size(), "{0} key checked.", "{0} keys checked.") + " " + t("Accepted: {0}.", ok) + (bad > 0 ? " " + t("Refused: {0} ({1}).", bad, String.join(", ", refused)) : "") + (unsure > 0 ? " " + t("The service did not say: {0}.", unsure) : "") + " " + t("Each line is in the table.");
    }

    static String keysGist(LibraryStore store) {
        int set = 0, n = 0, bad = 0;
        for (OutsideServices.Row r : OutsideServices.rows(store)) { n++; if (r.set()) set++; if (r.last() != null && r.last().accepted() != null && !r.last().accepted()) bad++; }
        return t("{0} of {1} keys set", set, n) + (bad > 0 ? ", " + t("{0} refused at the last check", bad) : "");
    }

    /**
     * The outside services the library can use with a key, one row each: what it adds, free or paid with the allowance and the day it was
     * read, whether the key is set, this month's use from the tally, what the last check said, and where a key comes from. One button
     * asks every service with a key whether it is accepted and what is left.
     */
    static String keysSection(LibraryStore store) {
        StringBuilder b = new StringBuilder("<p class=\"k\">").append(t("Every outside service the library can use with a key, and what each gives. Prices and allowances are read off the services' own pages, each on the day shown; the use is this month's count from the tally. A key is set on the Settings page; the link is where one comes from.")).append("</p>");
        b.append("<form method=\"post\" action=\"/settings\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"check_keys\"><button class=\"btn\">").append(t("Check the keys now")).append("</button> <span class=\"k3\">").append(t("one small call to each service with a key; a minute or two")).append("</span></form>");
        b.append("<div class=\"tablewrap\"><table class=\"stack keys\"><tr><th>").append(t("Service")).append("</th><th>").append(t("What it adds")).append("</th><th>").append(t("Free or paid, allowance, read on")).append("</th><th>").append(t("Key")).append("</th><th>").append(t("This month")).append("</th><th>").append(t("Last check")).append("</th><th>").append(t("Get a key")).append("</th></tr>");
        for (OutsideServices.Row r : OutsideServices.rows(store)) {
            OutsideServices.Service s = r.service();
            String last = r.last() == null ? "" : OutsideServices.lastSaid(r);
            b.append("<tr><td data-l=\"" + esc(t("Service")) + "\"><b>").append(esc(OutsideServices.nameSaid(s, Chrome.now()))).append("</b></td><td data-l=\"" + esc(t("What it adds")) + "\">").append(esc(s.adds())).append("</td><td data-l=\"" + esc(t("Free or paid, allowance, read on")) + "\">").append(esc(s.terms())).append(" <span class=\"k\">(").append(t("read {0}", esc(Chrome.now().day(s.checkedOn())))).append(")</span></td>")
             .append("<td data-l=\"" + esc(t("Key")) + "\">").append(r.set() ? t("set") : "<span class=\"k\">" + t("not set") + "</span>").append("</td><td data-l=\"" + esc(t("This month")) + "\">").append(esc(OutsideServices.use(r, Chrome.now()))).append("</td>")
             .append("<td data-l=\"" + esc(t("Last check")) + "\"").append(r.last() != null && r.last().accepted() != null && !r.last().accepted() ? " class=\"bad\"" : "").append(">").append(last.isEmpty() ? "<span class=\"k\">" + t("not checked") + "</span>" : esc(last)).append("</td>")
             .append("<td data-l=\"" + esc(t("Get a key")) + "\"><a href=\"").append(esc(s.link())).append("\" rel=\"noopener\">").append(esc(s.link().replaceFirst("^https?://", "").replaceFirst("/$", ""))).append("</a> <span class=\"k\">→ ").append(esc(String.join(", ", s.allSettings()))).append("</span></td></tr>");
        }
        return b.append("</table></div>").toString();
    }

    /** /suggest: what the library suggests the person do next, now first, each with its reason, its action and its cost; a mark teaches the library. */
    static String suggestPage(LibraryStore store, Patrons.Patron patron) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        List<Suggestions.Entry> open = Suggestions.refresh(store, null), all = Suggestions.all(store);
        boolean writer = mayWrite(store, patron);
        StringBuilder b = new StringBuilder("<h1>").append(t("Suggested")).append("</h1><p class=\"k\">").append(t("What the shelves' own events suggest you do next: a forecast due, a story behind the shelves, a question of yours with an answer waiting, a report that landed, a dossier gone stale. Now first, then the rest by how likely you are to take it up. A mark teaches the library.")).append("</p>");
        if (open.isEmpty()) b.append("<p>").append(t("Nothing to suggest right now.")).append("</p>");
        else {
            b.append("<table><tr><th>").append(t("Id")).append("</th><th>").append(t("When")).append("</th><th>").append(t("Suggestion")).append("</th><th>").append(t("Why")).append("</th><th>").append(t("Do")).append("</th><th>").append(t("Costs")).append("</th>").append(writer ? "<th>" + t("Mark") + "</th>" : "").append("</tr>");
            for (Suggestions.Entry e : open) {
                b.append("<tr><td>").append(esc(e.id())).append("</td><td><b>").append(esc(e.klass())).append("</b>").append(e.pTake() == null ? "" : " <span class=\"k\">" + Math.round(e.pTake() * 100) + "%</span>").append("</td><td>").append(withIdLinks(esc(e.text()))).append("</td><td class=\"k\">").append(esc(e.reason()))
                 .append("</td><td><code>").append(esc(e.action())).append("</code></td><td class=\"k\">").append(esc(e.cost())).append("</td>");
                if (writer) b.append("<td><form method=\"post\" action=\"/suggest\" style=\"display:inline\"><input type=\"hidden\" name=\"id\" value=\"").append(esc(e.id())).append("\"><button name=\"outcome\" value=\"taken\">").append(t("taken")).append("</button> <button name=\"outcome\" value=\"later\">").append(t("later")).append("</button> <button name=\"outcome\" value=\"dismissed\">").append(t("dismiss")).append("</button></form></td>");
                b.append("</tr>");
            }
            b.append("</table>");
        }
        Double brier = Suggestions.brier(all);
        if (brier != null) b.append("<p class=\"k\">").append(t("Brier score over your marks: {0} (0 is perfect, 0.25 is coin-tossing).", Chrome.now().decimal(brier, 3))).append("</p>");
        b.append("<p class=\"k\">").append(t("At the command line: researchzosho suggest · in the chat: /suggest · the nightly housekeeping classes new suggestions with the model's typed judge; this page uses the rules.")).append("</p>");
        return page(store, patron, t("Suggested"), b.toString());
    }

    private static String search(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        String query = q.getOrDefault("q", "").strip();
        String subject = q.getOrDefault("subject", "").strip();
        StringBuilder b = new StringBuilder();
        b.append("<form class=\"big\" action=\"/search\"><input name=\"q\" value=\"").append(esc(query)).append("\" placeholder=\"").append(esc(t("Words, a title, a name…"))).append("\">");
        if (!subject.isEmpty()) b.append("<input type=\"hidden\" name=\"subject\" value=\"").append(esc(subject)).append("\">");
        b.append(" <button>").append(t("Search")).append("</button></form>");
        if (!subject.isEmpty()) b.append("<p class=\"k\">").append(t("in the subject {0}", "<b>" + esc(subject) + "</b>")).append(" <a href=\"/search?q=").append(enc(query)).append("\">(").append(t("everything")).append(")</a></p>");
        if (query.isEmpty() && subject.isEmpty()) return page(store, patron, t("Search"), b.toString());
        ObjectNode a = args(patron);
        a.put("query", query.isEmpty() ? subject.replace("--", " ") : query);
        a.put("k", 25);
        if (!subject.isEmpty()) a.put("subject", subject);
        if (q.containsKey("cursor")) a.put("cursor", q.get("cursor"));
        ObjectNode r = p.search(a);
        if (r.path("hits").size() == 0) b.append("<p>").append(t("Nothing in the library matches these words.")).append(" <a href=\"/research?q=").append(enc(query)).append("\">").append(t("Research this question")).append("</a>.</p>");
        else {
            b.append("<ol class=\"hits\">");
            for (JsonNode h : r.path("hits")) b.append("<li>").append(hitLine(h)).append("</li>");
            b.append("</ol>");
            if (r.hasNonNull("next_cursor")) b.append("<p><a href=\"/search?q=").append(enc(query)).append("&subject=").append(enc(subject)).append("&cursor=").append(enc(r.get("next_cursor").asText())).append("\">").append(t("More")).append("</a></p>");
        }
        return page(store, patron, t("Search"), b.toString());
    }

    private static String ask(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        String question = q.getOrDefault("q", "").strip();
        StringBuilder b = new StringBuilder();
        b.append("<form class=\"big\" action=\"/ask\"><input name=\"q\" value=\"").append(esc(question)).append("\" placeholder=\"").append(esc(t("Ask the library a question…"))).append("\"> <button>").append(t("Ask")).append("</button></form>");
        if (question.isEmpty()) return page(store, patron, t("Ask"), b.toString());
        ObjectNode a = args(patron); a.put("question", question); a.put("k", 8); a.put("peers", "none");
        ObjectNode r = p.ask(a);
        if (r.path("holds_nothing").asBoolean()) {
            b.append("<p>").append(t("The library has nothing on this yet."));
            if (r.path("filed_as_demand").asBoolean()) b.append(" ").append(t("The question was added to the open questions."));
            b.append(" <a href=\"/research?q=").append(enc(question)).append("\">").append(t("Research this question")).append("</a>.</p>");
        } else {
            b.append("<p class=\"k\">").append(t("What the library has on this, best match first. Each one lists its sources; open it to see them.")).append("</p>");
            for (JsonNode e : r.path("entries")) b.append(entryCard(e));
        }
        if (r.path("open_threads").size() > 0) {
            b.append("<h2>").append(t("Open questions about this")).append("</h2><ul class=\"tight\">");
            for (JsonNode th : r.path("open_threads")) b.append("<li>").append(esc(th.path("text").asText())).append(" <span class=\"k\">").append(esc(Chrome.now().day(th.path("date").asText()))).append("</span></li>");
            b.append("</ul>");
        }
        return page(store, patron, t("Ask"), b.toString());
    }

    static String entry(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, String id) throws IOException { return entry(store, p, patron, id, Map.of()); }

    static String entry(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, String id, Map<String, String> q) throws IOException { return entry(null, store, p, patron, id, q); }

    /**
     * An entry's page; a report with {@code beside=1} opens beside the chat. A report is read in the language {@code in} names, or the
     * person's own when they set one, when that differs from the language it was written in: the translation the library keeps, or a word
     * that one is being made ({@code d} runs it); {@code in=written} is the report as written.
     */
    static String entry(LibrarianDaemon d, LibraryStore store, LibraryProtocol p, Patrons.Patron patron, String id, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        if (!LibraryStore.safeName(id)) throw ProtocolError.notFound("entry " + id);
        ObjectNode e = p.entry(id, p.kindOf(id), true);
        if (e == null) throw ProtocolError.notFound("entry " + id);
        String kind = e.path("kind").asText();
        StringBuilder b = new StringBuilder();
        StringBuilder meta = new StringBuilder("<p class=\"k\">").append(esc(t(kind))).append(" · ").append(badge(e.path("state").asText()));
        if (!"raw".equals(kind)) meta.append(" · ").append(t("confidence {0}", esc(t(e.path("confidence").asText())))).append(" · ").append(esc(t(e.path("claim_type").asText())));
        meta.append(" · ").append(t("by {0}", esc(e.path("writer").asText()))).append(" · ").append(when(e.path("recorded_at").asText()));
        if (e.hasNonNull("valid_as_of") && !e.path("valid_as_of").asText().isEmpty()) meta.append(" · ").append(t("valid as of {0}", esc(Chrome.now().day(e.path("valid_as_of").asText()))));
        if (e.hasNonNull("review_by")) meta.append(" · ").append(t("review due {0}", esc(Chrome.now().day(e.path("review_by").asText()))));
        meta.append("</p>");
        boolean report = "investigation".equals(kind);
        Investigation inv = report ? store.investigation(id) : null;
        ReportRun run = report ? reportRun(store, id) : null;
        // the language: what the report was written in, and what this person reads it in
        String written = inv == null ? "" : Translate.writtenIn(inv), askedIn = q.getOrDefault("in", "").strip(), mine = inv == null ? "" : ReadIn.of(store, patron);
        String readIn = inv == null ? "" : askedIn.isEmpty() ? mine : askedIn.equals("written") ? "" : Translate.code(askedIn);
        Translate.Translated tr = inv != null && !readIn.isEmpty() && !readIn.equals(written) ? Translate.currentReport(store, inv, readIn) : null;
        String title = tr == null ? e.path("title").asText(id) : tr.words().getOrDefault(inv.title(), inv.title());
        String body = tr == null ? e.path("body").asText() : tr.markdown();
        if (report && inv != null) {   // a report: its line of state at its head, the legend of the two marks, and the dossier it belongs to
            int waiting = 0; for (ObjectNode o : p.inboxList()) if (id.equals(o.path("report").asText(""))) waiting++;
            b.append("<p class=\"state done\">").append(reportStateLine(inv, run.job(), run.ledger(), waiting)).append(languageState(written, tr == null ? "" : readIn, mine)).append("</p>").append(legend());
            b.append(readInLine(d, store, patron, id, written, readIn, mine, tr));
            b.append(saveLinks("/download?id=" + enc(id), askedIn));
            String subject = inv.title().startsWith("The story of ") ? inv.title().substring("The story of ".length()).strip() : TimelineClaims.subjectOf(store, inv);
            if (subject != null && !subject.isBlank()) b.append("<p class=\"k\">").append(t("Dossier: {0} — everything the library holds on them, as one page.", "<a href=\"/dossier?subject=" + enc(subject) + "\">" + esc(subject) + "</a>")).append("</p>");
        } else b.append(meta);
        if (!"raw".equals(kind) && !report) b.append(ladder(id, Explain.Rung.written)).append(downloads(id, null));
        if (!"raw".equals(kind) && !report && mayWrite(store, patron)) b.append(removeForm(id, kind));
        if (report && mayWrite(store, patron)) b.append(goDeeper(e));
        if (e.path("subjects").size() > 0) {
            b.append("<p class=\"k\">").append(t("Subjects:")).append(" ");
            for (JsonNode s : e.path("subjects")) b.append("<a href=\"/search?subject=").append(enc(s.asText())).append("\">").append(esc(s.asText())).append("</a> ");
            b.append("</p>");
        }
        if (e.hasNonNull("triple")) {
            JsonNode t = e.get("triple");
            b.append("<p class=\"triple\"><a href=\"/map?focus=").append(enc(t.path("subject").asText())).append("\">").append(esc(t.path("subject").asText())).append("</a> — ")
             .append(esc(t.path("predicate").asText())).append(" — <a href=\"/map?focus=").append(enc(t.path("object").asText())).append("\">").append(esc(t.path("object").asText())).append("</a></p>");
        }
        if ("raw".equals(kind)) {
            PdfReading.Preflight pre = PdfReading.fromJson(e.get("pdf_reading"));   // a PDF: what the reader found, the sentence the add and a plan say
            if (pre != null) b.append("<p class=\"k\">").append(esc(PdfReading.said(pre, Chrome.now()))).append("</p>");
            b.append("<p>").append(t("Saved from {0} ({1} characters).", locatorLink(e.path("locator").asText()), e.path("chars").asInt())).append(" ")
             .append("<a href=\"/read?locator=").append(enc(e.path("locator").asText())).append("\">").append(t("Read the whole saved page")).append("</a>.</p>");
            b.append("<pre class=\"raw\">").append(esc(e.path("body").asText())).append("</pre>");
        } else {
            b.append("<div class=\"body\">").append(withRefs(md(body), body)).append("</div>");
        }
        if (e.path("sources").size() > 0 && !"raw".equals(kind)) {
            int n = e.path("sources").size(), ind = e.path("independent_sources").asInt(n);
            b.append("<h2>").append(t("Sources")).append("</h2>");
            b.append("<p class=\"k\">").append(n(n, "{0} source", "{0} sources")).append(ind < n ? ", " + t("{0} independent (copies of one text count once)", ind) : "")
             .append(ind <= 1 ? ". <span class=\"mark\">" + t("one source") + "</span> — " + t("nothing else backs this up yet") : "").append("</p><ul>");
            for (JsonNode s : e.path("sources")) {
                b.append("<li>").append(locatorLink(s.path("locator").asText()));
                if (s.hasNonNull("edition") && !s.path("edition").asText().isEmpty()) b.append(" <span class=\"k\">").append(esc(s.path("edition").asText())).append("</span>");
                StringBuilder facts = new StringBuilder();
                if (s.hasNonNull("published")) {
                    facts.append(t("published {0}", esc(Chrome.now().day(s.path("published").asText()))));
                    facts.append(esc(Researcher.ageNote(s.path("published").asText())));
                }
                String tier = s.path("tier").asText("");
                if (!tier.isEmpty() && !tier.equals("web")) { if (facts.length() > 0) facts.append(" · "); facts.append(esc(tier)); }
                if (s.hasNonNull("rule")) { if (facts.length() > 0) facts.append(" · "); facts.append("trust".equals(s.path("rule").asText()) ? "<span class=\"mark\">" + t("trusted by you") + "</span>" : "<span class=\"mark bad\">" + t("on your refused list") + "</span>"); }
                if (facts.length() > 0) b.append(" <span class=\"k\">").append(facts).append("</span>");
                if (!s.path("why").asText().isEmpty()) b.append("<br><span class=\"k\">").append(esc(s.path("why").asText())).append("</span>");
                b.append("</li>");
            }
            b.append("</ul>");
        }
        if (e.path("findings").size() > 0) {
            b.append("<h2>").append(t("Claims that came out of it")).append("</h2><ul class=\"tight\">");
            for (JsonNode f : e.path("findings")) b.append("<li>").append(report ? estMark(store, f.asText()) + " " : "").append(idLink(f.asText())).append(report ? claimTitle(store, f.asText()) : "").append("</li>");
            b.append("</ul>");
        }
        if (report && inv != null) {
            b.append(howMade(store, id, e, inv, run, meta.toString()));
            if (mayWrite(store, patron)) b.append(removeForm(id, kind));
        }
        if (e.path("notes").size() > 0) {
            b.append("<h2>").append(t("Notes on this claim")).append("</h2><ul>");
            for (JsonNode n : e.path("notes")) b.append("<li><b>").append(esc(n.path("kind").asText())).append("</b> ").append(esc(n.path("text").asText())).append(" <span class=\"k\">").append(esc(n.path("by").asText())).append(", ").append(esc(n.path("date").asText())).append("</span></li>");
            b.append("</ul>");
        }
        if (e.hasNonNull("last_checked")) {
            JsonNode lc = e.get("last_checked");
            String v = lc.path("verdict").asText("");
            b.append("<p class=\"k\">").append(t("Last read against its source {0}:", when(lc.path("at").asText()))).append(" ")
             .append("supported".equals(v) ? t("the source still says it") : "unsupported".equals(v) ? "<span class=\"mark bad\">" + t("the source does not say it") + "</span>" : "no-capture".equals(v) ? t("no saved copy of the source to read") : t("could not tell from the source")).append("</p>");
        }
        if (e.hasNonNull("review")) {
            JsonNode rv = e.get("review");
            b.append("<p class=\"k\">").append(t("Reviewed: {0} by {1} on {2}", esc(t(rv.path("decision").asText())), esc(rv.path("reviewer").asText()), when(rv.path("at").asText())))
             .append(rv.path("stale").asBoolean() ? " (" + t("it has changed since then") + ")" : "").append("</p>");
        }
        StringBuilder links = new StringBuilder();
        for (JsonNode s : e.path("supersedes")) links.append("<li>").append(t("replaces {0}", idLink(s.asText()))).append("</li>");
        for (JsonNode s : e.path("superseded_by")) links.append("<li>").append(t("replaced by {0}", idLink(s.asText()))).append("</li>");
        for (JsonNode s : e.path("related")) {
            links.append("<li>").append(t("related {0}", idLink(s.path("id").asText())));
            if (s.path("shared").size() > 0) { links.append(" <span class=\"k\">(").append(t("shares")).append(" "); for (JsonNode sh : s.path("shared")) links.append(esc(sh.asText())).append(' '); links.append(")</span>"); }
            links.append("</li>");
        }
        if (links.length() > 0) b.append("<h2>").append(t("Connected")).append("</h2><ul class=\"tight\">").append(links).append("</ul>");
        if (report && "1".equals(q.get("beside"))) return beside(store, p, patron, title, t("Report") + " · " + t("opened from Past") + " · <a href=\"/entry/" + enc(id) + "\">" + t("its own page") + "</a>", "/past", b.toString());
        return page(store, patron, title, b.toString());
    }

    /**
     * What a report's line of state adds about its language: "Written in X, read in Y." when it is read translated; "Written in X." when
     * it was written in a language other than English, the default left unsaid, or in one other than the person's own, no translation
     * kept yet; nothing for an English report read by a person who reads as written or in English.
     */
    static String languageState(String written, String readIn, String mine) {
        if (!readIn.isEmpty()) return " " + t("Written in {0}, read in {1}.", esc(languageName(written)), esc(languageName(readIn)));
        if (!written.equals("en") || (!mine.isEmpty() && !mine.equals(written))) return " " + t("Written in {0}.", esc(languageName(written)));
        return "";
    }

    /** A language's name in the language of the page: Japanese, 日本語; one the table has no word for, as ReadIn names it. */
    static String languageName(String code) { return Chrome.now().languageName(code); }

    /** The words of the page, with the meaning beside an English word that has two ({@link Chrome#tc}). */
    static String tc(String en, String meaning, Object... args) { return Chrome.now().tc(en, meaning, args); }
    /** What "Read in" means on the page that reads material into the library, against the line on a report that names its language. */
    static final String READ_IN_PAGE = "the page and its link in the menu that read material into the library";

    /** A language by its own name, for a list of languages to switch to: 日本語, Deutsch; one the switch does not name, as its locale names itself. */
    static String ownName(String code) {
        String c = Translate.code(code);
        if (Chrome.FILES.contains(c) || c.equals(Chrome.EN)) return Chrome.nativeName(c);
        String own = Locale.forLanguageTag(c).getDisplayLanguage(Locale.forLanguageTag(c));
        return own.isBlank() || own.equals(c) ? ReadIn.name(c) : Character.toUpperCase(own.charAt(0)) + own.substring(1);
    }

    /** A Past row's word on a report's language, by the same rule as its page: read in the person's language when that translation is kept. */
    static String languageNote(LibraryStore store, Investigation inv, String mine) {
        String written = Translate.writtenIn(inv);
        boolean translated = !mine.isEmpty() && !mine.equals(written) && Translate.currentReport(store, inv, mine) != null;
        return languageState(written, translated ? mine : "", mine);
    }

    /**
     * The Read in line of a report: as written, and each language the library offers, the one being read plain; the word on a translation
     * being made, started on a writer's visit; and the person's choice to keep: every report in this language, or as written from now on.
     */
    static String readInLine(LibrarianDaemon d, LibraryStore store, Patrons.Patron patron, String id, String written, String readIn, String mine, Translate.Translated tr) throws IOException {
        StringBuilder b = new StringBuilder("<p class=\"k readin\"><b>").append(t("Read in")).append("</b> ");
        b.append(readIn.isEmpty() ? t("as written") : "<a href=\"/entry/" + enc(id) + "?in=written\">" + t("as written") + "</a>");
        List<String[]> offered = new ArrayList<>();   // every language the pages are written in, by its own name; the person's and the one asked for besides
        for (String c : Chrome.available()) offered.add(new String[]{c, Chrome.nativeName(c)});
        if (!mine.isEmpty() && offered.stream().noneMatch(l -> l[0].equals(mine))) offered.add(new String[]{mine, ownName(mine)});
        if (!readIn.isEmpty() && offered.stream().noneMatch(l -> l[0].equals(readIn))) offered.add(new String[]{readIn, ownName(readIn)});
        for (String[] l : offered) {
            if (l[0].equals(written)) continue;
            b.append(" · ").append(l[0].equals(readIn) ? esc(l[1]) : "<a href=\"/entry/" + enc(id) + "?in=" + enc(l[0]) + "\">" + esc(l[1]) + "</a>");
        }
        b.append(" <span class=\"note\">(").append(t("translated by the model; the cites, the claim ids, the dates and the sources stand as they are")).append(")</span>");
        if (!readIn.isEmpty() && tr == null) {   // wanted in a language no translation is kept for: being made, or started now, or a writer's to start
            String name = languageName(readIn), running = d == null ? null : Translate.pendingReport(d.jobs(), id, readIn);
            if (running != null) b.append("<br>").append(t("Being translated into {0} ({1}): a few minutes on the model. Come back to this page.", esc(name), esc(running)));
            else if (d != null && mayWrite(store, patron)) {
                ObjectNode a = M.createObjectNode(); a.put("report", id); a.put("lang", readIn);
                String job = d.jobs().submit("translate", patron.did(), a);
                b.append("<br>").append(t("Translating into {0} now ({1}): a few minutes on the model, section by section. Come back to this page; it is shown as written meanwhile.", esc(name), esc(job)));
            } else b.append("<br>").append(t("No translation into {0} yet; the report is shown as written. A writer's visit to this page starts one.", esc(name)));
        }
        String back = "/entry/" + enc(id);
        if (!readIn.isEmpty() && !readIn.equals(mine)) b.append(" <form class=\"inline\" method=\"post\" action=\"/settings\"><input type=\"hidden\" name=\"op\" value=\"read_in\"><input type=\"hidden\" name=\"value\" value=\"").append(esc(readIn)).append("\"><input type=\"hidden\" name=\"back\" value=\"").append(esc(back)).append("\"><button class=\"btn quiet\">").append(t("Always read in {0}", esc(languageName(readIn)))).append("</button></form>");
        if (!mine.isEmpty()) b.append(" <span class=\"note\">").append(t("You read every report in {0}.", esc(languageName(mine)))).append("</span> <form class=\"inline\" method=\"post\" action=\"/settings\"><input type=\"hidden\" name=\"op\" value=\"read_in\"><input type=\"hidden\" name=\"value\" value=\"\"><input type=\"hidden\" name=\"back\" value=\"").append(esc(back)).append("\"><button class=\"btn quiet\">").append(t("As written from now on")).append("</button></form>");
        return b.append("</p>").toString();
    }

    private static String read(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        String locator = q.getOrDefault("locator", "").strip();
        if (locator.isEmpty()) return page(store, patron, t("Read"), "<p>" + t("Say which saved page to read.") + "</p>");
        ObjectNode a = args(patron); a.put("locator", locator); a.put("max_chars", 400_000);
        ObjectNode r = p.read(a);
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\">").append(locatorLink(r.path("locator").asText())).append(" · ").append(t("saved {0}", when(r.path("captured_at").asText()))).append(" · ").append(t("{0} characters", r.path("chars").asInt()))
         .append(r.path("truncated").asBoolean() ? " (" + t("shown in part") + ")" : "").append(" · <a href=\"/entry/").append(enc(r.path("raw_id").asText())).append("\">").append(t("the entry")).append("</a></p>");
        b.append("<pre class=\"raw\">").append(esc(r.path("text").asText())).append("</pre>");
        return page(store, patron, r.path("title").asText(locator), b.toString());
    }

    /** The Subjects page's forms: the proposed subjects accepted or dropped, by the tick boxes or all at once. The note shown back. */
    static String subjectsPost(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);
        String op = form.getOrDefault("op", "");
        if (!op.equals("accept") && !op.equals("drop")) return t("That is not something the Subjects page does.");
        List<String> which = new ArrayList<>();
        if ("1".equals(form.get("all"))) which.add("all");
        else for (var e : form.entrySet()) if (e.getKey().startsWith("p") && e.getKey().substring(1).matches("\\d+") && !e.getValue().isBlank()) which.add(e.getValue());
        return ProposedSubjects.decide(store, op, which).text().replace("\n", " ");
    }

    private static String subjects(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        ObjectNode r = p.subjects(args(patron));
        Map<String, List<JsonNode>> byFacet = new LinkedHashMap<>();
        for (JsonNode s : r.path("subjects")) if (s.hasNonNull("broader") || !s.path("narrower").isEmpty() == false) byFacet.computeIfAbsent(s.path("facet").asText(), k -> new ArrayList<>()).add(s);
        StringBuilder b = new StringBuilder();
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(esc(note)).append("</p>\n");
        // the proposed subjects: the cataloger's, decided here by a writer (until 2026-11-07 only at the terminal)
        List<ProposedSubjects.Proposal> proposed = ProposedSubjects.list(store);
        if (!proposed.isEmpty()) {
            boolean writer = mayWrite(store, patron);
            b.append("<h2>").append(t("Proposed")).append("</h2><p class=\"k\">").append(t("Subjects the cataloger proposes from the claims. Accepted, one joins the list and the claims are filed under it; dropped, it is forgotten.")).append("</p>");
            if (writer) b.append("<form method=\"post\" action=\"/subjects\" class=\"stack\">");
            int i = 0;
            for (ProposedSubjects.Proposal pr : proposed) {
                i++;
                b.append(writer ? "<label><input type=\"checkbox\" name=\"p" + i + "\" value=\"" + esc(pr.slug()) + "\"> " : "<p>").append("<b>").append(esc(pr.slug())).append("</b>").append(pr.description().isEmpty() ? "" : " — " + esc(pr.description())).append(writer ? "</label><br>" : "</p>");
            }
            if (writer) b.append("<button name=\"op\" value=\"accept\" class=\"btn primary\">").append(t("Accept the ticked")).append("</button> <button name=\"op\" value=\"drop\" class=\"quiet\">").append(t("Drop the ticked")).append("</button></form>")
                 .append("<form method=\"post\" action=\"/subjects\" class=\"inline\"><input type=\"hidden\" name=\"all\" value=\"1\"><button name=\"op\" value=\"accept\" class=\"quiet\">").append(t("Accept all")).append("</button> <button name=\"op\" value=\"drop\" class=\"quiet\">").append(t("Drop all")).append("</button></form>");
        }
        if (byFacet.isEmpty()) b.append("<p>").append(t("No subjects yet. They appear as claims are added.")).append("</p>");
        for (var f : byFacet.entrySet()) {
            b.append("<h2>").append(esc(f.getKey())).append("</h2><ul class=\"tight\">");
            for (JsonNode s : f.getValue()) {
                if (s.path("narrower").size() > 0 && !s.hasNonNull("broader")) continue;   // the facet row itself
                b.append("<li><a href=\"/search?subject=").append(enc(s.path("id").asText())).append("\">").append(esc(s.path("label").asText())).append("</a> <span class=\"k\">").append(esc(s.path("id").asText())).append(" · ").append(s.path("count").asInt()).append("</span></li>");
            }
            b.append("</ul>");
        }
        return page(store, patron, t("Subjects"), b.toString());
    }

    static String changes(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        List<Changes.Change> tail = Changes.tail(store, 200);
        StringBuilder b = new StringBuilder("<p class=\"k\">").append(t("What changed in the library, newest first.")).append("</p>");
        if (tail.isEmpty()) b.append("<p>").append(t("Nothing has changed yet.")).append("</p>");
        else { b.append("<ul class=\"tight\">"); for (int i = tail.size() - 1; i >= 0; i--) b.append("<li>").append(changeLine(tail.get(i))).append("</li>"); b.append("</ul>"); }
        return page(store, patron, t("Changes"), b.toString());
    }

    /** The dossier page's forms: what the person tells of the subject (filed as told), the story written, a forecast scored. The note shown back. */
    static String dossierPost(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);
        LibraryProtocol p = new LibraryProtocol(store);
        String subject = form.getOrDefault("subject", "").strip();
        try {
            if (form.containsKey("tell")) return p.tell(args(patron).put("subject", subject).put("text", form.getOrDefault("text", "").strip())).path("summary").asText();
            if (form.containsKey("story")) { ObjectNode r = p.dossier(args(patron).put("op", "story").put("subject", subject)); return t("The story of {0} is written as {1}: {2} chapters from {3} claims.", r.path("subject").asText(subject), r.path("id").asText(), r.path("chapters").asInt(), r.path("claims").asInt()); }
            return p.dossier(args(patron).put("op", "score").put("id", form.getOrDefault("score", "").strip()).put("outcome", form.getOrDefault("outcome", "").strip()).put("note", form.getOrDefault("note", "").strip())).path("summary").asText();
        } catch (ProtocolError e) { return e.getMessage(); }
    }

    /** /dossier?subject=…: the target package on a person or an organisation, {@link DossierPage}, from what the shelves hold. */
    /** The dossier read in another language: the translation the library keeps when it is current; else a job that makes it, and a word to come back. */
    static String dossierIn(LibrarianDaemon d, LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        String subject = q.getOrDefault("subject", "").strip(), lang = Translate.code(q.getOrDefault("in", ""));
        String name = languageName(lang);
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\">").append(t("{0}, read in {1}: the same page, each piece of prose translated by the model. The cites, the claim ids and the dates stand as they are, and the claims keep their own language on the shelves.", "<a href=\"/dossier?subject=" + enc(subject) + "\">" + esc(subject) + "</a>", esc(name))).append("</p>");
        Translate.Translated tr = Translate.current(store, subject, lang);
        if (tr != null) return page(store, patron, t("Dossier: {0}", subject) + " (" + name + ")", DossierPage.render(store, subject, tr.lookup(), lang));   // the same page, its pieces translated
        String running = d == null ? null : Translate.pending(d.jobs(), subject, lang);
        if (running != null) b.append("<p>").append(t("Being translated into {0} ({1}): a few minutes on the model. Come back to this page.", esc(name), esc(running))).append("</p>");
        else if (d != null && mayWrite(store, patron)) {
            ObjectNode a = new ObjectMapper().createObjectNode(); a.put("subject", subject); a.put("lang", lang);
            String id = d.jobs().submit("translate", patron.did(), a);
            b.append("<p>").append(t("Translating into {0} now ({1}): a few minutes on the model, section by section. Come back to this page.", esc(name), esc(id))).append("</p>");
        } else b.append("<p>").append(t("No translation into {0} yet. A writer's visit to this page starts one; the command is {1}.", esc(name), "researchzosho dossier \"" + esc(subject) + "\" --in " + esc(lang))).append("</p>");
        return page(store, patron, t("Dossier: {0}", subject) + " (" + name + ")", b.toString());
    }

    static String dossierPage(LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        String subject = q.getOrDefault("subject", "").strip();
        String state = "";
        if (!subject.isEmpty()) for (Dossiers.Entry e : Dossiers.list(store)) if (e.subject().equalsIgnoreCase(subject)) {
            state = "<p class=\"state done\">" + dossierState(e.lastRun(), e.claims(), e.reports(), e.story()) + "</p>";
            if ("1".equals(q.get("beside"))) return beside(store, new LibraryProtocol(store), patron, subject, t("Dossier") + " · " + esc(t(e.kind())) + " · " + t("opened from Past") + " · <a href=\"/dossier?subject=" + enc(subject) + "\">" + t("its own page") + "</a>", "/past", state + DossierPage.render(store, subject));
        }
        String note = q.getOrDefault("note", "").isEmpty() ? "" : "<p class=\"note\">" + esc(q.get("note")) + "</p>";
        return page(store, patron, subject.isEmpty() ? t("Dossier") : t("Dossier: {0}", subject), note + state + DossierPage.render(store, subject, mayWrite(store, patron)));
    }

    static String questions(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        StringBuilder b = new StringBuilder();
        // the searches the housekeeping keeps
        b.append("<h2>").append(t("Searches kept up to date")).append("</h2><p class=\"k\">").append(t("Nightly maintenance runs each one again every so many days. Anything new is listed on the Changes page.")).append("</p>");
        List<Serials.Shelf> kept = Serials.shelves(store);
        if (kept.isEmpty()) b.append("<p>").append(t("None yet.")).append("</p>");
        else {
            b.append("<ul class=\"tight\">");
            for (Serials.Shelf s : kept) b.append("<li>").append(s.parked() ? "<span class=\"badge\">" + t("parked") + "</span> " : "").append("<b>").append(esc(s.slug())).append("</b>: ").append(esc(s.query())).append(" <span class=\"k\">(").append(t("last {0}", esc(Chrome.now().day(s.lastChecked())))).append(")</span> ")
                    .append("<form method=\"post\" action=\"/questions\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"every\"><input type=\"hidden\" name=\"name\" value=\"").append(esc(s.slug())).append("\">").append(t("every {0} days", "<input name=\"every\" size=\"3\" value=\"" + s.everyDays() + "\">")).append(" <button>").append(t("Change")).append("</button></form> ")
                    .append("<form method=\"post\" action=\"/questions\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"").append(s.parked() ? "unpark-search" : "park-search").append("\"><input type=\"hidden\" name=\"name\" value=\"").append(esc(s.slug())).append("\"><button>").append(s.parked() ? t("Resume") : t("Park")).append("</button></form> ")
                    .append("<form method=\"post\" action=\"/questions\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"unkeep\"><input type=\"hidden\" name=\"name\" value=\"").append(esc(s.slug())).append("\"><button>").append(t("Stop this search")).append("</button></form></li>");
            b.append("</ul>");
        }
        b.append("<form method=\"post\" action=\"/questions\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"keep\">")
         .append("<label>").append(t("Keep a search")).append("<br><input name=\"query\" size=\"60\" placeholder=\"").append(esc(t("what to search for, every so often"))).append("\"></label>")
         .append("<label>").append(t("Name")).append(" <input name=\"name\" size=\"16\" placeholder=\"").append(esc(t("short-name"))).append("\"></label> <label>").append(t("every {0} days", "<input name=\"every\" size=\"3\" value=\"7\">")).append("</label> <button>").append(t("Keep it")).append("</button></form>");
        // the open questions: the queue, with the filters a person sorts a long list by; tick any number, then one button for all of them
        Map<String, String> f = new LinkedHashMap<>();
        for (String k : List.of("type", "show", "report", "fate", "who", "subject", "language", "q", "view", "similar")) if (q.containsKey(k) && !q.get(k).isBlank()) f.put(k, q.get(k).strip());
        f.putIfAbsent("show", "queued");
        String show = f.get("show");
        b.append("<h2>").append(t("Open questions")).append("</h2><p class=\"k\">").append(t("The nightly research takes its questions from this list: {0} research run(s) a night, and related questions share a run. The Runs page shows tonight's.", Crews.explorerBudget())).append(" ")
         .append(t("Questions left over from a report start out parked: they wait here until you put them in the queue. Tick any number, then research them now, move them, park them, or drop them.")).append("</p>");
        List<ObjectNode> open = p.frontierList(true);
        int dups = Frontier.duplicates(store).size();
        if (dups > 0) b.append("<form method=\"post\" action=\"/questions\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"tidy\">").append(t("{0} of these are second copies of the same question.", dups)).append(" <button>").append(t("Remove the copies")).append("</button></form>");
        // the facets, counted over the questions that pass the OTHER filters, so every count says what a click would show
        b.append("<form method=\"get\" action=\"/questions\" class=\"inline\">");
        for (var e : f.entrySet()) if (!e.getKey().equals("q")) b.append("<input type=\"hidden\" name=\"").append(esc(e.getKey())).append("\" value=\"").append(esc(e.getValue())).append("\">");
        b.append("<input name=\"q\" size=\"32\" value=\"").append(esc(f.getOrDefault("q", ""))).append("\" placeholder=\"").append(esc(t("words in the question"))).append("\"> <button>").append(t("Find")).append("</button>")
         .append(f.size() > 1 || !show.equals("queued") ? " <a class=\"k\" href=\"/questions\">" + t("clear the filters") + "</a>" : "").append("</form>");
        b.append(facetRow(open, f, "show", t("Show"), List.of(new String[]{"queued", t("queued")}, new String[]{"parked", t("parked")}, new String[]{"all", t("all")}), false));
        b.append(facetRow(open, f, "type", t("Type"), List.of(new String[]{"report", t("from reports")}, new String[]{"asked", t("asked, unanswered")}, new String[]{"person", t("added by you")}, new String[]{"dispute", t("from disputes")}, new String[]{"check", t("source checks")}), true));
        List<String[]> reports = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ObjectNode o : open) { String id = o.path("report").asText(""); if (!id.isEmpty() && seen.add(id)) reports.add(new String[]{id, shortReport(id, o.path("report_title").asText(""))}); }
        if (reports.size() > 1) b.append(facetRow(open, f, "report", t("Left by"), reports, true));
        List<String[]> fates = new ArrayList<>();
        for (String[] x : new String[][]{{"kept", t("a report you kept")}, {"waiting", t("a report still in the inbox")}, {"disputed", t("a disputed report")}, {"retired", t("a retired report")}, {"none", t("a report with no claims")}}) for (ObjectNode o : open) if (o.path("report_fate").asText("").equals(x[0])) { fates.add(x); break; }
        if (fates.size() > 1) b.append(facetRow(open, f, "fate", t("What became of it"), fates, true));
        Map<String, Integer> whos = new TreeMap<>();
        for (ObjectNode o : open) if (o.hasNonNull("perspective")) whos.merge(o.path("perspective").asText(), 1, Integer::sum);
        if (whos.size() > 1) { List<String[]> ws = new ArrayList<>(); for (String w : whos.keySet()) ws.add(new String[]{w, w}); b.append(facetRow(open, f, "who", t("Asked from"), ws, true)); }
        Map<String, Integer> subjects = new TreeMap<>();
        for (ObjectNode o : open) for (var x : o.path("subjects")) subjects.merge(x.asText(), 1, Integer::sum);
        if (!subjects.isEmpty()) { List<String[]> ss = new ArrayList<>(); for (String x : subjects.keySet()) ss.add(new String[]{x, x}); b.append(facetRow(open, f, "subject", t("Subject"), ss, true)); }
        Map<String, Integer> langs = new TreeMap<>();
        for (ObjectNode o : open) langs.merge(o.path("language").asText("english"), 1, Integer::sum);
        if (langs.size() > 1) { List<String[]> ls = new ArrayList<>(); for (String x : langs.keySet()) ls.add(new String[]{x, x}); b.append(facetRow(open, f, "language", t("Language"), ls, true)); }
        String view = f.getOrDefault("view", reports.isEmpty() ? "list" : "report");
        String similar = f.getOrDefault("similar", "folded");
        b.append("<p class=\"k\">").append(t("View:")).append(" ").append(view.equals("report") ? "<b>" + t("by report") + "</b>" : "<a href=\"" + href(f, "view", "report") + "\">" + t("by report") + "</a>").append(" · ")
         .append(view.equals("list") ? "<b>" + t("one list") + "</b>" : "<a href=\"" + href(f, "view", "list") + "\">" + t("one list") + "</a>")
         .append(" &nbsp; ").append(t("Questions that read alike:")).append(" ").append(similar.equals("folded") ? "<b>" + t("folded") + "</b>" : "<a href=\"" + href(f, "similar", "folded") + "\">" + t("folded") + "</a>").append(" · ")
         .append(similar.equals("shown") ? "<b>" + t("shown") + "</b>" : "<a href=\"" + href(f, "similar", "shown") + "\">" + t("shown") + "</a>").append("</p>");
        if (f.getOrDefault("type", "").equals("check")) b.append("<p class=\"k\">").append(t("Source checks are not research questions. A re-read found that the source does not support the claim. The claim is in the {0}. Decide it there. The nightly research never takes these.", "<a href=\"/inbox\">" + t("Inbox") + "</a>")).append("</p>");
        List<ObjectNode> rows = new ArrayList<>();
        for (ObjectNode o : open) if (LibraryProtocol.matches(o, f)) rows.add(o);
        if (rows.isEmpty()) b.append("<p>").append(t("No questions match these filters.")).append("</p>");
        else {
            b.append("<form method=\"post\" action=\"/questions\" class=\"pick\"><input type=\"hidden\" name=\"op\" value=\"selected\">")
             .append("<div class=\"bar\"><label><input type=\"checkbox\" onclick=\"for(const c of this.form.querySelectorAll('input[name^=q]'))c.checked=this.checked\"> ").append(t("all")).append("</label> ")
             .append("<button name=\"do\" value=\"run\">").append(t("Research now")).append("</button> <button name=\"do\" value=\"next\">").append(t("Run next")).append("</button> <button name=\"do\" value=\"later\">").append(t("Run later")).append("</button> ");
            if (!show.equals("queued")) b.append("<button name=\"do\" value=\"unpark\">").append(t("Put back in the queue")).append("</button> ");
            if (!show.equals("parked")) b.append("<button name=\"do\" value=\"park\">").append(t("Park")).append("</button> ");
            b.append("<button name=\"do\" value=\"drop\">").append(t("Drop")).append("</button></div>");
            // grouped by the report that left them (a group's box ticks the group), or one list; alike questions fold under their first
            Map<String, List<ObjectNode>> groups = new LinkedHashMap<>();
            for (ObjectNode o : rows) groups.computeIfAbsent(view.equals("report") ? o.path("report").asText("") : "", k -> new ArrayList<>()).add(o);
            int[] n = {0};
            for (var e : groups.entrySet()) {
                String id = e.getKey();
                if (view.equals("report")) {
                    ObjectNode first = e.getValue().get(0);
                    b.append("<h3 class=\"group\"><label><input type=\"checkbox\" onclick=\"for(const c of this.closest('h3').nextElementSibling.querySelectorAll('input[name^=q]'))c.checked=this.checked\"> ");
                    if (id.isEmpty()) b.append(t("Not from a report"));
                    else b.append(t("Left by {0}", idLink(id, shortReport(id, first.path("report_title").asText(""))))).append(" ").append(fateBadge(first.path("report_fate").asText("")));
                    b.append(" <span class=\"k\">").append(e.getValue().size()).append("</span></label>");
                    if (!id.isEmpty() && first.path("report_fate").asText("").equals("waiting")) b.append(" <a class=\"k\" href=\"/inbox?report=").append(enc(id)).append("\">").append(t("decide its claims")).append("</a>");
                    b.append("</h3>");
                }
                b.append("<ul>");
                Set<String> inGroup = new HashSet<>();
                for (ObjectNode o : e.getValue()) inGroup.add(o.path("text").asText());
                Set<String> folded = new HashSet<>();
                if (similar.equals("folded")) for (ObjectNode o : e.getValue()) { String head = o.path("similar").asText(""); if (!head.isEmpty() && !head.equals(o.path("text").asText()) && inGroup.contains(head)) folded.add(o.path("text").asText()); }
                for (ObjectNode o : e.getValue()) {
                    String text = o.path("text").asText();
                    if (folded.contains(text)) continue;
                    b.append("<li>").append(questionRow(o, n, show));
                    List<ObjectNode> under = new ArrayList<>();
                    for (ObjectNode x : e.getValue()) if (folded.contains(x.path("text").asText()) && x.path("similar").asText("").equals(text)) under.add(x);
                    if (!under.isEmpty()) {
                        b.append("<details><summary class=\"k\">").append(n(under.size(), "{0} question reads alike", "{0} questions read alike")).append("</summary><ul>");
                        for (ObjectNode x : under) b.append("<li>").append(questionRow(x, n, show)).append("</li>");
                        b.append("</ul></details>");
                    }
                    b.append("</li>");
                }
                b.append("</ul>");
            }
            b.append("</form>");
        }
        b.append("<form method=\"post\" action=\"/questions\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"budget\">").append(t("The nightly research takes {0} run(s) a night; tonight only: {1}", "<input name=\"standing\" size=\"2\" value=\"" + Crews.explorerPerNight() + "\">", "<input name=\"tonight\" size=\"2\" value=\"" + (Crews.explorerTonight() > 0 ? String.valueOf(Crews.explorerTonight()) : "") + "\" placeholder=\"" + esc(t("same")) + "\">")).append(" <button>").append(t("Set")).append("</button></form>");
        b.append("<form method=\"post\" action=\"/questions\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"add\">")
         .append("<label>").append(t("Add an open question")).append("<br><input name=\"question\" size=\"70\" placeholder=\"").append(esc(t("a question for the nightly research to take on a coming night"))).append("\"></label> <button>").append(t("Add it")).append("</button></form>");
        return page(store, patron, t("Open questions"), b.toString());
    }

    /** One open question as a list row: its box, tonight's mark, its place, the text, and what a person filters on. */
    private static String questionRow(ObjectNode o, int[] n, String show) {
        String text = o.path("text").asText();
        String t = o.path("type").asText();
        StringBuilder b = new StringBuilder("<label><input type=\"checkbox\" name=\"q").append(n[0]++).append("\" value=\"").append(esc(text)).append("\"> ");
        if (o.path("tonight").asBoolean()) b.append("<b class=\"badge accepted\">").append(t("tonight")).append("</b> ");
        if (o.path("parked").asBoolean() && !show.equals("parked")) b.append("<span class=\"badge\">").append(t("parked")).append("</span> ");
        b.append("<span class=\"k\">").append(o.path("position").asInt()).append(".</span> ").append(withIdLinks(Frontier.strip(text)));
        if (o.hasNonNull("parked_why")) b.append(" <span class=\"k\">— ").append(t("waits: {0}", esc(o.path("parked_why").asText()))).append("</span>");
        b.append(" <span class=\"k\">").append(esc(t(t))).append(t.equals("asked") ? " ×" + o.path("asked").asInt() : "").append(o.hasNonNull("field") ? " · " + t("{0} mode", esc(Fields.said(o.path("field").asText()))) : "").append(" · ").append(esc(Chrome.now().day(o.path("date").asText())));
        if (!o.path("language").asText("english").equals("english")) b.append(" · ").append(esc(o.path("language").asText()));
        for (var x : o.path("subjects")) b.append(" · ").append(esc(x.asText()));
        b.append("</span>");
        if (o.hasNonNull("answered")) b.append(" <span class=\"k\">— ").append(t("maybe answered already: {0}", idLink(o.path("answered").path("id").asText(), o.path("answered").path("title").asText()))).append("</span>");
        return b.append("</label>").toString();
    }

    /** A row of facet links: each value with its count among the questions passing the other filters; the chosen one in bold. */
    private static String facetRow(List<ObjectNode> open, Map<String, String> f, String key, String label, List<String[]> values, boolean withAll) {
        return facetRow("/questions", LibraryProtocol::matches, open, f, key, label, values, withAll);
    }

    private static String facetRow(String base, BiPredicate<ObjectNode, Map<String, String>> matches, List<ObjectNode> open, Map<String, String> f, String key, String label, List<String[]> values, boolean withAll) {
        Map<String, String> others = new LinkedHashMap<>(f); others.remove(key);
        if (key.equals("show")) others.put("show", "all");
        StringBuilder b = new StringBuilder("<p class=\"k\">").append(esc(label)).append(": ");
        String chosen = f.getOrDefault(key, "");
        if (withAll) b.append(chosen.isEmpty() ? "<b>" + t("all") + "</b>" : "<a href=\"" + href(base, f, key, "") + "\">" + t("all") + "</a>");
        boolean first = !withAll;
        for (String[] v : values) {
            int count = 0;
            Map<String, String> with = new LinkedHashMap<>(others); with.put(key, v[0]);
            for (ObjectNode o : open) if (matches.test(o, with)) count++;
            if (!first) b.append(" · "); first = false;
            b.append(chosen.equals(v[0]) ? "<b>" + esc(v[1]) + "</b>" : "<a href=\"" + href(base, f, key, v[0]) + "\">" + esc(v[1]) + "</a>").append(" (").append(count).append(")");
        }
        return b.append("</p>").toString();
    }

    /** The questions page with one filter changed; an empty value removes it. */
    private static String href(Map<String, String> f, String key, String value) { return href("/questions", f, key, value); }

    private static String href(String base, Map<String, String> f, String key, String value) {
        Map<String, String> m = new LinkedHashMap<>(f);
        if (value.isEmpty()) m.remove(key); else m.put(key, value);
        StringBuilder b = new StringBuilder(base);
        char sep = '?';
        for (var e : m.entrySet()) { b.append(sep).append(enc(e.getKey())).append('=').append(enc(e.getValue())); sep = '&'; }
        return esc(b.toString());
    }

    /** "I-0016 What is a variational autoencoder…" — the id and a short title. */
    private static String shortReport(String id, String title) {
        String t = title == null ? "" : title.strip();
        if (t.length() > 48) t = t.substring(0, 47) + "…";
        return id.replaceAll("^(I-\\d+).*$", "$1") + (t.isEmpty() ? "" : " " + t);
    }

    private static String fateBadge(String fate) {
        return switch (fate) {
            case "kept" -> "<span class=\"badge accepted\">" + t("kept") + "</span>";
            case "waiting" -> "<span class=\"badge\">" + t("in the inbox") + "</span>";
            case "disputed" -> "<span class=\"badge disputed\">" + t("disputed") + "</span>";
            case "retired" -> "<span class=\"badge\">" + t("retired") + "</span>";
            case "none" -> "<span class=\"badge\">" + t("no claims") + "</span>";
            default -> "";
        };
    }

    /** The forms on the questions page. Returns where to go next. */
    /** The Add page: files, a folder or an archive from the person's own computer, or a page on the web, onto the shelves. */
    static String addPage() {
        return "<p class=\"k\">" + t("A document of your own goes onto the shelves here: a PDF, a Word file, a presentation, an EPUB, a web page saved as HTML, a plain text file, or a page by its address.") + " "
                + t("The library reads the text into the shelves; the file itself stays yours.") + "</p>"
                + "<h2 id=\"file\">" + t("Files from this computer") + "</h2><p class=\"k\">" + t("One file or several at once, up to {0} files and {1} in one go. An archive among them (zip, tar, tar.gz, tgz) is unpacked and its documents shelved under a collection named for it.", Uploads.MAX_FILES, (Uploads.MAX >> 20) + " MB") + "</p>"
                + "<form method=\"post\" action=\"/add\" enctype=\"multipart/form-data\" class=\"stack\"><input type=\"file\" name=\"file\" multiple required aria-label=\"" + esc(t("the files to add")) + "\"> "
                + "<input name=\"collection\" placeholder=\"" + esc(t("a collection to shelve them under (optional)")) + "\" aria-label=\"" + esc(t("the collection")) + "\"> <button>" + t("Add the files") + "</button></form>"
                + "<h2 id=\"folder\">" + t("A whole folder from this computer") + "</h2><p class=\"k\">" + t("The folder's documents, with the folders inside it, go onto the shelves under a collection named for the folder unless you name one. Files the library does not read (pictures, programs) are left out.") + "</p>"
                + "<form method=\"post\" action=\"/add\" enctype=\"multipart/form-data\" class=\"stack\"><input type=\"file\" name=\"folder\" webkitdirectory multiple required aria-label=\"" + esc(t("the folder to add")) + "\"> "
                + "<input name=\"collection\" placeholder=\"" + esc(t("a collection (optional)")) + "\" aria-label=\"" + esc(t("the collection")) + "\"> <button>" + t("Add the folder") + "</button></form>"
                + "<h2 id=\"url\">" + t("A page or a document on the web") + "</h2><form method=\"post\" action=\"/add\" class=\"stack\"><input name=\"url\" placeholder=\"https://…\" required aria-label=\"" + esc(t("the address")) + "\"> "
                + "<input name=\"collection\" placeholder=\"" + esc(t("a collection (optional)")) + "\" aria-label=\"" + esc(t("the collection")) + "\"> <button>" + t("Add the page") + "</button></form>"
                + "<p class=\"k\">" + t("A folder too big for the page, or a file to be read where it lies, goes in at the command line on the machine that runs the library: {0} and the path. A program does the same with the tool library_add.", "<code>researchzosho add</code>") + "</p>";
    }

    /** What one posted thing came to: its name, what happened to it in a sentence (a file's title, when it has one of its own), the problems a folder's or an archive's add listed ("" when none), the entry it is on the shelves as ("" for a folder, an archive, or a refusal), and whether it went onto the shelves. */
    record Shelved(String name, String said, String problems, String id, boolean kept) {
        /** The sentence and the problems together. */
        String full() { return said + problems; }
    }

    /** A file's title when it has one of its own; "" when it is only the file's name, or the name without its ending (what the corpus makes of a file with no title inside). */
    static String ownTitle(String title, String name) {
        String t = title == null ? "" : title.strip();
        String stem = name.replaceAll("\\.[A-Za-z0-9]+$", "").replace('_', ' ');
        return t.isEmpty() || t.equalsIgnoreCase(name) || t.equalsIgnoreCase(stem) ? "" : t;
    }

    /**
     * Every file a form posted, onto the shelves by the command line's own add: a file as one document; an archive unpacked and shelved
     * as a folder; the files of a folder chosen whole, which arrive with their paths inside it, as that folder under a collection named
     * for it unless one is given. The upload's folder is the caller's to drop.
     */
    static List<Shelved> shelveAll(LibraryProtocol p, Patrons.Patron patron, Map<String, String> form, String collection) {
        List<Shelved> out = new ArrayList<>();
        List<Uploads.Uploaded> files = Uploads.files(form, "file"), folder = Uploads.files(form, "folder");
        Path root = null;   // the folder chosen whole, on disk as it was chosen
        for (Uploads.Uploaded u : folder) {
            if (u.rel().isEmpty()) { files.add(u); continue; }   // a browser that sent the folder's files with their names alone: each as a file
            Path top = u.path();
            for (int i = u.rel().split("/").length - 1; i > 0; i--) top = top.getParent();
            if (root == null) root = top;
        }
        if (root != null) {
            ObjectNode a = args(patron); a.put("path", root.toString()); if (!collection.isEmpty()) a.put("collection", collection);
            String name = root.getFileName().toString();
            try { ObjectNode r = p.add(a); out.add(new Shelved(name, r.path("summary").asText(""), problems(r), "", r.path("added").asInt() > 0)); }
            catch (ProtocolError | IOException e) { out.add(new Shelved(name, e.getMessage(), "", "", false)); }
        }
        shelveFiles(p, patron, files, collection, out);
        return out;
    }

    /** Each file onto the shelves in turn, by the protocol's add: a document by its entry, an archive by what came of it, one that could not be added by why. */
    static void shelveFiles(LibraryProtocol p, Patrons.Patron patron, List<Uploads.Uploaded> files, String collection, List<Shelved> out) {
        for (Uploads.Uploaded u : files) {
            ObjectNode a = args(patron); a.put("path", u.path().toString()); if (!collection.isEmpty()) a.put("collection", collection);
            try {
                ObjectNode r = p.add(a);
                if (r.path("kind").asText("").equals("archive")) out.add(new Shelved(u.name(), r.path("summary").asText(""), problems(r), "", r.path("added").asInt() > 0));
                else out.add(new Shelved(u.name(), ownTitle(r.path("title").asText(""), u.name()), r.path("reading").asText("").isEmpty() ? "" : " " + r.path("reading").asText(""), r.path("raw").asText(""), true));
            } catch (ProtocolError | IOException e) { out.add(new Shelved(u.name(), e.getMessage(), "", "", false)); }
        }
    }

    /** The problems a folder's or an archive's add lists, as one sentence after the summary; "" when none. */
    static String problems(ObjectNode r) {
        if (r.path("problems").size() == 0) return "";
        StringBuilder b = new StringBuilder(" ");
        int n = 0;
        for (JsonNode x : r.path("problems")) { if (n++ == 12) { b.append("… and ").append(r.path("problems").size() - 12).append(" more."); break; } b.append(x.asText()).append(". "); }
        return b.toString().stripTrailing();
    }

    /** What the Add page's forms do: the files written by the upload or the address given go through the same add as the command line's, and the page says what came of each. */
    static String addPost(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> form) throws IOException {
        String url = form.getOrDefault("url", "").strip(), collection = form.getOrDefault("collection", "").strip();
        try {
            boolean any = !Uploads.files(form, "file").isEmpty() || !Uploads.files(form, "folder").isEmpty();
            if (!any && url.isEmpty()) return "<p>" + t("Choose a file, or write a page's address.") + "</p>" + addPage();
            if (!any) {
                ObjectNode a = args(patron); a.put("url", url);
                if (!collection.isEmpty()) a.put("collection", collection);
                ObjectNode r;
                try { r = p.add(a); }
                catch (ProtocolError e) { return "<p>" + esc(e.getMessage()) + "</p>" + addPage(); }
                String title = r.path("title").asText("").strip(), summary = r.path("summary").asText("").strip();
                return "<p><b>" + t("Added:") + "</b> " + esc(url) + (title.isEmpty() || title.equals(url) ? "" : " — " + esc(title)) + (summary.isEmpty() ? "" : ". " + esc(summary)) + ". " + t("It is on the shelves now: {0}, or {1}.", "<a href=\"/search?q=" + enc(title.isEmpty() ? url : title) + "\">" + t("find it") + "</a>", "<a href=\"/ask\">" + t("ask a question of it") + "</a>") + "</p>" + addPage();
            }
            List<Shelved> all = shelveAll(p, patron, form, collection);
            if (all.size() == 1 && !all.get(0).id().isEmpty()) {   // one file, as before: its name, its title, and where to go
                Shelved s = all.get(0);
                return "<p><b>" + t("Added:") + "</b> " + esc(s.name()) + (s.said().isEmpty() ? "" : " — " + esc(s.said())) + ". " + t("It is on the shelves now: {0}, or {1}.", "<a href=\"/search?q=" + enc(s.said().isEmpty() ? s.name() : s.said()) + "\">" + t("find it") + "</a>", "<a href=\"/ask\">" + t("ask a question of it") + "</a>") + "</p>" + addPage();
            }
            long kept = all.stream().filter(Shelved::kept).count();
            StringBuilder b = new StringBuilder("<p><b>").append(esc(t("{0} of {1} went onto the shelves.", kept, all.size()))).append("</b></p><ul>");
            for (Shelved s : all) {
                b.append("<li>").append(s.kept() ? "" : "<span class=\"k3\">" + t("not kept") + "</span> ").append("<b>").append(esc(s.name())).append("</b>");
                if (!s.id().isEmpty()) b.append(s.said().isEmpty() ? "" : " — " + esc(s.said())).append(" · <a href=\"/entry/").append(enc(s.id())).append("\">").append(t("the entry")).append("</a>");
                else if (!s.full().isEmpty()) b.append(" — ").append(esc(s.full()));
                b.append("</li>");
            }
            b.append("</ul><p>").append(t("What is on the shelves now can be found with {0}, or you can {1}.", "<a href=\"/search\">" + t("a search") + "</a>", "<a href=\"/ask\">" + t("ask a question of it") + "</a>")).append("</p>");
            return b + addPage();
        } finally { Uploads.drop(form); }
    }

    private static String questionsPost(LibrarianDaemon d, LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> form) throws IOException {
        ObjectNode a = args(patron);
        switch (form.getOrDefault("op", "")) {
            case "keep" -> { a.put("op", "add"); a.put("name", form.getOrDefault("name", "").isBlank() ? slugOf(form.getOrDefault("query", "")) : form.get("name")); a.put("query", form.getOrDefault("query", "")); a.put("every_days", num(form.get("every")) > 0 ? num(form.get("every")) : 7); p.serials(a); }
            case "unkeep" -> { a.put("op", "remove"); a.put("name", form.getOrDefault("name", "")); p.serials(a); }
            case "park-search", "unpark-search" -> { a.put("op", form.get("op").equals("park-search") ? "park" : "unpark"); a.put("name", form.getOrDefault("name", "")); p.serials(a); }
            case "every" -> { a.put("op", "every"); a.put("name", form.getOrDefault("name", "")); a.put("every_days", num(form.get("every"))); p.serials(a); }
            case "add" -> { a.put("op", "add"); a.put("question", form.getOrDefault("question", "")); p.frontier(a); }
            case "tidy" -> { a.put("op", "tidy"); p.frontier(a); }
            case "budget" -> {
                Patrons.check(store, patron, Patrons.Level.write);
                int standing = num(form.get("standing")); int tonight = num(form.get("tonight"));
                Config.set("RESEARCHZOSHO_EXPLORER_PER_NIGHT", String.valueOf(standing));
                Config.set("explorer.tonight", String.valueOf(tonight));
            }
            case "selected" -> {
                List<String> picked = new ArrayList<>();
                for (var e : form.entrySet()) if (e.getKey().startsWith("q") && e.getKey().substring(1).matches("\\d+") && !e.getValue().isBlank()) picked.add(e.getValue());
                String action = form.getOrDefault("do", "");
                if (action.equals("drop") || action.equals("next") || action.equals("later") || action.equals("park") || action.equals("unpark")) {
                    // "run next" on several: the last one ticked ends up first, so apply in reverse to keep the ticked order
                    List<String> order = action.equals("next") ? new ArrayList<>(picked.reversed()) : picked;
                    for (String q : order) { ObjectNode x = args(patron); x.put("op", action); x.put("question", q); try { p.frontier(x); } catch (ProtocolError ignored) { } }
                } else if ("run".equals(action)) {
                    Patrons.check(store, patron, Patrons.Level.write);
                    String lastId = null;
                    List<String> told = new ArrayList<>();   // the runs whose question looks like a field's: the runs page says so
                    Map<String, String> fieldOf = new HashMap<>();
                    var runs = Fields.runs(store);
                    for (Frontier.Line l : Frontier.read(store)) if (l.open()) fieldOf.put(l.text(), Fields.ofLine(l, runs));
                    for (String q : picked) {
                        ObjectNode body = M.createObjectNode(); body.put("question", q); body.put("mode", "broad"); body.put("sources", "both");
                        String field = fieldOf.getOrDefault(q, "");   // a question a field filed is sent as that field's, never guessed from its words
                        if (!field.isEmpty() && Fields.enabledNamed(store, field) != null) body.put("field", field);
                        ObjectNode sent = d.research(body, patron, new LibraryProtocol.Way("explorer-line", "web", true));
                        lastId = sent.path("job_id").asText(null);
                        if (sent.has("suggestion") && lastId != null) told.add(lastId);
                        ObjectNode x = args(patron); x.put("op", "drop"); x.put("question", q); try { p.frontier(x); } catch (ProtocolError ignored) { }   // sent: no longer open
                    }
                    if (lastId != null) return told.isEmpty() ? "/jobs" : "/jobs?told=" + enc(String.join(",", told));
                }
            }
            default -> throw ProtocolError.invalidArgs("This form is not known. Go back and try again.");
        }
        return "/questions";
    }

    /** The inbox: every claim waiting for a decision, sorted by the same facets as the open questions, ticked in any number, then one decision for all of them. */
    static String inbox(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        Map<String, String> f = new LinkedHashMap<>();
        for (String k : List.of("report", "subject", "kind", "tier", "confidence", "writer", "state", "language", "q", "view")) if (q.containsKey(k) && !q.get(k).isBlank()) f.put(k, q.get(k).strip());
        List<ObjectNode> all = p.inboxList();
        StringBuilder b = new StringBuilder();
        for (Profile fp : Librarian.chatFields(store)) if (fp.joinsOnlyWhenAsked()) b.append(fp.inboxNote(store, mayWrite(store, patron)));   // a field whose work the library holds
        for (String told : UncheckedPages.tell(store)) b.append("<p class=\"notice\">").append(esc(told)).append("</p>");   // pages saved before their check, and pages a later check removed
        List<Recalls.Hit> recallHits = Recalls.hits(store, true);
        if (!recallHits.isEmpty()) b.append("<p class=\"notice\">").append(t("{0} recall notice(s) may concern something you own.", recallHits.size())).append(" ").append(t("{0}, where each can be put away.", "<a href=\"/owned\">" + t("See them on the Owned page") + "</a>")).append("</p>");
        if (mayWrite(store, patron)) {
            List<AccessRequests.Request> asking = new ArrayList<>();
            for (AccessRequests.Request r : AccessRequests.list(store)) if (r.state().equals("pending")) asking.add(r);
            if (!asking.isEmpty()) {
                b.append("<h2>").append(t("Asking to be let in")).append("</h2><table><tr><th>").append(t("who")).append("</th><th>").append(t("note")).append("</th><th>").append(tc("asked", "a column of the table of people asking to be let in: when they asked")).append("</th><th></th></tr>\n");
                for (AccessRequests.Request r : asking) b.append("<tr><td>").append(esc(r.name().isEmpty() ? r.did() : r.name())).append(r.did().startsWith("did:mail:") ? " <small>" + esc(r.did().substring(9)) + "</small>" : "").append("</td><td>").append(esc(r.note())).append("</td><td>").append(esc(Chrome.now().day(r.date()))).append("</td><td>")
                 .append("<form class=\"inline\" method=\"post\" action=\"/inbox\"><input type=\"hidden\" name=\"request\" value=\"").append(esc(r.id())).append("\"><button name=\"do\" value=\"let-in-read\">").append(t("Let in to read")).append("</button> <button name=\"do\" value=\"let-in-write\">").append(t("Let in to write")).append("</button> <input name=\"why\" size=\"18\" placeholder=\"").append(esc(t("why not"))).append("\"> <button name=\"do\" value=\"refuse\" class=\"quiet\">").append(t("Refuse")).append("</button></form></td></tr>\n");
                b.append("</table>\n");
            }
        }
        b.append("<p class=\"k\">").append(t("Claims waiting for your decision: new claims, and accepted claims whose review is out of date. Accept puts a claim into every answer from now on. Dispute records your reason. Retire stops using the claim but keeps the record. Tick any number, then choose.")).append("</p>");
        if (all.isEmpty()) { b.append("<p>").append(t("No claims are waiting. New claims appear here after a research run.")).append("</p>"); return page(store, patron, t("Inbox"), b.toString()); }
        b.append("<form method=\"get\" action=\"/inbox\" class=\"inline\">");
        for (var e : f.entrySet()) if (!e.getKey().equals("q")) b.append("<input type=\"hidden\" name=\"").append(esc(e.getKey())).append("\" value=\"").append(esc(e.getValue())).append("\">");
        b.append("<input name=\"q\" size=\"32\" value=\"").append(esc(f.getOrDefault("q", ""))).append("\" placeholder=\"").append(esc(t("words in the claim"))).append("\"> <button>").append(t("Find")).append("</button>")
         .append(f.isEmpty() ? "" : " <a class=\"k\" href=\"/inbox\">" + t("clear the filters") + "</a>").append("</form>");
        BiPredicate<ObjectNode, Map<String, String>> m = LibraryProtocol::inboxMatches;
        List<String[]> reports = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (ObjectNode o : all) { String id = o.path("report").asText(""); if (!id.isEmpty() && seen.add(id)) reports.add(new String[]{id, shortReport(id, o.path("report_title").asText(""))}); }
        if (reports.size() > 1 || (reports.size() == 1 && all.size() > reports.size())) b.append(facetRow("/inbox", m, all, f, "report", t("From the report"), reports, true));
        b.append(facetRow("/inbox", m, all, f, "state", t("Waiting because"), List.of(new String[]{"draft", t("new claim")}, new String[]{"stale", t("review is out of date")}), true));
        b.append(facetRow("/inbox", m, all, f, "kind", t("Kind"), List.of(new String[]{"extraction", t("extraction")}, new String[]{"synthesis", t("synthesis")}, new String[]{"interpretation", t("interpretation")}, new String[]{"speculation", t("speculation")}), true));
        List<String[]> tiers = new ArrayList<>(); for (SourceTier tier : SourceTier.values()) for (ObjectNode o : all) if (o.path("tier").asText("").equals(tier.name())) { tiers.add(new String[]{tier.name(), tier.name()}); break; }
        if (tiers.size() > 1) b.append(facetRow("/inbox", m, all, f, "tier", t("Strongest source"), tiers, true));
        b.append(facetRow("/inbox", m, all, f, "confidence", t("Confidence"), List.of(new String[]{"high", t("high")}, new String[]{"medium", t("medium")}, new String[]{"low", t("low")}), true));
        Map<String, Integer> writers = new TreeMap<>(); for (ObjectNode o : all) writers.merge(o.path("writer").asText(""), 1, Integer::sum);
        if (writers.size() > 1) { List<String[]> ws = new ArrayList<>(); for (String w : writers.keySet()) ws.add(new String[]{w, w}); b.append(facetRow("/inbox", m, all, f, "writer", t("Written by"), ws, true)); }
        Map<String, Integer> subjects = new TreeMap<>(); for (ObjectNode o : all) for (var x : o.path("subjects")) subjects.merge(x.asText(), 1, Integer::sum);
        if (!subjects.isEmpty()) { List<String[]> ss = new ArrayList<>(); for (String x : subjects.keySet()) ss.add(new String[]{x, x}); b.append(facetRow("/inbox", m, all, f, "subject", t("Subject"), ss, true)); }
        Map<String, Integer> langs = new TreeMap<>(); for (ObjectNode o : all) langs.merge(o.path("language").asText("english"), 1, Integer::sum);
        if (langs.size() > 1) { List<String[]> ls = new ArrayList<>(); for (String x : langs.keySet()) ls.add(new String[]{x, x}); b.append(facetRow("/inbox", m, all, f, "language", t("Language"), ls, true)); }
        String view = f.getOrDefault("view", reports.isEmpty() ? "list" : "report");
        b.append("<p class=\"k\">").append(t("View:")).append(" ").append(view.equals("report") ? "<b>" + t("by report") + "</b>" : "<a href=\"" + href("/inbox", f, "view", "report") + "\">" + t("by report") + "</a>").append(" · ")
         .append(view.equals("list") ? "<b>" + t("one list") + "</b>" : "<a href=\"" + href("/inbox", f, "view", "list") + "\">" + t("one list") + "</a>").append("</p>");
        List<ObjectNode> rows = new ArrayList<>();
        for (ObjectNode o : all) if (m.test(o, f)) rows.add(o);
        if (rows.isEmpty()) { b.append("<p>").append(t("No claims match these filters.")).append("</p>"); return page(store, patron, t("Inbox"), b.toString()); }
        b.append("<form method=\"post\" action=\"/inbox\" class=\"pick\">")
         .append("<div class=\"bar\"><label><input type=\"checkbox\" onclick=\"for(const c of this.form.querySelectorAll('input[name^=f]'))c.checked=this.checked\"> ").append(t("all")).append("</label> ")
         .append("<button name=\"do\" value=\"accept\">").append(t("Accept the ticked ones")).append("</button> <button name=\"do\" value=\"retire\">").append(t("Retire the ticked ones")).append("</button> ")
         .append("<span>").append(t("Dispute the ticked ones:")).append(" <input name=\"why\" size=\"36\" placeholder=\"").append(esc(t("why"))).append("\"> <button name=\"do\" value=\"dispute\">").append(t("Dispute")).append("</button></span></div>");
        Map<String, List<ObjectNode>> groups = new LinkedHashMap<>();
        for (ObjectNode o : rows) groups.computeIfAbsent(view.equals("report") ? o.path("report").asText("") : "", k -> new ArrayList<>()).add(o);
        int n = 0;
        for (var e : groups.entrySet()) {
            String id = e.getKey();
            if (view.equals("report")) {
                b.append("<h3 class=\"group\"><label><input type=\"checkbox\" onclick=\"for(const c of this.closest('h3').nextElementSibling.querySelectorAll('input[name^=f]'))c.checked=this.checked\"> ");
                if (id.isEmpty()) b.append(t("Not from a report"));
                else b.append(t("From {0}", idLink(id, shortReport(id, e.getValue().get(0).path("report_title").asText("")))));
                b.append(" <span class=\"k\">").append(e.getValue().size()).append("</span></label>");
                if (!id.isEmpty()) b.append(" <a class=\"k\" href=\"/questions?show=all&amp;report=").append(enc(id)).append("\">").append(t("its open questions")).append("</a>");
                b.append("</h3>");
            }
            b.append("<ul>");
            for (ObjectNode o : e.getValue()) {
                b.append("<li><label><input type=\"checkbox\" name=\"f").append(n++).append("\" value=\"").append(esc(o.path("id").asText())).append("\"> ")
                 .append("<a href=\"/entry/").append(enc(o.path("id").asText())).append("\">").append(esc(o.path("title").asText())).append("</a> <span class=\"k\">")
                 .append(o.path("stale").asBoolean() ? t("review out of date") : esc(t(o.path("state").asText()))).append(" · ").append(esc(t(o.path("kind").asText()))).append(" · ").append(esc(o.path("tier").asText()))
                 .append(" · ").append(esc(t(o.path("confidence").asText()))).append(" · ").append(esc(Chrome.now().day(o.path("date").asText())));
                for (var x : o.path("subjects")) b.append(" · ").append(esc(x.asText()));
                b.append("</span></label></li>");
            }
            b.append("</ul>");
        }
        b.append("</form>");
        return page(store, patron, t("Inbox"), b.toString());
    }

    /** Whether the patron may write, without throwing: the form is shown only to those who could use it. */
    static boolean mayWrite(LibraryStore store, Patrons.Patron patron) { return Patrons.mayWrite(store, patron); }

    /** The family's pages that take its word (Who is who, Decisions): for the people who may write, and a plain sentence for anyone else. */
    static void writersOnly(LibraryStore store, Patrons.Patron patron) {
        if (!mayWrite(store, patron)) throw ProtocolError.forbidden(t("This page is for the people who may change the library: its owner, and anyone the owner lets write to it.") + " "
                + t("What is answered here changes the library and its research. The family tree and the rest of the library are open to you."));
    }

    /** The remove form on an entry: for a report the three choices, for a claim the one; the plan is shown before anything goes. */
    static String removeForm(String id, String kind) {
        StringBuilder b = new StringBuilder("<details class=\"remove\"><summary class=\"k\">").append(t("Delete…")).append("</summary><form method=\"post\" action=\"/remove\"><input type=\"hidden\" name=\"id\" value=\"").append(esc(id)).append("\">");
        if ("investigation".equals(kind)) {
            b.append("<label><input type=\"radio\" name=\"what\" value=\"all\" checked> ").append(t("Delete the report and its claims")).append("</label><br>")
             .append("<label><input type=\"radio\" name=\"what\" value=\"report\"> ").append(t("Delete only the report. Keep its claims.")).append("</label><br>")
             .append("<label><input type=\"radio\" name=\"what\" value=\"claims\"> ").append(t("Delete only the claims. Keep the report.")).append("</label><br>");
        } else b.append("<input type=\"hidden\" name=\"what\" value=\"all\"><p class=\"k\">").append(t("Delete this claim. To keep the record but stop using it, retire it from the Inbox instead.")).append("</p>");
        b.append("<button class=\"quiet\">").append(t("Preview")).append("</button></form></details>");
        return b.toString();
    }

    /** POST /remove: the first post shows the plan with a Confirm button; the post with confirm=1 and an unspent token removes. */
    /** The settings page (0.5.5): every setting with what it is for and its value, a key only as set or not, and a form to change one. */
    static String settings(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        ObjectNode a = M.createObjectNode(); a.put("op", "list"); a.set("patron", LibrarianDaemon.patronNode(patron));
        JsonNode r = p.settings(a);
        StringBuilder b = new StringBuilder();
        String note = q.getOrDefault("note", "");
        if (!note.isEmpty()) b.append("<p class=\"note\">").append(esc(note)).append("</p>\n");
        b.append("<p>").append(t("These are the settings of this library, from {0}.", esc(r.path("config_file").asText()))).append(" ").append(t("A change takes effect at once, except where a setting says otherwise. A key is shown only as set or not.")).append("</p>\n");
        b.append(modelsSection(store)).append(spendSection(store));
        b.append(settingsForm(r));
        b.append(readersSection(store, patron, q));
        return page(store, patron, t("Settings"), b.toString(), 0, null, true);
    }

    /** The models a run may use, by name: the table on Settings and in Keep's fold. */
    static String modelsSection(LibraryStore store) {
        StringBuilder b = new StringBuilder();
        Map<String, ModelEntries.Entry> entries = ModelEntries.all();
        b.append("<h2>").append(t("Models")).append("</h2><p class=\"k\">").append(t("The models a run may use, by name (researchzosho models adds one; the research form, /model in the chat and the MCP tool pick one).")).append("</p><table><tr><th>").append(t("name")).append("</th><th>").append(t("model")).append("</th><th>").append(t("host")).append("</th><th>").append(t("prices per M")).append("</th><th>").append(t("cap")).append("</th><th>").append(t("key")).append("</th><th>").append(t("pictures")).append("</th></tr>");
        for (ModelEntries.Entry e : entries.values()) b.append("<tr><td>").append(esc(e.name())).append("</td><td>").append(esc(e.model())).append("</td><td>").append(esc(e.host())).append("</td><td>").append(esc(e.pricesText())).append("</td><td>").append(e.cap() > 0 ? t("{0} a month", Spend.dollars(e.cap())) : "").append("</td><td>").append(!e.hosted() ? t("none needed") : DriveClient.hostKey(e.host()) != null || (e.name().equals(ModelEntries.DEFAULT) && Config.get("RESEARCHZOSHO_API_KEY") != null) ? t("kept") : "<b>" + t("none") + "</b>").append("</td><td>").append(t(e.seesText())).append("</td></tr>");
        b.append("</table>");
        // which entry pictures go to (0.7.0): the probe's three words stand in the table; the choice is said under it
        ModelEntries.Entry eyes = null; try { eyes = ModelEntries.seeing(); } catch (RuntimeException ignored) { }
        b.append("<p class=\"k\">").append(eyes != null ? esc(t("Pictures go to {0} ({1}): the chat's judgements, the research runs' picture tools and the descriptions of the pictures in added documents.", eyes.name(), eyes.model()))
                : esc(t("No entry is known to see pictures: researchzosho models test <name> probes one with a small coloured square, and RESEARCHZOSHO_VISION_DRIVE names a server for pictures outright."))).append("</p>");
        b.append("<p class=\"k\">").append(esc(!LikenessHelper.on() ? t("The likeness helper is off (RESEARCHZOSHO_LIKENESS=off): judgements of likeness rest on the seeing model's reading alone.")
                : LikenessHelper.installed() ? t("The likeness helper is set up: a judgement of likeness measures the faces and the pictures first, and the seeing model judges in that order.")
                : t("No likeness helper is set up: judgements of likeness rest on the seeing model's reading alone. researchzosho images likeness install sets it up (about 2 GB, on the CPU)."))).append("</p>");
        b.append("<form method=\"post\" action=\"/settings\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"model_add\"><label class=\"small k\">").append(t("Add a model entry")).append("</label> ")
         .append("<input name=\"name\" required placeholder=\"").append(esc(t("name, such as deepseek"))).append("\"> <input name=\"drive\" required placeholder=\"").append(esc(t("openrouter, anthropic, openai, gemini, nebius, huggingface, or an address"))).append("\"> ")
         .append("<input name=\"model\" required placeholder=\"").append(esc(t("model id, such as deepseek/deepseek-v4.1-flash"))).append("\"> <input name=\"prices\" placeholder=\"").append(esc(t("prices per M: in/out/cache"))).append("\"> <input name=\"cap\" size=\"6\" placeholder=\"").append(esc(t("cap, dollars a month"))).append("\"> <button>").append(t("Add")).append("</button></form>");
        b.append("<form method=\"post\" action=\"/settings\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"model_key\"><label class=\"small k\">").append(t("Keep a host's key")).append("</label> ")
         .append("<input name=\"host\" required placeholder=\"").append(esc(t("openrouter, anthropic, openai, gemini, nebius, huggingface"))).append("\"> <input type=\"password\" name=\"value\" autocomplete=\"off\" placeholder=\"").append(esc(t("the key; empty forgets it"))).append("\"> <button>").append(tc("Keep", "a button that saves the thing beside it")).append("</button></form>");
        b.append("<p class=\"k\">").append(t("A hosted entry runs on its host's key; without one it is listed but no run uses it. Keys come from the provider: openrouter.ai/keys, console.anthropic.com, platform.openai.com, aistudio.google.com, tokenfactory.nebius.com, huggingface.co/settings/tokens.")).append("</p>");
        return b.toString();
    }

    /** Beside a row's dollars: the runs at prices the entry did not state, apart, with the tokens their servers counted; "" for none. */
    static String unpricedCell(JsonNode row) {
        String calls = row.has("calls_all") ? "<br><span class=\"k\">" + esc(t("of which outside a run: {0}", t("{0} this month, {1} in all", Spend.dollars(row.path("calls_month").asDouble()), Spend.dollars(row.path("calls_all").asDouble())))) + "</span>" : "";
        if (row.path("unpriced_calls_all").asInt() > 0) calls += "<br><span class=\"k\">" + esc(t("outside a run, at prices the entry does not state: {0} tokens sent, {1} written this month",
                Chrome.now().number(row.path("unpriced_calls_sent_month").asLong()), Chrome.now().number(row.path("unpriced_calls_written_month").asLong()))) + "</span>";
        if (row.path("unpriced_runs_all").asInt() == 0) return calls;
        return calls + "<br><span class=\"k\">" + esc(t("runs at prices the entry does not state: {0} this month ({1} tokens sent, {2} written), {3} in all", row.path("unpriced_runs_month").asInt(),
                Chrome.now().number(row.path("unpriced_sent_month").asLong()), Chrome.now().number(row.path("unpriced_written_month").asLong()), row.path("unpriced_runs_all").asInt())) + "</span>";
    }

    /** What the model calls cost at each model's list prices, this month and in all, by host, named model and use; nothing when nothing was spent. */
    static String spendSection(LibraryStore store) throws IOException {
        StringBuilder b = new StringBuilder();
        ObjectNode sum = Spend.summary(store);
        if (!sum.path("hosts").isEmpty()) {
            b.append("<h2>").append(t("Spend")).append("</h2><p class=\"k\">").append(t("What the model calls cost at each model's list prices, this month ({0}) and in all: the library's arithmetic on the servers' token counts, not a bill.", esc(sum.path("month").asText()))).append("</p><table><tr><th>").append(t("host")).append("</th><th>").append(t("this month")).append("</th><th>").append(t("in all")).append("</th></tr>");
        for (JsonNode row : sum.path("hosts")) b.append("<tr><td>").append(esc(row.path("name").asText())).append("</td><td>").append(Spend.dollars(row.path("month").asDouble())).append(" (").append(n(row.path("runs_month").asInt() - row.path("unpriced_runs_month").asInt(), "{0} run", "{0} runs")).append(")").append(unpricedCell(row)).append("</td><td>").append(Spend.dollars(row.path("all").asDouble())).append(" (").append(row.path("runs_all").asInt() - row.path("unpriced_runs_all").asInt()).append(")</td></tr>");
            b.append("</table><table><tr><th>").append(t("named model")).append("</th><th>").append(t("this month")).append("</th><th>").append(t("in all")).append("</th><th>").append(t("cap")).append("</th></tr>");
            for (JsonNode row : sum.path("entries")) b.append("<tr><td>").append(esc(row.path("name").asText())).append("</td><td>").append(Spend.dollars(row.path("month").asDouble())).append(unpricedCell(row)).append("</td><td>").append(Spend.dollars(row.path("all").asDouble())).append("</td><td>")
                    .append(row.has("cap") ? Spend.dollars(row.path("cap").asDouble()) + (row.path("cap_counts").asBoolean(true) ? "" : esc(t(", which counts nothing until the entry has prices"))) : "").append("</td></tr>");
            b.append("</table>");
            // what the calls were for: the chat, the nightly work, research, translation, pictures, other work
            b.append("<table><tr><th>").append(t("use")).append("</th><th>").append(t("this month")).append("</th><th>").append(t("in all")).append("</th></tr>");
            for (JsonNode u : sum.path("uses")) {
                boolean priced = u.path("priced").asBoolean(true);
                b.append("<tr><td>").append(esc(Spend.useSaid(u.path("for").asText(), Chrome.now()))).append("</td><td>").append(priced ? Spend.dollars(u.path("month").asDouble()) : "")
                        .append(u.has("unpriced_sent_month") ? (priced ? "<br>" : "") + "<span class=\"k\">" + esc(t("at prices the entry does not state: {0} tokens sent, {1} written this month", Chrome.now().number(u.path("unpriced_sent_month").asLong()), Chrome.now().number(u.path("unpriced_written_month").asLong()))) + "</span>" : "")
                        .append("</td><td>").append(priced ? Spend.dollars(u.path("all").asDouble()) : "").append("</td></tr>");
            }
            b.append("</table>");
        }
        return b.toString();
    }

    /** The settings form and the table of every setting with its value and what it is for. */
    static String settingsForm(JsonNode r) {
        StringBuilder b = new StringBuilder();
        b.append("<form method=\"post\" action=\"/settings\"><p><label>").append(t("Setting")).append(" <select name=\"name\">");
        for (JsonNode s : r.path("settings")) b.append("<option value=\"").append(esc(s.path("name").asText())).append("\">").append(esc(s.path("name").asText())).append("</option>");
        b.append("</select></label> <label>").append(t("Value")).append(" <input name=\"value\" size=\"50\" placeholder=\"").append(esc(t("leave empty to unset"))).append("\"></label> <button type=\"submit\">").append(t("Save")).append("</button></p></form>\n");
        b.append("<table><tr><th>").append(t("setting")).append("</th><th>").append(t("value")).append("</th><th>").append(t("what it is for")).append("</th></tr>\n");
        for (JsonNode s : r.path("settings")) {   // what a setting is for stays as written, in English, for now
            String v = s.path("value").asText("");
            b.append("<tr><td><code>").append(esc(s.path("name").asText())).append("</code></td><td>").append(v.isEmpty() ? "<i>" + t("not set") + "</i>" + (s.hasNonNull("example") && !s.path("example").asText().isEmpty() ? " <small>" + t("for example {0}", esc(s.path("example").asText())) + "</small>" : "") : esc(v))
             .append("</td><td>").append(esc(s.path("what").asText())).append(s.has("choices") ? " <small>(" + esc(String.join(", ", toStrings(s.path("choices")))) + ")</small>" : "").append("</td></tr>\n");
        }
        b.append("</table>\n<p>").append(t("The same from a terminal: {0}; and in the chat, in a sentence.", "<code>researchzosho settings</code>")).append("</p>\n");
        return b.toString();
    }

    /** Who may read and write the library, with the invitation and the readers' forms; nothing for a reader. */
    static String readersSection(LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException {
        StringBuilder b = new StringBuilder();
        if (mayWrite(store, patron)) {
            b.append("<h2>").append(t("Readers")).append("</h2><p class=\"k\">").append(t("Who may read and write this library. Invite a person by name: the link, opened within seven days, lets them choose a password; then they sign in with their name and it, from any phone or computer. \"New link\" is for a forgotten password; \"Set\" puts in a password you typed for them.")).append("</p>\n");
            String joined = q.getOrDefault("join", "");
            if (!joined.isEmpty()) b.append("<p class=\"note\">").append(t("Send this link; it works once, for seven days:")).append(" <code>").append(esc(joined)).append("</code></p>\n");
            String token = q.getOrDefault("token", "");
            if (!token.isEmpty()) b.append("<p class=\"note\">").append(t("A token for {0}, shown this once; a program sends it as Authorization: Bearer <token>:", esc(q.getOrDefault("did", "")))).append(" <code>").append(esc(token)).append("</code></p>\n");
            b.append("<form method=\"post\" action=\"/settings\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"invite\"><label>").append(t("Name")).append(" <input name=\"name\" size=\"24\"></label> <label>").append(t("May")).append(" <select name=\"level\"><option value=\"read\">").append(t("read")).append("</option><option value=\"write\">").append(t("write")).append("</option></select></label> <button>").append(t("Invite")).append("</button></form>\n");
            Patrons.Policy pol = Patrons.load(store);
            b.append("<table><tr><th>").append(t("who")).append("</th><th>").append(t("may")).append("</th><th>").append(t("signs in with")).append("</th><th></th></tr>\n<tr><td><i>").append(t("anyone not listed")).append("</i></td><td>").append(t(String.valueOf(pol.dflt()))).append("</td><td></td><td></td></tr>\n");
            for (Patrons.Entry e : pol.listed()) {
                Accounts.Account acc = Accounts.byDid(store, e.did());
                String ways = acc == null ? (e.tokenHash() == null ? t("nothing yet") : t("a token (a program)")) : acc.ways(Chrome.now());
                b.append("<tr><td>").append(esc(e.name().isEmpty() ? e.did() : e.name())).append(" <small>").append(esc(e.did())).append("</small></td><td>").append(t(String.valueOf(e.level()))).append("</td><td>").append(esc(ways)).append("</td><td><form class=\"inline\" method=\"post\" action=\"/settings\"><input type=\"hidden\" name=\"did\" value=\"").append(esc(e.did())).append("\"><button name=\"op\" value=\"reader-read\" class=\"quiet\">").append(t("read")).append("</button> <button name=\"op\" value=\"reader-write\" class=\"quiet\">").append(t("write")).append("</button> <button name=\"op\" value=\"reader-deny\" class=\"quiet\">").append(tc("deny", "a button on a reader's row that refuses them")).append("</button> <button name=\"op\" value=\"reader-remove\" class=\"quiet\">").append(t("remove")).append("</button> <button name=\"op\" value=\"reader-link\" class=\"quiet\">").append(t("new link")).append("</button> <input type=\"password\" name=\"password\" size=\"12\" placeholder=\"").append(esc(t("a password"))).append("\" autocomplete=\"off\"> <button name=\"op\" value=\"reader-password\" class=\"quiet\">").append(tc("set", "a button that sets the password written beside it")).append("</button></form></td></tr>\n");
            }
            b.append("</table>\n");
            // what anyone not listed may do; a program's id listed by hand; a token for one (until 2026-11-07 the terminal's alone)
            b.append("<form method=\"post\" action=\"/settings\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"reader-default\"><label>").append(t("Anyone not listed may")).append(" <select name=\"level\">");
            for (Patrons.Level l : Patrons.Level.values()) b.append("<option value=\"").append(l).append("\"").append(l == pol.dflt() ? " selected" : "").append(">").append(t(String.valueOf(l))).append("</option>");
            b.append("</select></label> <button class=\"btn\">").append(t("Set")).append("</button></form>\n");
            b.append("<form method=\"post\" action=\"/settings\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"reader-allow\"><label>").append(t("List an id")).append(" <input name=\"did\" size=\"28\" placeholder=\"did:key:…\"></label> <label>").append(t("Name")).append(" <input name=\"name\" size=\"16\"></label> <label>").append(t("May")).append(" <select name=\"level\"><option value=\"read\">").append(t("read")).append("</option><option value=\"write\">").append(t("write")).append("</option><option value=\"deny\">").append(t("deny")).append("</option></select></label> <button class=\"btn\">").append(t("List it")).append("</button></form>\n");
            b.append("<form method=\"post\" action=\"/settings\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"reader-token\"><label>").append(t("A token for a listed reader's program")).append(" <input name=\"did\" size=\"28\" placeholder=\"did:key:…\"></label> <button class=\"btn\">").append(t("Make the token")).append("</button></form>\n");
            b.append("<p class=\"k\">").append(t("The same from a terminal: {0}.", "<code>researchzosho reader invite \"a name\" read</code>, <code>reader link \"a name\"</code>, <code>reader password \"a name\"</code>, <code>reader list</code>, <code>reader allow</code>, <code>reader remove</code>")).append(" ").append(t("Your own account: {0}.", "<a href=\"/account\">" + t("Account") + "</a>")).append("</p>\n");
        }
        return b.toString();
    }

    /** {@code /owned}: what the person owns, from their own records — a search box over it and what the ledger holds. */
    static String owned(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        ObjectNode st = M.createObjectNode(); st.put("op", "status"); st.set("patron", LibrarianDaemon.patronNode(patron));
        JsonNode status = p.owned(st);
        String words = q.getOrDefault("q", "").strip(), year = q.getOrDefault("year", "").strip(), shop = q.getOrDefault("shop", "").strip();
        StringBuilder b = new StringBuilder();
        b.append("<p>").append(esc(ownedSaid(status))).append("</p>\n");
        List<Recalls.Hit> recallHits = Recalls.hits(store, true);
        if (!recallHits.isEmpty()) {
            b.append("<h2>").append(t("Recall notices that may concern what you own")).append("</h2><table><tr><th>").append(t("notice")).append("</th><th>").append(t("you have")).append("</th><th>").append(t("found by")).append("</th><th></th></tr>\n");
            for (Recalls.Hit h : recallHits) {
                b.append("<tr><td><a href=\"").append(esc(h.noticeUrl())).append("\">").append(esc(h.noticeTitle())).append("</a> <small>").append(esc(h.feed().toUpperCase(Locale.ROOT))).append(", ").append(esc(h.noticeDate())).append("</small></td>")
                 .append("<td>").append(esc(h.lineTitle())).append(" <small>").append(esc(h.shop())).append(", ").append(esc(h.lineDate())).append("</small></td>")
                 .append("<td>").append(esc(h.why())).append(" <small>(").append(esc(h.tier())).append(")</small></td>")
                 .append("<td><form class=\"inline\" method=\"post\" action=\"/owned\"><input type=\"hidden\" name=\"dismiss\" value=\"").append(esc(h.id())).append("\"><button class=\"quiet\">").append(t("Put away")).append("</button></form></td></tr>\n");
            }
            b.append("</table>\n<p class=\"k\">").append(t("A notice is matched by a barcode (exact), by brand and model number (high), or by the maker named in the notice and the words of the name (medium). Putting one away says it is not yours or is dealt with.")).append("</p>\n");
        }
        b.append("<form method=\"get\" action=\"/owned\"><p><input name=\"q\" size=\"40\" value=\"").append(esc(words)).append("\" placeholder=\"").append(esc(t("words of the thing, the shop or the order number"))).append("\"> ")
         .append("<input name=\"year\" size=\"6\" value=\"").append(esc(year)).append("\" placeholder=\"").append(esc(t("year"))).append("\"> <input name=\"shop\" size=\"14\" value=\"").append(esc(shop)).append("\" placeholder=\"").append(esc(t("shop"))).append("\"> <button type=\"submit\">").append(t("Find")).append("</button></p></form>\n");
        if (status.path("lines").asInt() > 0 && (!words.isEmpty() || !year.isEmpty() || !shop.isEmpty() || "1".equals(q.get("all")))) {
            ObjectNode f = M.createObjectNode(); f.put("op", "find"); f.put("words", words); f.put("year", year); f.put("shop", shop); f.put("limit", 200); f.set("patron", LibrarianDaemon.patronNode(patron));
            JsonNode found = p.owned(f);
            int count = found.path("count").asInt(), limit = 200;
            b.append("<p>").append(esc(count == 0 ? t("Nothing in what you own matches.") : count >= limit ? t("The first {0} lines, newest first.", count) : n(count, "{0} line, newest first.", "{0} lines, newest first."))).append("</p>\n<table><tr><th>").append(t("date")).append("</th><th>").append(t("shop")).append("</th><th>").append(t("what")).append("</th><th>").append(t("paid")).append("</th></tr>\n");
            for (JsonNode l : found.path("lines")) {
                String price = l.path("total").asText().isEmpty() ? l.path("unit_price").asText() : l.path("total").asText();
                b.append("<tr><td>").append(esc(Chrome.now().day(l.path("date").asText()))).append("</td><td>").append(esc(l.path("shop").asText())).append("</td><td>").append(esc(l.path("title").asText()))
                 .append(l.path("qty").asInt(1) > 1 ? " ×" + l.path("qty").asInt() : "").append(l.path("confidence").asText().equals("guessed") ? " <small>(" + t("read from the mail's text; may be off") + ")</small>" : "")
                 .append("</td><td>").append(esc((price + " " + l.path("currency").asText()).strip())).append("</td></tr>\n");
            }
            b.append("</table>\n");
        } else if (status.path("lines").asInt() > 0) {
            b.append("<p><a href=\"/owned?all=1\">").append(t("Show the newest 200")).append("</a></p>\n");
        }
        if (status.path("sources").size() > 0) {
            b.append("<h2>").append(t("Where it comes from")).append("</h2><table><tr><th>").append(t("file")).append("</th><th>").append(t("kind")).append("</th><th>").append(t("lines")).append("</th><th>").append(t("records")).append("</th></tr>\n");
            for (JsonNode s : status.path("sources")) b.append("<tr><td>").append(esc(s.path("file").asText())).append("</td><td>").append(esc(s.path("kind").asText())).append("</td><td>").append(s.path("lines").asInt())
                    .append("</td><td>").append(esc(s.path("records_begin").asText().isEmpty() ? t("no dates") : t("{0} to {1}", Chrome.now().day(s.path("records_begin").asText()), Chrome.now().day(s.path("records_end").asText())))).append("</td></tr>\n");
            b.append("</table>\n<p>").append(t("Where a file's records begin is its first record, not when the account was opened.")).append("</p>\n");
        }
        b.append("<p>").append(t("To add records: {0} on the machine the library runs on, with the zip a shop's data request returns, a mailbox file or folder, or a statement as CSV. The ledger is a reference the research and the chat may use; nothing needs it.", "<code>researchzosho owned import &lt;file&gt;</code>")).append("</p>\n");
        return page(store, patron, t("Owned"), b.toString(), 0, null, true);
    }

    /**
     * What the owned ledger holds, in the person's language: the page's own sentence from the counts of library_owned's status (whose
     * summary is the protocol's English); where each file's records begin is the table below it.
     */
    static String ownedSaid(JsonNode st) {
        int lines = st.path("lines").asInt();
        if (lines == 0) return t("Nothing is imported yet.");
        return t("Lines: {0}. Purchases: {1}. Receipts for services: {2}. Statement payments: {3}. Files: {4}.", Chrome.now().number(lines), Chrome.now().number(st.path("purchases").asInt()),
                Chrome.now().number(st.path("receipts").asInt()), Chrome.now().number(st.path("payments").asInt()), Chrome.now().number(st.path("sources").size()));
    }

    private static void ownedPost(LibraryProtocol p, Patrons.Patron patron, Map<String, String> form) throws IOException {
        String dismiss = form.getOrDefault("dismiss", "").strip();
        if (dismiss.isEmpty()) return;
        ObjectNode a = M.createObjectNode(); a.put("op", "recalls"); a.put("hit", dismiss); a.set("patron", LibrarianDaemon.patronNode(patron));
        p.owned(a);
    }

    private static String settingsPost(LibraryProtocol p, Patrons.Patron patron, Map<String, String> form) throws IOException {
        ObjectNode a = M.createObjectNode(); a.set("patron", LibrarianDaemon.patronNode(patron));
        String name = form.getOrDefault("name", ""), value = form.getOrDefault("value", "");
        a.put("op", value.isBlank() ? "unset" : "set"); a.put("name", name); a.put("value", value);
        try { return p.settings(a).path("note").asText(); } catch (ProtocolError e) { return e.getMessage(); }
    }

    private static List<String> toStrings(JsonNode arr) { List<String> out = new ArrayList<>(); for (JsonNode x : arr) out.add(x.asText("")); return out; }

    private static String removePost(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);
        String id = form.getOrDefault("id", "").strip(), what = form.getOrDefault("what", "all");
        ObjectNode a = M.createObjectNode().put("id", id).put("what", what);
        a.set("patron", LibrarianDaemon.patronNode(patron));
        boolean confirm = "1".equals(form.get("confirm"));
        String once = form.getOrDefault("once", "");
        if (confirm && (once.isEmpty() || SENT.containsKey(once))) confirm = false;   // a refresh or a second click does not remove twice
        ObjectNode r = p.remove(a.deepCopy().put("dry", !confirm));
        StringBuilder b = new StringBuilder();
        if (!confirm) {
            b.append("<pre>").append(esc(r.path("plan").asText())).append("</pre>");
            if (r.path("claims_go").size() == 0 && !r.path("report_goes").asBoolean()) b.append("<p>").append(t("Nothing to delete.")).append("</p>");
            else b.append("<form method=\"post\" action=\"/remove\"><input type=\"hidden\" name=\"id\" value=\"").append(esc(id)).append("\"><input type=\"hidden\" name=\"what\" value=\"").append(esc(what)).append("\"><input type=\"hidden\" name=\"confirm\" value=\"1\">")
                  .append(nonceField()).append("<button>").append(t("Delete")).append("</button> <a class=\"quiet\" href=\"/entry/").append(enc(id)).append("\">").append(t("Cancel")).append("</a></form><p class=\"k\">").append(t("This cannot be undone.")).append("</p>");
            return page(store, patron, t("Delete {0}", id), b.toString());
        }
        SENT.put(once, id);
        b.append("<p>").append(esc(r.path("summary").asText())).append("</p><ul>");
        for (JsonNode x : r.path("removed")) b.append("<li>").append(esc(x.asText())).append("</li>");
        b.append("</ul><p class=\"k\">").append(t("This is recorded in the {0}.", "<a href=\"/changes\">" + t("change log") + "</a>")).append("</p>");
        return page(store, patron, t("Deleted"), b.toString());
    }

    /** The ticked claims decided. */
    static void inboxPost(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);
        String decision = form.getOrDefault("do", "");
        if (decision.startsWith("let-in-") || decision.equals("refuse")) {
            // someone asking to be let in: the owner's answer from the inbox's buttons
            String id = form.getOrDefault("request", "").strip();
            if (decision.equals("refuse")) AccessRequests.deny(store, id, form.getOrDefault("why", "").strip());
            else AccessRequests.approve(store, id, decision.endsWith("write") ? Patrons.Level.write : Patrons.Level.read);
            return;
        }
        if (decision.equals("accept-report")) {
            // a report that waited for the keeper's eye: accepted as it stands
            String id = form.getOrDefault("report", "").strip();
            if (store.investigation(id) == null) throw ProtocolError.notFound("No report has the id " + id + ".");
            ReportStanding.accept(store, id, ReportStanding.PERSON);
            return;
        }
        Council c = new Council(store);
        List<String> ids = new ArrayList<>();
        for (var e : form.entrySet()) if (e.getKey().startsWith("f") && e.getKey().substring(1).matches("\\d+") && !e.getValue().isBlank()) ids.add(e.getValue());
        String why = form.getOrDefault("why", "").strip();
        for (String id : ids) {
            switch (form.getOrDefault("do", "")) {
                case "accept" -> c.accept(id);
                case "retire" -> c.retire(id);
                case "dispute" -> c.dispute(id, why.isEmpty() ? "disputed from the inbox page" : why);
                default -> throw ProtocolError.invalidArgs("That decision is not known. Go back and try again.");
            }
        }
    }

    private static String slugOf(String query) {
        String s = query.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.length() > 24 ? s.substring(0, 24).replaceAll("-+$", "") : s.isEmpty() ? "search" : s;
    }

    static String jobs(LibraryStore store, LibraryProtocol p, Patrons.Patron patron) throws IOException { return jobs(store, p, patron, Map.of()); }

    /** {@code told}: the runs just sent from the questions page whose question looks like a field's; the page says so at its top. */
    static String jobs(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        ObjectNode a = args(patron); a.put("limit", 40);
        ObjectNode r = p.job(a);
        StringBuilder b = new StringBuilder();
        b.append(toldAbout(store, q.getOrDefault("told", "")));
        b.append(tonight(store));
        b.append("<h2>").append(t("Running and waiting")).append("</h2>");
        boolean paused = r.path("paused").asBoolean();
        b.append("<form method=\"post\" action=\"/jobs\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"").append(paused ? "resume" : "pause").append("\">")
         .append(paused ? "<span class=\"badge\">" + t("paused") + "</span> " + t("Research is paused: queued runs wait, and a running one pauses at its next turn.") + " <button>" + t("Resume") + "</button>" : "<button>" + t("Pause research") + "</button> <span class=\"k\">" + t("queued runs wait, a running one pauses at its next turn, and nightly maintenance still runs") + "</span>")
         .append("</form>");
        Jobs jobs = new Jobs(store, j -> "");
        long typical = TYPICAL.getAsLong(), now = System.currentTimeMillis();
        if (r.path("active").size() == 0) b.append("<p class=\"k\">").append(t("Nothing is running.")).append("</p>");
        else {
            b.append("<div class=\"strip\">");
            for (JsonNode j : r.path("active")) { ObjectNode raw = jobs.get(j.path("job_id").asText()); b.append(rline(RunProgress.of(j, now, typical), raw == null ? j.path("job_id").asText() : runName(store, raw), j.path("waiting").asText(""), RunProgress.resumed(j))); }
            b.append("</div>");
        }
        b.append("<h2>").append(t("Finished")).append("</h2>");
        if (r.path("finished").size() == 0) b.append("<p class=\"k\">").append(t("None yet.")).append("</p>");
        else {
            b.append("<div class=\"strip\">");
            for (JsonNode j : r.path("finished")) { ObjectNode raw = jobs.get(j.path("job_id").asText()); b.append(rline(RunProgress.of(j, now, typical), raw == null ? j.path("job_id").asText() : runName(store, raw), "")); }
            b.append("</div>");
        }
        return page(store, patron, t("Runs"), b.toString());
    }

    /** The Runs page's forms: pause or resume the runner, stop one run. */
    /**
     * The run page's button: a translation's units taken off the record its checks are measured from, by someone who may write to the
     * library; what left and the thresholds now, with the way back to the run. A reader who may only read is refused, as is an id that is
     * no translation run.
     */
    static String withdrawPost(LibraryStore store, Patrons.Patron patron, String id) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);
        ObjectNode job = new Jobs(store, j -> "").get(id);
        if (job == null || !Translation.JOB_KIND.equals(job.path("kind").asText()) || !Jobs.visibleTo(patron, job)) throw ProtocolError.notFound(t("No translation run has the id {0}.", id));
        return "<p>" + esc(TranslationScores.withdrawnSaid(store, TranslationScores.withdraw(store, id), Chrome.now())) + "</p><p><a href=\"/jobs/" + enc(id) + "\">" + t("Back to the run") + "</a></p>";
    }

    private static void jobsPost(LibraryProtocol p, Patrons.Patron patron, Map<String, String> form) throws IOException {
        ObjectNode a = args(patron);
        String op = form.getOrDefault("op", "");
        if (!op.equals("pause") && !op.equals("resume") && !op.equals("stop")) throw ProtocolError.invalidArgs(t("This form is not known. Go back and try again."));
        a.put("op", op);
        if (op.equals("stop")) a.put("job_id", form.getOrDefault("job_id", ""));
        p.job(a);
    }

    /** What the housekeeping will do at its next run, from its own plan. */
    static String tonight(LibraryStore store) throws IOException {
        Tonight.Plan t = Tonight.plan(store);
        StringBuilder b = new StringBuilder();
        b.append("<h2>").append(t("Tonight")).append("</h2><p>").append(t("Nightly maintenance runs at {0} on {1}.", String.format("%02d:00", t.hour()), Chrome.now().day(String.valueOf(t.date())))).append("</p>");
        b.append("<p><b>").append(t("Searches you keep.")).append("</b> ");
        if (t.due().isEmpty() && t.later().isEmpty()) b.append(t("None yet. Add one with {0}.", "<code>researchzosho shelf add &lt;name&gt; &lt;query&gt; [days]</code>")).append("</p>");
        else {
            b.append("</p><ul class=\"tight\">");
            for (Serials.Shelf s : t.due()) b.append("<li><b>").append(t("runs tonight")).append("</b> ").append(esc(s.slug())).append(": ").append(esc(s.query())).append(" <span class=\"k\">(").append(t("every {0} days, last {1}", s.everyDays(), esc(Chrome.now().day(s.lastChecked())))).append(")</span></li>");
            for (Serials.Shelf s : t.later()) b.append("<li><span class=\"k\">").append(s.parked() ? t("parked") : t("not yet due")).append("</span> ").append(esc(s.slug())).append(": ").append(esc(s.query())).append(" <span class=\"k\">(").append(t("every {0} days, last {1}", s.everyDays(), esc(Chrome.now().day(s.lastChecked())))).append(")</span></li>");
            b.append("</ul>");
        }
        b.append("<p><b>").append(t("Open questions it will research:")).append("</b> ").append(t("{0} research run(s) tonight", t.budget()))
         .append(t.override() > 0 ? " (" + t("set for tonight only; the usual number is {0}", t.standing()) + ")" : "").append(". ").append(t("Related questions share a run.")).append(" ")
         .append(t("Reorder, park, or drop them on the {0} page.", "<a href=\"/questions\">" + t("Open questions") + "</a>")).append("</p>");
        if (t.bundles().isEmpty()) b.append("<p class=\"k\">").append(t("None: no queued question for the nightly research to take.")).append("</p>");
        else {
            b.append("<ol class=\"tight\">");
            for (Crews.Bundle bd : t.bundles()) {
                b.append("<li>").append(esc(bd.question())).append(" <span class=\"k\">").append(esc(t(bd.head().type()))).append("</span>");
                List<Frontier.Line> more = bd.more();
                if (!more.isEmpty()) { b.append("<ul class=\"tight\">"); for (Frontier.Line l : more) b.append("<li class=\"k\">+ ").append(esc(Frontier.strip(l.text()))).append("</li>"); b.append("</ul>"); }
                b.append("</li>");
            }
            b.append("</ol>");
            if (t.stillOpen() > 0) b.append("<p class=\"k\">… ").append(t("{0} more queued for later nights", t.stillOpen())).append(t.parked() > 0 ? "; " + t("{0} parked", t.parked()) : "").append(".</p>");
        }
        if (Shelf.path() != null) {
            int minutes = Config.getInt(ShelfDna.MINUTES_KEY, ShelfDna.MINUTES);
            int labelled = Dna.counts(Dna.BOOK)[1];
            b.append("<p class=\"k\">").append(t("Your shelf:")).append(" ").append(minutes > 0
                ? t("its books are read for their DNA for {0} minutes, the ones you went for first ({1} labelled so far).", minutes, labelled)
                : t("the reading of its books for their DNA is turned off (dna_minutes is 0); {0} labelled so far.", labelled)).append("</p>");
        }
        b.append("<p class=\"k\">").append(t("Also:")).append(" ").append(t("{0} accepted claims checked against their sources again; new reports reviewed and given subjects; preprints and retractions checked; the search index refreshed; a backup made.", t.inventoryPerNight()))
         .append(t.weekly() ? " " + t("Weekly extras tonight: the list of duplicate claims and the list of saved pages nothing refers to.") : "")
         .append(t.monthly() ? " " + t("Monthly extra tonight: the search index is rebuilt.") : "").append("</p>");
        return b.toString();
    }

    static String job(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, String id, Map<String, String> q) throws IOException {
        ObjectNode a = args(patron); a.put("job_id", id);
        JsonNode j = p.job(a).path("job");
        String state = j.path("state").asText();
        boolean live = "queued".equals(state) || "running".equals(state);
        String back = q.getOrDefault("back", "");
        if (!back.startsWith("/explain?")) back = "";
        StringBuilder b = new StringBuilder();
        if (live) {
            b.append("<div class=\"working\"><span class=\"spin\"></span><div><p><b>").append("queued".equals(state) ? t("Waiting its turn…") : t("Working on it…")).append("</b></p>");
            b.append("<p class=\"k\">").append(t("{0} s so far.", j.path("elapsed_s").asLong())).append(" ").append(j.path("args").path("quick").asBoolean() || j.path("quick").asBoolean() ? t("A quick lookup: a few minutes.") + " " : "")
             .append(back.isEmpty() ? t("This page updates itself.") : t("This page updates itself, and goes back to the explanation when the answer is ready.")).append("</p></div></div>");
        } else if (!back.isEmpty()) {
            b.append("<p>").append(t("Done.")).append(" <a href=\"").append(esc(back)).append("\">").append(t("Read the explanation")).append("</a>.</p>");
        }
        b.append("<p class=\"k\">").append(esc(t(j.path("kind").asText()))).append(" · ").append(badge(j.path("state").asText())).append(" · ").append(t("queued {0}", when(j.path("queued_at").asText())));
        if (j.hasNonNull("started_at")) b.append(" · ").append(t("started {0}", when(j.path("started_at").asText())));
        if (j.hasNonNull("ended_at")) b.append(" · ").append(t("ended {0}", when(j.path("ended_at").asText())));
        b.append(" · ").append(j.path("elapsed_s").asLong()).append(" s");
        if (j.hasNonNull("drive")) b.append(" · ").append(t("on {0}", esc(j.path("drive").asText())));
        b.append("</p>");
        ObjectNode filedJob = new Jobs(store, x -> "").get(id);
        PdfReading.Preflight read = filedJob == null ? null : PdfReading.fromJson(filedJob.path("args").get("pdf_preflight"));   // a PDF's translation: what the reader found, as its plan said
        if (read != null) b.append("<p class=\"k\">").append(esc(PdfReading.said(read, Chrome.now()))).append("</p>");
        String who = j.path("translation").isObject() ? TranslationWho.said(j.path("translation"), Chrome.now()) : "";   // a document's translation: who translates, who reads, where the scores are made, the tier, the notes, the direction
        if (!who.isEmpty() && !j.path("result").asText("").contains(who)) b.append("<p>").append(esc(who)).append("</p>");   // once: a finished run's result says it
        if (who.isEmpty() && j.hasNonNull("notes")) { ObjectNode raw = filedJob; b.append("<p class=\"k\">").append(esc(t(Translation.notesSaid(j.path("notes").asText(), raw == null ? "" : Translation.tierOf(raw.path("args")))))).append("</p>"); }   // a document's translation: where what its checks still doubt goes, as filed
        if (j.hasNonNull("resumed")) b.append("<p class=\"k\">").append(esc(RunProgress.resumed(j))).append("</p>");   // a restart or an update interrupted it: where it picked up
        if (live && j.path("state").asText().equals("running")) { RunProgress.View v = RunProgress.of(j, System.currentTimeMillis(), RunProgress.typicalSeconds(p, args(patron).path("patron"))); b.append("<p><b>").append(esc(v.stage())).append("</b> · ").append(t("{0} elapsed", esc(RunProgress.elapsed(v.elapsedSeconds())))).append(" · ").append(t("about {0}% done", v.percent())).append(" <progress max=\"100\" value=\"").append(v.percent()).append("\"></progress></p>"); }
        if (live && j.has("progress")) b.append("<p class=\"k\">").append(esc(progressLine(j.get("progress")))).append("</p>");
        if (live && j.hasNonNull("waiting")) b.append("<p class=\"k\">").append(t("Waiting for the model: {0}.", esc(j.get("waiting").asText()))).append(" ").append(t("A model server that sleeps between uses is starting.")).append("</p>");
        if (j.hasNonNull("question")) b.append("<p><b>").append(esc(j.path("question").asText())).append("</b></p>");
        if (j.hasNonNull("investigation")) b.append("<p>").append(t("The answer is saved as {0}.", idLink(j.path("investigation").asText()))).append("</p>");
        boolean declinedRun = j.path("declined").path("run").asBoolean(false);
        if (j.path("declined").isObject()) {
            // the model declined the run, or parts of it: said in its own words, with what the model said
            b.append("<h2>").append(declinedRun ? t("Declined by the model") : t("Parts declined by the model")).append("</h2><p>").append(esc(j.path("declined").path("statement").asText())).append("</p>");
            if (!declinedRun && j.path("declined").path("parts").size() > 0) {
                b.append("<ul>");
                for (JsonNode part : j.path("declined").path("parts")) b.append("<li>").append(esc(part.path("model").asText("").isBlank() ? t("It declined {0}.", part.path("step").asText(t("a step of the work"))) : t("The model {0} declined {1}.", part.path("model").asText(), part.path("step").asText(t("a step of the work")))))
                        .append(part.path("said").asText("").isBlank() ? "" : " " + esc(t("What it said: \"{0}\"", Acquisitions.compress(part.path("said").asText(), 300)))).append("</li>");
                b.append("</ul>");
            }
        }
        if (!declinedRun && j.hasNonNull("result") && !j.path("result").asText().isEmpty()) b.append("<h2>").append(j.path("is_error").asBoolean() ? t("What went wrong") : t("Result")).append("</h2><div class=\"body\">").append(md(j.path("result").asText())).append("</div>");
        if (Translation.JOB_KIND.equals(j.path("kind").asText()) && !live) {   // a translation's units on the record the checks are measured from, and the button that takes them off
            String rec = Translation.onRecordSaid(store, id, Chrome.now());
            if (!rec.isEmpty()) b.append("<p class=\"k\">").append(esc(rec)).append(" <form class=\"inline\" method=\"post\" action=\"/jobs\"><input type=\"hidden\" name=\"op\" value=\"withdraw-scores\"><input type=\"hidden\" name=\"job_id\" value=\"").append(esc(id)).append("\"><button class=\"quiet\">").append(t("Take this translation off the record")).append("</button></form></p>");
        }
        if (live) return page(store, patron, t("Run {0}", id), b.toString(), 5, null);
        if (!back.isEmpty() && "done".equals(state)) return page(store, patron, t("Run {0}", id), b.toString(), 3, back);
        return page(store, patron, t("Run {0}", id), b.toString());
    }

    /**
     * For each run just sent whose question looks like a field's: that the field's mode is there, what it does, that the run is ordinary
     * research, and the way to ask for the mode (the Research page with the box ticked).
     */
    static String toldAbout(LibraryStore store, String told) throws IOException {
        StringBuilder b = new StringBuilder();
        Jobs jobs = new Jobs(store, j -> "");
        for (String id : told.split(",")) {
            if (!id.strip().matches("J-\\d+")) continue;
            ObjectNode j = jobs.get(id.strip());
            if (j == null || !j.path("args").path("suggested").isObject()) continue;
            JsonNode s = j.path("args").path("suggested");
            String question = j.path("args").path("question").asText(""), field = s.path("field").asText();
            b.append("<p class=\"notice\">").append(esc(t(s.path("offer").asText()))).append(" ").append(t("The question \"{0}\" was sent as ordinary research, as {1}.", esc(question), "<a href=\"/jobs/" + enc(id.strip()) + "\">" + esc(id.strip()) + "</a>")).append(" ")
             .append(t("To research it in {0} mode, {1} and send it.", esc(Fields.said(field)), "<a href=\"/research?q=" + enc(question) + "&field=" + enc(field) + "\">" + t("open it on the Research page with the box ticked") + "</a>")).append("</p>");
        }
        return b.toString();
    }

    private static String researchForm(LibraryStore store, Patrons.Patron patron, String q, String note) throws IOException { return researchForm(store, patron, q, note, ""); }

    /**
     * The boxes that ask for a field that joins only when asked, each unticked unless {@code ticked} names it (a question the field filed
     * itself). Beside one, when the question looks like the field's, the sentence that says so; the form then carries {@code told}, so
     * sending it as it is files ordinary research.
     */
    static String fieldChoices(LibraryStore store, String question, String ticked) {
        StringBuilder b = new StringBuilder();
        Fields.Suggestion s = question == null || question.isBlank() ? null : Fields.suggest(store, question);
        boolean told = false;
        for (Profile p : Fields.enabled(store)) {
            if (!p.joinsOnlyWhenAsked() || p.choice().isBlank()) continue;
            boolean on = p.name().equalsIgnoreCase(ticked == null ? "" : ticked.strip());
            b.append("<label class=\"choice\"><input type=\"checkbox\" name=\"field\" value=\"").append(esc(p.name())).append("\"").append(on ? " checked" : "").append("> ").append(esc(t(p.choice()))).append("</label>");   // the field's own sentence, in the person's language
            if (s != null && s.field().equals(p.name()) && !on) { b.append("<p class=\"notice\">").append(esc(t(s.offer()))).append(" ").append(t("Tick the box above to use it. Sent as it is, the question is researched as ordinary research.")).append("</p>"); told = true; }
        }
        if (told) b.append("<input type=\"hidden\" name=\"told\" value=\"1\">");
        return b.toString();
    }

    /**
     * The box that lets into one question's run what the library leaves out by default, never ticked unless the person ticked it; its own
     * box, not a field. Beside it, when the question may need such material ({@code needs}), the sentence that says so; the form then
     * carries {@code content_told}, so sending it as it is files the run with that material left out.
     */
    static String allowChoice(List<String> needs, boolean ticked) {
        StringBuilder b = new StringBuilder("<label class=\"choice\"><input type=\"checkbox\" name=\"allow\" value=\"1\"").append(ticked ? " checked" : "")
                .append("> ").append(t("Let in what the library leaves out by default, for this question only: {0}", esc(ContentOffer.described(List.of(ContentPolicy.EXPLICIT, ContentPolicy.HOWTO), Chrome.now())))).append("</label>");
        if (needs != null && !needs.isEmpty()) b.append("<p class=\"notice\">").append(esc(ContentOffer.offer(needs, Chrome.now()))).append(" ").append(t("Tick the box above to let it in. Sent as it is, the question is researched with that material left out.")).append("</p>")
                .append("<input type=\"hidden\" name=\"content_told\" value=\"1\">");
        return b.toString();
    }

    private static String researchForm(LibraryStore store, Patrons.Patron patron, String q, String note, String field) throws IOException { return researchForm(store, patron, q, note, field, Map.of()); }

    /** {@code sent}: what a person sent before, kept when the form is shown again (how, where to read, the limits, and the fields they cannot see). */
    private static String researchForm(LibraryStore store, Patrons.Patron patron, String q, String note, String field, Map<String, String> sent) throws IOException {
        return researchForm(store, patron, q, note, field, sent, List.of());
    }

    /** {@code needs}: what the question may need that the library leaves out by default, said beside the box. */
    private static String researchForm(LibraryStore store, Patrons.Patron patron, String q, String note, String field, Map<String, String> sent, List<String> needs) throws IOException {
        StringBuilder b = new StringBuilder();
        if (note != null) b.append("<p class=\"err\">").append(esc(note)).append("</p>");
        if (patron.web() && WebAccess.signInRequired()) {
            b.append("<p>").append(t("To ask for research you need to {0} first.", "<a href=\"/login\">" + t("sign in") + "</a>")).append("</p>");
        }
        b.append("<p class=\"k\">").append(t("A question that takes real reading. The library reads, checks every source it uses, and saves the answer as a report you can read here.")).append("</p>");
        b.append("<form method=\"post\" action=\"/research\" class=\"stack\">");
        b.append("<label>").append(t("The question")).append("<br><textarea name=\"question\" rows=\"3\" required>").append(esc(q)).append("</textarea></label>");
        String mode = sent.getOrDefault("mode", "broad"), sources = sent.getOrDefault("sources", "both");
        b.append(howAndWhere(mode, sources));
        b.append(goalChoice(sent.getOrDefault("goal", "")));
        b.append(modelChoice(sent.getOrDefault("model", "")));
        b.append(optionChoices(sent));
        b.append(limits(num(sent.get("max_turns")), num(sent.get("max_minutes"))));
        // harm_ok: the person's yes to researching it after the help was shown, carried so that the help is not shown twice
        for (String hidden : List.of("quick", "follow_up_to", "back", "harm_ok")) if (sent.containsKey(hidden) && !sent.get(hidden).isBlank()) b.append("<input type=\"hidden\" name=\"").append(hidden).append("\" value=\"").append(esc(sent.get(hidden))).append("\">");
        b.append(fieldChoices(store, q, field));
        b.append(allowChoice(needs, "1".equals(sent.get("allow"))));
        b.append(nonceField()).append("<button>").append(t("Send it")).append("</button> <button name=\"sharpen\" value=\"1\" class=\"quiet\">").append(t("Refine it first")).append("</button></form>");
        b.append("<p class=\"k\">").append(t("Refine it first: the library reads what it already holds, finds who studies this, and gives the question back more precise, with what it assumed and a plan. You edit, then send. No research runs yet.")).append("</p>");
        return page(store, patron, t("Research"), b.toString());
    }

    /** The research form's how and where: broad or deep, and the library, the web or both. */
    static String howAndWhere(String mode, String sources) {
        return "<label>" + t("How") + "<br><select name=\"mode\"><option value=\"broad\">" + t("broad — cover the whole topic") + "</option><option value=\"depth\"" + (mode.equals("depth") ? " selected" : "") + ">" + t("deep — go into detail on the best sources") + "</option></select></label>"
             + "<label>" + t("Where to read") + " <small>(" + t("this library: the documents you added and what earlier runs found; the web: everything else, the archives and the podcasts included") + ")</small><br><select name=\"sources\"><option value=\"both\">" + t("this library and the web") + "</option><option value=\"shelves\"" + (sources.equals("shelves") ? " selected" : "") + ">" + t("this library only") + "</option><option value=\"web\"" + (sources.equals("web") ? " selected" : "") + ">" + t("the web only") + "</option></select></label>";
    }

    /** The goals a person can name on the research form, each in plain words; the generic goal is the library's own choice and is not listed. */
    static final Map<String, String> GOAL_WORDS = new LinkedHashMap<>();
    static {
        GOAL_WORDS.put("home", "a home to buy or rent"); GOAL_WORDS.put("work", "work: openings and employers"); GOAL_WORDS.put("dossier", "a dossier on a person or an organisation");
        GOAL_WORDS.put("buy", "a thing to buy"); GOAL_WORDS.put("prior-art", "prior art and patents"); GOAL_WORDS.put("what-the-law-says", "what the law says");
        GOAL_WORDS.put("family", "family history"); GOAL_WORDS.put("learn-a-craft", "how to make or do something"); GOAL_WORDS.put("choose", "which book, video, show or project to pick");
    }

    /**
     * The research form's goal: what the question is for, which names the run's shape, instruments and rules; blank lets the library read
     * it off the question. The form never sent one until 2026-11-07, though the command line and the protocol took it.
     */
    static String goalChoice(String chosen) {
        StringBuilder b = new StringBuilder("<label>").append(t("What it is for")).append(" <small>(").append(t("the goal names the run's shape and the instruments it brings; blank lets the library read it off the question")).append(")</small><br><select name=\"goal\">")
                .append(option("", t("let the library choose"), chosen));
        for (Goal g : Goals.known()) { if (g.name().equals("generic")) continue; String words = GOAL_WORDS.get(g.name()); b.append(option(g.name(), words == null ? g.name() : t(words), chosen)); }
        return b.append("</select></label>").toString();
    }

    /** The research form's limits: model turns and minutes, 0 for none. */
    static String limits(int turns, int minutes) {
        return "<label>" + t("Limits, if you want any (0 = no limit)") + "<br>" + t("{0} model turns &nbsp; {1} minutes", "<input name=\"max_turns\" value=\"" + turns + "\" size=\"6\">", "<input name=\"max_minutes\" value=\"" + minutes + "\" size=\"6\">") + "</label>";
    }

    /** The research form's report options (0.7.0): how long, how the sources are written, and the shape; each one blank for the library's default. */
    static String optionChoices(Map<String, String> sent) {
        ReportOptions dflt = ReportOptions.defaults();
        String len = sent.getOrDefault("length", ""), cit = sent.getOrDefault("citations", ""), shp = sent.getOrDefault("shape", ""), words = sent.getOrDefault("words", "");
        StringBuilder b = new StringBuilder();
        b.append("<label>").append(t("How long")).append(" <small>(").append(t("or about {0} words", "<input name=\"words\" size=\"5\" value=\"" + esc(words.replaceAll("[^0-9]", "")) + "\">")).append(")</small><br><select name=\"length\">")
         .append(option("", dflt.length().isEmpty() ? t("as long as the question needs") : t("the library's default: {0}", new ReportOptions(dflt.length(), "", "").described()), len))
         .append(option("short", t("short, about 400 words"), len)).append(option("medium", t("medium, about 1,200 words"), len)).append(option("long", t("long, about 3,000 words"), len)).append("</select></label>");
        b.append("<label>").append(t("Sources written as")).append(" <small>(").append(t("the numbers in the text stay, so every cited sentence can still be checked")).append(")</small><br><select name=\"citations\">")
         .append(option("", t("the library's default: {0}", dflt.citations().isEmpty() ? t("numbered") : new ReportOptions("", dflt.citations(), "").described().replaceFirst("^with ", "")), cit))
         .append(option("numbered", t("numbered, [1] [2]"), cit)).append(option("apa", "APA", cit)).append(option("mla", "MLA", cit)).append(option("chicago", "Chicago", cit)).append(option("ieee", "IEEE", cit)).append("</select></label>");
        b.append("<label>").append(t("Shape")).append("<br><select name=\"shape\">").append(option("", t("a report"), shp))
         .append(option("reading-list", t("a reading list: each source with what it is, why it matters and how strong it is"), shp))
         .append(option("outline", t("an outline: headings with one line each"), shp)).append("</select></label>");
        return b.toString();
    }

    private static String option(String value, String label, String chosen) {
        return "<option value=\"" + esc(value) + "\"" + (value.equals(chosen) && !value.isEmpty() ? " selected" : "") + ">" + esc(label) + "</option>";
    }

    /**
     * Start sharpening in the background (or join the one already running for this question) and show the working page. {@code field}: the
     * field the person ticked, kept ticked on the refined form.
     */
    private static String sharpenStart(LibraryStore store, Patrons.Patron patron, String question, String field) throws IOException {
        String q = question.strip();
        if (q.length() < 8) return researchForm(store, patron, q, t("The question is too short to refine."), field);
        Patrons.check(store, patron, Patrons.Level.read);
        Researcher.Drive drive = Explain.drive();
        if (drive == null) throw ProtocolError.unavailable(t("No model is answering right now. Refining a question needs one."));
        String key = sharpenKey(q);
        Sharpening cur = SHARPENING.get(key);
        if (cur == null || (cur.done && cur.error != null) || (cur.done && System.currentTimeMillis() - cur.started > 10 * 60_000L)) {
            Sharpening nw = new Sharpening();
            if (SHARPENING.putIfAbsent(key, nw) == null || cur != null && SHARPENING.replace(key, cur, nw)) {
                Thread t = new Thread(() -> {
                    try { nw.result = Sharpen.run(store, patron, drive, Researcher.webTools(), q); } catch (Throwable e) { nw.error = e; }
                    nw.done = true;
                }, "sharpen-" + key);
                t.setDaemon(true); t.start();
            }
        }
        return sharpenPoll(store, patron, key, field);
    }

    /** The working page while a sharpening runs; the result once it is there; the error if it failed. */
    static String sharpenPoll(LibraryStore store, Patrons.Patron patron, String key, String field) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        Sharpening w = SHARPENING.get(key);
        if (w == null) return researchForm(store, patron, "", t("That refinement has expired. Type the question again."), field);
        if (!w.done) {
            long secs = (System.currentTimeMillis() - w.started) / 1000;
            String body = "<div class=\"working\"><span class=\"spin\"></span><div><p><b>" + t("Refining the question…") + "</b></p>"
                    + "<p class=\"k\">" + t("{0} seconds so far.", secs) + " " + t("The library reads what it already holds, finds who studies this, and writes the plan. This takes about half a minute.") + "</p>"
                    + "<p class=\"k\">" + t("This page updates itself.") + "</p></div></div>";
            return page(store, patron, t("Refining"), body, 2, "/research?sharpen=" + key + (field == null || field.isBlank() ? "" : "&field=" + enc(field.strip())));
        }
        if (w.error instanceof Declined declined) throw declined;   // the model declined: the page says so
        if (w.error != null) throw w.error instanceof ProtocolError pe ? pe : ProtocolError.unavailable(t("The question could not be refined: {0}", w.error.getMessage()));
        return sharpened(store, patron, w.result, field);
    }

    /** The sharpened question: what it assumed, the plan, what you hold, and the form filled back in with it, the field ticked as it was. */
    private static String sharpened(LibraryStore store, Patrons.Patron patron, Sharpen.Sharpened s, String field) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\">").append(t("You asked: {0}", esc(s.original()))).append("</p>");
        b.append("<h2>").append(t("Refined")).append("</h2><p><b>").append(esc(s.question())).append("</b></p>");
        if (!s.assumptions().isEmpty()) {
            b.append("<p class=\"k\">").append(t("To get there it assumed:")).append("</p><ul class=\"tight\">");
            for (String a : s.assumptions()) b.append("<li>").append(esc(a)).append("</li>");
            b.append("</ul>");
        }
        if (!s.questionsForYou().isEmpty()) {
            b.append("<p>").append(t("It would help to know:")).append("</p><ul class=\"tight\">");
            for (String x : s.questionsForYou()) b.append("<li>").append(esc(x)).append("</li>");
            b.append("</ul><p class=\"k\">").append(t("Put the answers into the question below before you send it.")).append("</p>");
        }
        if (!s.held().isEmpty()) {
            b.append("<h2>").append(t("Already in the library")).append("</h2><ul class=\"tight\">");
            for (Sharpen.Held h : s.held()) b.append("<li>").append(idLink(h.id(), h.title())).append(" <span class=\"k\">").append(esc(t(h.state()))).append("</span></li>");
            b.append("</ul>");
        }
        b.append("<h2>").append(t("The plan")).append("</h2><pre>").append(esc(s.brief().render())).append("</pre>");
        b.append("<p class=\"k\">").append(t("Mode: {0}", esc(s.mode()))).append(" · ").append(t("size: {0}", "quick".equals(s.size()) ? t("quick — a few pages should settle it") : t("full — it takes real reading")));
        if (!s.lanes().isEmpty()) { b.append(" · ").append(t("languages:")).append(" "); for (Lanes.Lane l : s.lanes()) b.append(esc(l.name())).append(' '); }
        b.append("</p>");
        b.append("<h2>").append(t("Send it")).append("</h2><p class=\"k\">").append(t("This is what the library will research. Change anything you like.")).append("</p>");
        b.append("<form method=\"post\" action=\"/research\" class=\"stack\">");
        b.append("<label>").append(t("The question")).append("<br><textarea name=\"question\" rows=\"14\" required>").append(esc(s.researchQuestion())).append("</textarea></label>");
        b.append(howAndWhere(s.mode(), "both"));
        boolean quick = "quick".equals(s.size());
        b.append(limits(quick ? Explain.QUICK_TURNS : 0, quick ? Explain.QUICK_MINUTES : 0));
        if (quick) b.append("<input type=\"hidden\" name=\"quick\" value=\"1\">");
        b.append(fieldChoices(store, s.researchQuestion(), field));
        b.append(allowChoice(List.of(), false));
        b.append(nonceField()).append("<button>").append(t("Send it")).append("</button> <button name=\"sharpen\" value=\"1\" class=\"quiet\">").append(t("Refine again")).append("</button></form>");
        return page(store, patron, t("Refined"), b.toString());
    }

    /** Where to find help, as the page shows it: the sentence, then one line to a row. */
    static String helpBlock() {
        String[] lines = CrisisHelp.text().split("\n");
        StringBuilder b = new StringBuilder("<div class=\"notice\"><p>").append(esc(lines[0])).append("</p><ul class=\"tight\">");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].startsWith("- ")) b.append("<li>").append(esc(lines[i].substring(2))).append("</li>");
            else b.append("</ul><p>").append(esc(lines[i])).append("</p><ul class=\"tight\">");
        }
        return b.append("</ul></div>").toString().replace("<ul class=\"tight\"></ul>", "");
    }

    /**
     * A question that reads as a person asking about harming themselves: where to find help first, then whether to research it. Yes sends
     * the form again as it was, with {@code harm_ok}; anything else sends nothing.
     */
    static String helpFirst(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        StringBuilder b = new StringBuilder(helpBlock());
        b.append("<p>").append(t("Do you want the library to research this question?")).append("</p><form method=\"post\" action=\"/research\" class=\"inline\">");
        for (String k : List.of("question", "mode", "sources", "max_turns", "max_minutes", "quick", "follow_up_to", "back", "field", "allow", "content_told", "told"))
            if (form.containsKey(k) && !form.get(k).isBlank()) b.append("<input type=\"hidden\" name=\"").append(k).append("\" value=\"").append(esc(form.get(k))).append("\">");
        b.append("<input type=\"hidden\" name=\"harm_ok\" value=\"1\">").append(nonceField()).append("<button>").append(t("Yes, research it")).append("</button></form> ");
        b.append("<a href=\"/research\">").append(t("No, do not research it")).append("</a>");
        b.append("<p class=\"k\">").append(t("Nothing is sent unless you say yes.")).append("</p>");
        return page(store, patron, t("Before you send it"), b.toString());
    }

    /** The model to run on, when the library has more than its default: a dropdown of the named models, the default first. */
    static String modelChoice(String chosen) {
        Map<String, ModelEntries.Entry> all = ModelEntries.all();
        if (all.size() < 2) return "";
        StringBuilder b = new StringBuilder("<label>").append(t("Which model")).append("<br><select name=\"model\">");
        b.append("<option value=\"auto\"").append(Auto.NAME.equals(chosen) || (chosen.isEmpty() && Auto.isDefault()) ? " selected" : "").append(">").append(t("auto — best judgement picks, and says why")).append("</option>");
        for (ModelEntries.Entry e : all.values()) {
            String label = e.name().equals(ModelEntries.DEFAULT) ? t("default — {0} via {1}", e.model(), e.host()) : e.name() + " — " + t("{0} via {1}", e.model(), e.host()) + (e.prices() == null ? "" : ", " + t("${0} per M", e.pricesText()));
            b.append("<option value=\"").append(esc(e.name())).append("\"").append(e.name().equals(chosen) ? " selected" : "").append(">").append(esc(label)).append("</option>");
        }
        return b.append("</select> <small>").append(t("researchzosho models adds one")).append("</small></label>").toString();
    }

    private static String researchPost(LibrarianDaemon d, LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);   // before anything is written down about the question
        String once = form.getOrDefault("once", "");
        if (!once.isEmpty() && SENT.containsKey(once)) {
            String id = SENT.get(once);
            return page(store, patron, t("Already sent"), "<p>" + t("This question was already sent, as {0}. A second click or a refresh does not send it again.", "<a href=\"/jobs/" + enc(id) + "\">" + esc(id) + "</a>") + "</p>"
                    + "<p class=\"k\"><a href=\"/research\">" + t("Send a different question") + "</a> · <a href=\"/jobs\">" + t("the runs") + "</a></p>");
        }
        ObjectNode body = M.createObjectNode();
        String question = form.getOrDefault("question", "").strip(), followUpTo = form.getOrDefault("follow_up_to", "").strip();
        if (!followUpTo.isEmpty() && !question.isEmpty() && !question.startsWith("Follow-up to ")) question = "Follow-up to " + followUpTo + ": " + question;   // the earlier report is handed to the run
        body.put("question", question);
        body.put("mode", form.getOrDefault("mode", "broad"));
        body.put("sources", form.getOrDefault("sources", "both"));
        body.put("max_turns", num(form.get("max_turns"))); body.put("max_minutes", num(form.get("max_minutes")));
        if ("1".equals(form.get("quick"))) body.put("quick", true);
        String words = form.getOrDefault("words", "").strip(), length = words.isEmpty() ? form.getOrDefault("length", "").strip() : words;   // a number of words wins over the length beside it
        if (!length.isEmpty()) body.put("length", length);
        for (String k : List.of("citations", "shape")) { String v = form.getOrDefault(k, "").strip(); if (!v.isEmpty()) body.put(k, v); }
        String modelEntry = form.getOrDefault("model", "").strip();
        if (!modelEntry.isEmpty() && !modelEntry.equals(ModelEntries.DEFAULT)) body.put("model", modelEntry);
        String goal = form.getOrDefault("goal", "").strip();
        if (!goal.isEmpty() && Goals.named(goal) != null) body.put("goal", goal);   // the goal the person chose travels with the run, as --goal does
        String field = form.getOrDefault("field", "").strip();
        if (!field.isEmpty()) body.put("field", field);
        else if (!"1".equals(form.get("told")) && question.length() >= 12) {
            // the question looks like a field's and the page has not said so yet: it says so once, and Send again files it as it is
            Fields.Suggestion s = Fields.suggest(store, question);
            if (s != null && !Fields.seen(store, question, s.field())) {
                Fields.note(store, question, s.field(), "told", "", "web");
                return researchForm(store, patron, form.getOrDefault("question", ""), null, "", form);
            }
        }
        boolean ticked = "1".equals(form.get("allow")), harmOk = "1".equals(form.get("harm_ok"));
        // every question, however short: a short one is still asked whether it is a person asking about harming themselves
        ContentOffer.Detected detected = !question.isBlank() && (!harmOk || (!ticked && !"1".equals(form.get("content_told")))) ? ContentOffer.detect(question, null) : null;
        // a person asking about harming themselves: where to find help first, and nothing is filed until they say yes to researching it
        if (detected != null && detected.harm() == ContentOffer.Harm.SURE && !harmOk) return helpFirst(store, patron, form);
        List<String> allow = new ArrayList<>();
        if (ticked) { allow.add(ContentPolicy.EXPLICIT); allow.add(ContentPolicy.HOWTO); }   // the person ticked the box: this run, and no other, lets it in
        else if (!"1".equals(form.get("content_told")) && detected != null && !detected.needs().isEmpty())
            // the question may need such material and the page has not said so: it says so, and Send again files it with the material left out
            return researchForm(store, patron, form.getOrDefault("question", ""), null, field, form, detected.needs());
        if (harmOk) allow.add(ContentPolicy.SELF_HARM);
        if (!allow.isEmpty()) { ArrayNode al = body.putArray("allow"); allow.forEach(al::add); body.put("allow_how", "web-box"); }
        String back = form.getOrDefault("back", "");
        if (!back.startsWith("/explain?")) back = "";
        body.set("patron", LibrarianDaemon.patronNode(patron));
        ObjectNode r;
        try { r = d.research(body, patron, new LibraryProtocol.Way("web-box", "web", false)); }
        catch (ProtocolError e) { if ("forbidden".equals(e.code)) throw e; return researchForm(store, patron, form.getOrDefault("question", ""), e.getMessage()); }
        String id = r.path("job_id").asText();
        if (!once.isEmpty()) SENT.put(once, id);
        boolean quick = r.path("quick").asBoolean(false);
        String jobUrl = "/jobs/" + enc(id) + (back.isEmpty() ? "" : "?back=" + enc(back));
        // the judge could not tell whether the question is a person asking about harming themselves: the help is shown anyway, first
        String help = detected != null && detected.harm() == ContentOffer.Harm.UNSURE && !harmOk ? helpBlock() : "";
        return page(store, patron, t("Sent"), help + "<p>" + t("Sent as {0}", "<a href=\"" + jobUrl + "\">" + esc(id) + "</a>") + (r.hasNonNull("field") ? ", " + t("in {0} mode", esc(Fields.said(r.path("field").asText()))) : "") + (quick ? ", " + t("first in line") : "") + ". " + t("{0} before it, {1} worker(s).", r.path("queued_ahead").asInt(), r.path("workers").asInt())
                + " " + t("The {0} page shows how it is going. The answer appears there, and in the library, when it is done.", "<a href=\"/jobs\">" + t("runs") + "</a>") + "</p>"
                + (back.isEmpty() ? "" : "<p>" + (quick ? t("When it is done, {0} — a few minutes.", "<a href=\"" + esc(back) + "\">" + t("read the explanation") + "</a>") : t("When it is done, {0}.", "<a href=\"" + esc(back) + "\">" + t("read the explanation") + "</a>")) + "</p>"),
                quick ? 2 : 0, quick ? jobUrl : null);
    }

    static String explain(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        String id = q.getOrDefault("id", "").strip(), term = q.getOrDefault("term", "").strip(), in = q.getOrDefault("in", "").strip();
        Explain.Rung rung = Explain.Rung.of(q.getOrDefault("rung", "beginner"));
        boolean fresh = "1".equals(q.get("fresh"));
        if (id.isEmpty() && term.isEmpty()) return page(store, patron, t("Explain"), "<p>" + t("Open an entry and pick a reading level, or type a word to explain.") + "</p>");
        Patrons.check(store, patron, Patrons.Level.read);
        String key = (term.isEmpty() ? "id=" + id : "term=" + term.toLowerCase(Locale.ROOT) + "&in=" + in) + "&rung=" + rung.name();
        Working w = WORKING.get(key);
        if (w != null && !w.done) return workingPage(store, patron, w, term.isEmpty() ? id : term, in.isEmpty() ? id : in, rung);
        if (w != null && w.error instanceof Declined declined) { WORKING.remove(key); throw declined; }   // the model declined: the page says so
        if (w != null && w.error != null) { WORKING.remove(key); throw w.error instanceof ProtocolError pe ? pe : ProtocolError.unavailable(t("The explanation could not be written: {0}", w.error.getMessage())); }
        if (w != null) WORKING.remove(key);
        ObjectNode r;
        // the cache answers at once; anything that needs the model runs in the background and the page comes back now
        Explain.Reading cachedOnly = null;
        if (!fresh) {
            try {
                cachedOnly = term.isEmpty() ? Explain.entry(store, null, id, rung, false, Explain.Progress.NONE) : Explain.term(store, null, term, in, rung, false, Explain.Progress.NONE);
            } catch (ProtocolError e) { if (!"unavailable".equals(e.code)) throw e; }
        }
        if (cachedOnly == null) {
            Researcher.Drive drive = Explain.drive();
            if (drive == null) throw ProtocolError.unavailable(t("No model is answering right now. The first explanation needs one."));
            Working nw = new Working();
            if (WORKING.putIfAbsent(key, nw) == null) {
                final boolean f = fresh;
                Thread t = new Thread(() -> {
                    try {
                        if (term.isEmpty()) Explain.entry(store, drive, id, rung, f, nw.progress());
                        else Explain.term(store, drive, term, in, rung, f, nw.progress());
                    } catch (Throwable e) { nw.error = e; }
                    nw.done = true;
                }, "reading-" + key);
                t.setDaemon(true); t.start();
            }
            return workingPage(store, patron, WORKING.get(key), term.isEmpty() ? id : term, in.isEmpty() ? id : in, rung);
        }
        r = cachedOnly.json();
        store.circulate("explain", patron.label() + " :: " + (term.isEmpty() ? id : term + (in.isEmpty() ? "" : " in " + in)) + " @ " + rung.name());
        String of = r.path("of").asText("");
        StringBuilder b = new StringBuilder();
        if (!of.isEmpty()) {
            ObjectNode e = p.entry(of, p.kindOf(of), false);
            b.append("<p class=\"k\">").append(term.isEmpty() ? idLink(of, e == null ? of : e.path("title").asText()) : t("as used in {0}", idLink(of, e == null ? of : e.path("title").asText()))).append("</p>");
        }
        if (term.isEmpty()) b.append(ladder(of, rung));
        else b.append("<p class=\"k\">").append(t("For:")).append(" ").append(rungLinks("/explain?term=" + enc(term) + "&in=" + enc(of) + "&rung=", rung)).append("</p>");
        String grounding = r.path("grounding").asText();
        if ("none".equals(grounding)) {
            b.append("<p>").append(esc(r.path("text").asText())).append("</p>");
            JsonNode offer = r.path("offer");
            if (offer.isObject()) {
                String back = "/explain?term=" + enc(term) + "&in=" + enc(of) + "&rung=" + rung.name();
                b.append("<p>").append(t("The library can look it up. A quick look takes a few minutes and goes first. Full research has no limits and waits its turn. Either way every source is checked and the answer is saved. Then this page can explain it.")).append("</p>");
                b.append(offerForm(offer, back, true)).append(offerForm(offer, back, false));
                b.append("<p class=\"k\">").append(t("The question it will ask: {0}", esc(offer.path("question").asText()))).append("</p>");
            }
        } else {
            b.append("<p class=\"k\">").append(t("Based on the library: {0}", "shelves".equals(grounding) ? t("fully") : "thin".equals(grounding) ? t("partly") : esc(grounding)));
            if (r.path("checked").asInt() > 0) b.append(" · ").append(t("{0} paragraph(s) checked against their sources, {1} not backed up", r.path("checked").asInt(), r.path("unsupported").asInt()));
            b.append(r.path("cached").asBoolean() ? " · " + t("written {0}", when(r.path("generated_at").asText())) : " · " + t("just written"));
            b.append(" · <a href=\"/explain?").append(term.isEmpty() ? "id=" + enc(of) : "term=" + enc(term) + "&in=" + enc(of)).append("&rung=").append(rung.name()).append("&fresh=1\">").append(t("write it again")).append("</a></p>");
            if (term.isEmpty()) b.append(downloads(of, rung));
            b.append("<div class=\"body reading\">").append(readingHtml(r.path("text").asText())).append("</div>");
            if (!term.isEmpty() || r.path("terms").size() > 0) {
                b.append("<h2>").append(t("Words you may meet next")).append("</h2>");
                if (r.path("terms").size() == 0) b.append("<p class=\"k\">").append(t("None.")).append("</p>");
                else {
                    b.append("<ul>");
                    for (JsonNode t : r.path("terms")) b.append("<li><a href=\"/explain?term=").append(enc(t.path("term").asText())).append("&in=").append(enc(of)).append("&rung=").append(rung.name()).append("\">").append(esc(t.path("term").asText())).append("</a> <span class=\"k\">").append(esc(t.path("gloss").asText())).append("</span></li>");
                    b.append("</ul>");
                }
            }
        }
        b.append("<form class=\"big\" action=\"/explain\"><input type=\"hidden\" name=\"in\" value=\"").append(esc(of)).append("\"><input type=\"hidden\" name=\"rung\" value=\"").append(rung.name())
         .append("\"><input name=\"term\" placeholder=\"").append(esc(t("What I don't understand is…"))).append("\"> <button>").append(t("Explain")).append("</button></form>");
        b.append("<p class=\"k\">").append(t("This is a plain-language version, not a record. Nothing here is saved as a claim, and each paragraph says where in the library it comes from.")).append("</p>");
        String title = term.isEmpty() ? t("Reading level: {0}", rungLabel(rung)) : esc(term);
        return page(store, patron, title, b.toString());
    }

    /** Sends already made: token → the job id, so a second click or a refresh shows the first send instead of filing again. */
    static final Map<String, String> SENT = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 500; }
    });
    static String nonce() { return UUID.randomUUID().toString().replace("-", ""); }
    static String nonceField() { return "<input type=\"hidden\" name=\"once\" value=\"" + nonce() + "\">"; }

    /** A sharpening in the background, keyed by the question: the result when done, the error when it failed. */
    static final class Sharpening {
        final long started = System.currentTimeMillis();
        volatile Sharpen.Sharpened result; volatile Throwable error; volatile boolean done;
    }
    static final ConcurrentHashMap<String, Sharpening> SHARPENING = new ConcurrentHashMap<>();
    /** The refinement of a question: one per question, whoever asked for it. */
    static String sharpenKey(String q) { return Integer.toHexString(q.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").hashCode()); }

    /** A reading being written in the background: what it is doing, since when, and how it ended. */
    static final class Working {
        volatile String stage = "starting";
        /** The stage as the waiting page says it: a whole sentence of the interface's words and its places. */
        volatile String sentence = "The library is starting…";
        volatile Object[] args = new Object[0];
        /** The reading's progress, kept as the page's words. */
        Explain.Progress progress() {
            return new Explain.Progress() {
                @Override public void at(String st) { stage = st; }
                @Override public void stage(String phrase, String said, Object... a) { stage = phrase; sentence = said; args = a; }
            };
        }
        final long started = System.currentTimeMillis();
        volatile boolean done;
        volatile Throwable error;
    }
    static final ConcurrentHashMap<String, Working> WORKING = new ConcurrentHashMap<>();

    /** The page that comes back at once: what the library is doing, for how long, refreshing itself until the reading is there. */
    private static String workingPage(LibraryStore store, Patrons.Patron patron, Working w, String what, String of, Explain.Rung rung) throws IOException {
        long secs = (System.currentTimeMillis() - w.started) / 1000;
        StringBuilder b = new StringBuilder();
        b.append("<div class=\"working\"><span class=\"spin\"></span><div><p><b>").append(esc(t(w.sentence, w.args))).append("</b></p>");
        b.append("<p class=\"k\">").append(t("{0} seconds so far.", secs)).append(" ").append(t("The library reads what it has, writes, then checks each paragraph against its sources.")).append(" ")
         .append(t("A report takes about a minute the first time; a word, ten to twenty seconds.")).append("</p>");
        b.append("<p class=\"k\">").append(t("This page updates itself. You can leave; the result is kept.")).append("</p></div></div>");
        if (!of.isEmpty()) b.append("<p class=\"k\">").append(idLink(of)).append("</p>");
        return page(store, patron, t("Working on: {0}", what), b.toString(), 2, null);
    }

    private static String downloads(String id, Explain.Rung rung) {
        String q = "id=" + enc(id) + (rung == null || rung == Explain.Rung.written ? "" : "&rung=" + rung.name());
        return "<p class=\"k\">" + t("Download:") + " <a href=\"/download?" + q + "&as=md\">Markdown</a> · <a href=\"/download?" + q + "&as=pdf\">PDF</a></p>";
    }

    /**
     * /download: an entry, or a reading of it, as Markdown, a PDF or a Word file; a report ({@code id=I-…}) or a dossier ({@code subject=…})
     * as a PDF or a Word file holding what its page shows, in the language the page is read in ({@code in}, else the reader's own for a report).
     */
    private static void download(HttpExchange x, LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        String as = q.getOrDefault("as", "md").strip(), subject = q.getOrDefault("subject", "").strip(), fmt = Export.format(as);
        Export.File f;
        if (!subject.isEmpty()) f = Export.dossier(store, subject, fmt == null ? Export.PDF : fmt, q.getOrDefault("in", "")).file();
        else {
            String id = q.getOrDefault("id", "").strip();
            if (!LibraryStore.safeName(id)) throw ProtocolError.notFound("entry " + id);
            Explain.Rung rung = q.containsKey("rung") ? Explain.Rung.of(q.get("rung")) : Explain.Rung.written;
            if (fmt != null && rung == Explain.Rung.written && "investigation".equals(new LibraryProtocol(store).kindOf(id))) {
                String asked = q.getOrDefault("in", "").strip();
                f = Export.report(store, id, fmt, asked.isEmpty() ? ReadIn.of(store, patron) : asked).file();
            } else {
                Explain.Reading reading = rung == Explain.Rung.written ? null : Explain.entry(store, null, id, rung, false, Explain.Progress.NONE);   // a reading must already be written; the page writes it
                f = Export.PDF.equals(fmt) ? Export.pdf(store, id, reading) : Export.DOCX.equals(fmt) ? Export.docx(store, id, reading) : Export.markdown(store, id, reading);
            }
        }
        sendFile(x, f.name(), f.contentType(), f.bytes());
        store.circulate("download", patron.label() + " :: " + f.name());
    }

    /** /exports/&lt;name&gt;: a file the protocol's export kept under catalog/exports/, to anyone who may read the library. */
    private static void exportFile(HttpExchange x, LibraryStore store, Patrons.Patron patron, String name) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        Path p = Export.kept(store, name);
        if (p == null) throw ProtocolError.notFound("No file is kept under the name " + name + ". A report's page and a dossier's page make one with their Download links.");
        String type = name.endsWith(".pdf") ? Export.contentType(Export.PDF) : name.endsWith(".docx") ? Export.contentType(Export.DOCX) : name.endsWith(".md") ? Export.contentType(Export.MARKDOWN) : name.endsWith(".epub") ? DocWriters.contentType("epub") : name.endsWith(".txt") ? DocWriters.contentType("txt") : "application/octet-stream";
        sendFile(x, p.getFileName().toString(), type, Files.readAllBytes(p));
        store.circulate("download", patron.label() + " :: exports/" + name);
    }

    /** /images/&lt;file&gt;: a picture kept under catalog/images/ ({@link Images}), shown in the page rather than downloaded, to anyone who may read the library. */
    private static void imageFile(HttpExchange x, LibraryStore store, Patrons.Patron patron, String name) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        Path p = Images.kept(store, name);
        if (p == null) throw ProtocolError.notFound("No picture is kept under the name " + name + ". Pictures are kept with researchzosho images find … --keep, or \"keep them\" in the chat.");
        byte[] bytes = Files.readAllBytes(p);
        x.getResponseHeaders().set("Content-Type", Images.contentType(name));
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        x.getResponseHeaders().set("Cache-Control", "private, max-age=86400");
        x.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = x.getResponseBody()) { os.write(bytes); }
    }

    /**
     * The header that has a file saved under its own name. A header carries ASCII alone (the server refuses the whole reply for one
     * letter outside it: a document named in Japanese could not be downloaded at all), so a name with other letters goes twice, as
     * RFC 6266 has it: a plain form any client reads, each run of other letters an underscore, and the name itself in UTF-8,
     * percent-encoded (filename*), which a browser prefers and saves the file under.
     */
    static String disposition(String name) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}\"\\\\]", "").strip();
        if (n.isEmpty()) n = "download";
        if (n.chars().allMatch(c -> c >= 0x20 && c < 0x7F)) return "attachment; filename=\"" + n + "\"";
        String plain = n.replaceAll("[^\\x20-\\x7E]+", "_");
        if (plain.startsWith("_")) plain = "document" + plain;
        String own = URLEncoder.encode(n, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A");
        return "attachment; filename=\"" + plain + "\"; filename*=UTF-8''" + own;
    }

    private static void sendFile(HttpExchange x, String name, String type, byte[] bytes) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("Content-Disposition", disposition(name));
        x.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = x.getResponseBody()) { os.write(bytes); }
    }

    /** The three links a report's page and a dossier's page carry: the file as the page shows it, in the language the page is read in — PDF, Word, and the Markdown the library keeps (asked for on 2026-11-08: the download took md all along, the page never offered it). */
    static String saveLinks(String base, String in) {
        String l = in == null || in.isBlank() ? "" : "&amp;in=" + enc(in.strip());
        return "<p class=\"k save\"><a href=\"" + base + "&amp;as=pdf" + l + "\">" + t("Download PDF") + "</a> · <a href=\"" + base + "&amp;as=docx" + l + "\">" + t("Download Word") + "</a> · <a href=\"" + base + "&amp;as=md" + l + "\">" + t("Download Markdown") + "</a></p>";
    }

    private static final Pattern REF_LINE = Pattern.compile("^\\[(\\d+)\\]\\s+(.*)$", Pattern.MULTILINE);
    private static final Pattern REF_CELL = Pattern.compile("<td>\\[(\\d+)\\]</td>");
    /** In a write-up's evidence table, a bare [n] becomes a link to reference n with the source's name beside it. */
    static String withRefs(String html, String markdown) {
        Map<String, String> label = new HashMap<>();
        Matcher m = REF_LINE.matcher(unentity(markdown));
        while (m.find()) {
            String rest = m.group(2);
            String title = rest.contains(" — ") ? rest.substring(0, rest.indexOf(" — ")).strip() : "";
            Matcher u = Pattern.compile("https?://[^\\s]+").matcher(rest);
            String host = "";
            if (u.find()) { try { host = URI.create(u.group().replaceAll("[),.;]+$", "")).getHost(); } catch (Exception ignored) { } if (host == null) host = ""; }
            host = host.replaceFirst("^www\\.", "");
            String text = !title.isEmpty() ? Acquisitions.compress(title, 48) : host;
            label.put(m.group(1), text.isEmpty() ? "" : text);
        }
        if (label.isEmpty()) return html;
        Matcher c = REF_CELL.matcher(html);
        StringBuilder b = new StringBuilder();
        while (c.find()) {
            String n = c.group(1), l = label.getOrDefault(n, "");
            c.appendReplacement(b, Matcher.quoteReplacement("<td><a href=\"#ref-" + n + "\">[" + n + "]</a>" + (l.isEmpty() ? "" : "<br><span class=\"k\">" + esc(l) + "</span>") + "</td>"));
        }
        c.appendTail(b);
        return b.toString();
    }

    private static String ladder(String id, Explain.Rung current) {
        return "<p class=\"k\">" + t("Reading level:") + " " + rungLinks("/explain?id=" + enc(id) + "&rung=", current) + " <span class=\"k\">(" + t("the first time takes about a minute") + ")</span></p>";
    }
    /** A reading level as a person sees it: Simple, Familiar, Original. */
    static String rungLabel(Explain.Rung r) {
        return switch (r) { case beginner -> t("Simple"); case familiar -> t("Familiar"); case written -> t("Original"); };
    }
    private static String rungLinks(String hrefPrefix, Explain.Rung current) {
        StringBuilder b = new StringBuilder();
        for (Explain.Rung r : Explain.Rung.values()) {
            if (b.length() > 0) b.append(" · ");
            String label = rungLabel(r);
            if (r == current) b.append("<b>").append(label).append("</b>");
            else b.append("<a href=\"").append(hrefPrefix).append(r.name()).append("\">").append(label).append("</a>");
        }
        return b.toString();
    }
    private static String offerForm(JsonNode offer, String back, boolean now) {
        StringBuilder b = new StringBuilder("<form method=\"post\" action=\"/research\" class=\"inline\">");
        for (String k : new String[]{"question", "mode", "sources"}) b.append("<input type=\"hidden\" name=\"").append(k).append("\" value=\"").append(esc(offer.path(k).asText())).append("\">");
        if (now) b.append("<input type=\"hidden\" name=\"max_turns\" value=\"").append(offer.path("max_turns").asInt()).append("\"><input type=\"hidden\" name=\"max_minutes\" value=\"").append(offer.path("max_minutes").asInt()).append("\"><input type=\"hidden\" name=\"quick\" value=\"1\">");
        else b.append("<input type=\"hidden\" name=\"max_turns\" value=\"0\"><input type=\"hidden\" name=\"max_minutes\" value=\"0\">");
        b.append("<input type=\"hidden\" name=\"back\" value=\"").append(esc(back)).append("\"><button>").append(now ? t("Quick look (a few minutes)") : t("Full research (no limits, waits its turn)")).append("</button></form> ");
        return b.toString();
    }
    private static final Pattern READING_CITE = Pattern.compile("\\[((?:[FIA]-\\d{4}-[a-z0-9-]+)|\\d{4}-\\d{2}-\\d{2}-[0-9a-f]{6,}\\.md|https?://[^\\]\\s]+)\\]");
    /** A reading's markdown, with its citations as links and its marks as labels. */
    static String readingHtml(String text) {
        String h = md(text);
        Matcher m = READING_CITE.matcher(h);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String c = m.group(1), rep;
            if (c.endsWith(".md")) rep = "<a class=\"cite\" href=\"/entry/" + enc(c) + "\">[" + t("source") + "]</a>";
            else if (c.startsWith("http")) rep = "<a class=\"cite\" href=\"" + c + "\" rel=\"noreferrer\">[" + t("source") + "]</a>";
            else rep = "<a class=\"cite\" href=\"/entry/" + enc(c) + "\">[" + c.substring(0, Math.min(6, c.length())) + "]</a>";
            m.appendReplacement(b, Matcher.quoteReplacement(rep));
        }
        m.appendTail(b);
        return b.toString().replace(esc(Explain.UNCITED_MARK), "<span class=\"mark\">" + t("not from the library") + "</span>")
                           .replace(esc(Explain.UNSUPPORTED_MARK), "<span class=\"mark bad\">" + t("the sources do not say this") + "</span>");
    }

    /** The sign-in page's waiting line, as the tests read it (the page itself is {@link SignIn}). */
    static final String WAITING = SignIn.WAITING;

    /** A visitor who asked to be let in, on a later load: the owner's answer ({@link SignIn#collectAsk}). */
    static boolean collectAsk(HttpExchange x, LibraryStore store, String ask) { return SignIn.collectAsk(x, store, ask); }

    static String randomHex(int bytes) { byte[] b = new byte[bytes]; new SecureRandom().nextBytes(b); return HexFormat.of().formatHex(b); }

    /** The readers list on the Settings page: a change from its buttons. */
    private static String readersPost(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);
        String op = form.getOrDefault("op", ""), did = form.getOrDefault("did", "").strip();
        if (did.isEmpty() && !op.equals("reader-default")) return t("Which reader? Go back and try again.");
        switch (op) {
            case "reader-remove" -> { Patrons.remove(store, did); Accounts.remove(store, did); return t("{0} removed.", did); }
            case "reader-password" -> {
                Patrons.Entry e = Patrons.byDid(store, did);
                if (e == null) return t("{0} is not on the readers list.", did);
                try {
                    if (Accounts.byDid(store, did) == null) Accounts.create(store, did, e.name().isEmpty() ? did : e.name(), form.getOrDefault("password", ""));
                    else Accounts.setPassword(store, did, form.getOrDefault("password", ""));
                } catch (IOException ex) { return ex.getMessage(); }
                return t("{0} signs in with that password now.", e.name().isEmpty() ? did : e.name());
            }
            case "reader-default" -> { Patrons.Level l = Patrons.levelOf(form.getOrDefault("level", "")); if (l == null) return t("Say what anyone not listed may do: deny, read or write."); Patrons.setDefault(store, l); return t("Anyone not listed may {0}.", t(String.valueOf(l))); }
            case "reader-allow" -> { Patrons.Level l = Patrons.levelOf(form.getOrDefault("level", "")); if (l == null) return t("Say what {0} may do: deny, read or write.", did); Patrons.set(store, did, form.getOrDefault("name", "").strip(), l); return l == Patrons.Level.deny ? t("{0} is denied.", did) : t("{0} may {1}.", did, t(String.valueOf(l))); }
            case "reader-read", "reader-write", "reader-deny" -> { Patrons.Level l = Patrons.Level.valueOf(op.substring(7)); Patrons.set(store, did, null, l); return l == Patrons.Level.deny ? t("{0} is denied.", did) : t("{0} may {1}.", did, t(String.valueOf(l))); }
            default -> { return t("That is not a change the readers list knows."); }
        }
    }

    // ---- pieces ----

    private static String entryCard(JsonNode e) {
        StringBuilder b = new StringBuilder("<div class=\"card\"><h3>").append(idLink(e.path("id").asText(), e.path("title").asText())).append("</h3>");
        b.append("<p class=\"k\">").append(esc(t(e.path("kind").asText()))).append(" · ").append(badge(e.path("state").asText()));
        if (!"raw".equals(e.path("kind").asText())) b.append(" · ").append(esc(t(e.path("confidence").asText())));
        int n = e.path("sources").size(), ind = e.path("independent_sources").asInt(n);
        b.append(" · ").append(n(n, "{0} source", "{0} sources")).append(ind < n ? " (" + t("{0} independent", ind) + ")" : "").append("</p>");
        b.append("<p>").append(esc(Acquisitions.compress(plain(e.path("body").asText()), 500))).append("</p></div>");
        return b.toString();
    }

    private static String hitLine(JsonNode h) {
        return idLink(h.path("id").asText(), h.path("title").asText()) + " <span class=\"k\">" + esc(t(h.path("kind").asText())) + " · " + badge(h.path("state").asText()) + "</span>"
                + (h.path("snippet").asText().isEmpty() ? "" : "<br><span class=\"snip\">" + esc(h.path("snippet").asText()) + "</span>");
    }

    private static String changeLine(Changes.Change c) {
        return "<span class=\"k\">" + esc(c.at().length() > 16 ? c.at().substring(0, 16).replace('T', ' ') : c.at()) + "</span> " + esc(c.event()) + " " + idLink(c.id())
                + (c.detail().isEmpty() ? "" : " <span class=\"k\">" + esc(Acquisitions.compress(c.detail(), 140)) + "</span>");
    }

    static String jobLine(JsonNode j) {
        String id = j.path("job_id").asText();
        return "<a href=\"/jobs/" + enc(id) + "\">" + esc(id) + "</a> " + badge(j.path("state").asText()) + (j.hasNonNull("waiting") ? " <span class=\"badge\">" + t("waiting for the model") + "</span>" : "")
                + (j.path("declined").path("run").asBoolean(false) ? " <span class=\"badge\">" + t("declined by the model") + "</span>" : j.path("declined").isObject() ? " <span class=\"badge\">" + t("parts declined by the model") + "</span>" : "")
                + " <span class=\"k\">" + esc(t(j.path("kind").asText())) + " · " + j.path("elapsed_s").asLong() + " s</span>"
                + (j.path("state").asText().equals("running") ? runBar(j) : "")
                + (j.hasNonNull("question") ? "<br>" + esc(Acquisitions.compress(j.path("question").asText(), 160)) : "")
                + (j.hasNonNull("investigation") ? " → " + idLink(j.path("investigation").asText()) : "");
    }

    /** How long a run usually takes here, for list rows that have no protocol at hand; set by the pages that do. */
    static volatile LongSupplier TYPICAL = () -> 0;

    /** A running run's stage, time and percent with a bar, for a list row. */
    static String runBar(JsonNode j) {
        RunProgress.View v = RunProgress.of(j, System.currentTimeMillis(), TYPICAL.getAsLong());
        return "<br><span class=\"k\">" + esc(v.stage()) + " · " + t("{0} elapsed", esc(RunProgress.elapsed(v.elapsedSeconds()))) + " · " + t("about {0}% done", v.percent()) + "</span> <progress max=\"100\" value=\"" + v.percent() + "\"></progress>";
    }

    /** Markdown marks stripped, for a one-paragraph preview. */
    static String plain(String md) {
        return md.replaceAll("(?m)^#+\\s*", "").replaceAll("(?m)^\\s*[-*•]\\s+", "").replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                 .replaceAll("\\[\\[([^\\]|]+)(?:\\|[^\\]]+)?\\]\\]", "$1").replaceAll("`", "").replace("\n", " ").replaceAll("\\s+", " ").strip();
    }
    /** An instant as a person reads it: the date and the minute. */
    static String when(String iso) {
        if (iso == null || iso.length() < 16 || iso.charAt(10) != 'T') return esc(iso);
        return esc(Chrome.now().when(iso));
    }
    private static final Pattern SHELF_ID = Pattern.compile("\\b([FIA]-\\d{4}-[a-z0-9-]+)");
    /** Escaped text in which every shelf id is a link. */
    static String withIdLinks(String text) {
        Matcher m = SHELF_ID.matcher(esc(text));
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(idLink(m.group(1))));
        m.appendTail(b);
        return b.toString();
    }
    static String badge(String state) { return "<span class=\"badge " + esc(state) + "\">" + esc(t(state)) + "</span>"; }
    /** "round 1 of 2 · workers 3 of 6 done · 41 of 200 turns · critic" — the runner's progress, for the page. */
    static String progressLine(JsonNode p) {
        StringBuilder sb = new StringBuilder();
        if (p.path("round").asInt() > 0) sb.append(t("round {0} of {1}", p.path("round").asInt(), p.path("rounds").asInt()));
        if (p.path("workers_total").asInt() > 0) sb.append(sb.length() > 0 ? " · " : "").append(t("workers {0} of {1} done", p.path("workers_done").asInt(), p.path("workers_total").asInt()));
        if (p.path("turns_ceiling").asInt() > 0) sb.append(sb.length() > 0 ? " · " : "").append(t("{0} of {1} turns", p.path("turns_used").asInt(), p.path("turns_ceiling").asInt()));
        else if (p.path("turns_used").asInt() > 0) sb.append(sb.length() > 0 ? " · " : "").append(t("{0} turns, no turn limit", p.path("turns_used").asInt()));
        if (p.hasNonNull("deadline_at")) { try { long min = (Instant.parse(p.get("deadline_at").asText()).toEpochMilli() - System.currentTimeMillis()) / 60_000; sb.append(sb.length() > 0 ? " · " : "").append(min >= 0 ? t("{0} min left", min) : t("past its time limit, finishing up")); } catch (Exception ignored) { } }
        if (!p.path("phase").asText("").isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(p.path("phase").asText());
        return sb.toString();
    }

    static String idLink(String id) { return idLink(id, id); }
    static String idLink(String id, String text) { return "<a href=\"/entry/" + enc(id) + "\">" + esc(text.isEmpty() ? id : text) + "</a>"; }

    static String locatorLink(String locator) {
        if (locator.startsWith("http://") || locator.startsWith("https://")) return "<a href=\"" + esc(locator) + "\" rel=\"noreferrer\">" + esc(locator) + "</a>";
        if (locator.startsWith("F-") || locator.startsWith("I-") || locator.startsWith("A-")) return idLink(locator);
        return "<a href=\"/read?locator=" + enc(locator) + "\">" + esc(locator) + "</a>";
    }

    private static ObjectNode args(Patrons.Patron patron) { ObjectNode a = M.createObjectNode(); a.set("patron", LibrarianDaemon.patronNode(patron)); return a; }
    private static int num(String s) { try { return s == null || s.isBlank() ? 0 : Math.max(0, Integer.parseInt(s.strip())); } catch (NumberFormatException e) { return 0; } }

    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]{1,6}|#\\d{1,7}|amp|lt|gt|quot|apos|nbsp|ndash|mdash|hellip|rsquo|lsquo|rdquo|ldquo);");
    /** HTML entities that reached the record as text (a page title captured as &#039;) become the characters they mean. */
    public static String unentity(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        Matcher m = ENTITY.matcher(s);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String e = m.group(1), rep;
            try {
                if (e.startsWith("#x")) rep = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if (e.startsWith("#")) rep = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else rep = switch (e) { case "amp" -> "&"; case "lt" -> "<"; case "gt" -> ">"; case "quot" -> "\""; case "apos" -> "'"; case "nbsp" -> " ";
                    case "ndash" -> "–"; case "mdash" -> "—"; case "hellip" -> "…"; case "rsquo" -> "’"; case "lsquo" -> "‘"; case "rdquo" -> "”"; case "ldquo" -> "“"; default -> m.group(); };
            } catch (Exception x) { rep = m.group(); }
            m.appendReplacement(b, Matcher.quoteReplacement(rep));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** Just enough Markdown: headings, lists, paragraphs, links, bold, code, a picture on a line of its own, a ```mermaid diagram, and [[F-…]] shelf links. */
    static String md(String text) {
        text = unentity(text);
        StringBuilder out = new StringBuilder();
        boolean inList = false, inPre = false, inTable = false, headerDone = false, inDiagram = false;
        StringBuilder para = new StringBuilder(), chart = new StringBuilder();
        for (String raw : text.split("\n")) {
            String line = raw.stripTrailing();
            if (line.startsWith("```")) {
                flush(out, para); if (inList) { out.append("</ul>"); inList = false; } if (inTable) { out.append("</table></div>"); inTable = false; }
                if (inPre) { out.append(inDiagram ? diagram(chart.toString()) : "</pre>"); inPre = false; inDiagram = false; chart.setLength(0); continue; }
                inPre = true; inDiagram = isDiagramFence(line);   // a ```mermaid block is drawn on the page; any other fence is code
                if (!inDiagram) out.append("<pre>");
                continue;
            }
            if (inPre) { if (inDiagram) chart.append(line).append('\n'); else out.append(esc(line)).append('\n'); continue; }
            // a table: rows of | cells |; the |---| line under the first row makes it the header
            if (line.strip().startsWith("|") && line.strip().endsWith("|")) {
                String body = line.strip();
                if (body.matches("\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?")) { headerDone = true; continue; }
                if (!inTable) { flush(out, para); if (inList) { out.append("</ul>"); inList = false; } out.append("<div class=\"tablewrap\"><table>"); inTable = true; headerDone = false; }
                String[] cells = body.substring(1, body.length() - 1).split("(?<!\\\\)\\|");
                boolean header = !headerDone && out.lastIndexOf("<table>") == out.length() - 7;
                out.append("<tr>");
                for (String cell : cells) out.append(header ? "<th>" : "<td>").append(inline(cell.strip())).append(header ? "</th>" : "</td>");
                out.append("</tr>");
                continue;
            } else if (inTable) { out.append("</table></div>"); inTable = false; }
            Matcher fig = FIGURE_LINE.matcher(raw);
            if (fig.matches()) {   // a picture on a line of its own at the margin, as a writer places one under the paragraph that cites it: reading size; indented under a list item, or among words, it stays small
                flush(out, para); if (inList) { out.append("</ul>"); inList = false; }
                out.append(figure(fig.group(1), fig.group(2)));
                continue;
            }
            if (line.isBlank()) { flush(out, para); if (inList) { out.append("</ul>"); inList = false; } continue; }
            if (line.startsWith("#")) {
                flush(out, para); if (inList) { out.append("</ul>"); inList = false; }
                int n = 0; while (n < line.length() && line.charAt(n) == '#') n++;
                int lvl = Math.min(4, n + 1);
                out.append("<h").append(lvl).append('>').append(inline(line.substring(n).strip())).append("</h").append(lvl).append('>');
                continue;
            }
            if (line.strip().matches("^(?:https?|file)://\\S+(?:\\s*(?:\\(.*\\)|—.*))?\\s*$")) {   // a source line (a URL, maybe a note in brackets): one per row, never run together
                flush(out, para); if (inList) { out.append("</ul>"); inList = false; }
                out.append("<p class=\"src\">").append(inline(line.strip())).append("</p>");
                continue;
            }
            Matcher ref = Pattern.compile("^\\[(\\d+)\\]\\s+(.*)$").matcher(line);
            if (ref.matches()) {   // a reference line: its own row, anchored so the evidence table can point at it
                flush(out, para); if (inList) { out.append("</ul>"); inList = false; }
                StringBuilder parts = new StringBuilder();
                for (String part : ref.group(2).split("  also ")) { if (parts.length() > 0) parts.append("<br>also "); parts.append(inline(part.strip())); }
                out.append("<p class=\"ref\" id=\"ref-").append(ref.group(1)).append("\"><b>[").append(ref.group(1)).append("]</b> ").append(parts).append("</p>");
                continue;
            }
            Matcher li = Pattern.compile("^\\s*(?:[-*•]|\\d+[.)])\\s+(.*)$").matcher(line);
            if (li.matches()) { flush(out, para); if (!inList) { out.append("<ul>"); inList = true; } out.append("<li>").append(inline(li.group(1))).append("</li>"); continue; }
            if (para.length() > 0) para.append(' ');
            para.append(line.strip());
        }
        flush(out, para); if (inList) out.append("</ul>"); if (inPre) out.append(inDiagram ? diagram(chart.toString()) : "</pre>"); if (inTable) out.append("</table></div>");
        return out.toString();
    }
    private static void flush(StringBuilder out, StringBuilder para) { if (para.length() > 0) { out.append("<p>").append(inline(para.toString())).append("</p>"); para.setLength(0); } }

    /** A fence that opens a diagram: ```mermaid, in any case, nothing else on the line. */
    static boolean isDiagramFence(String line) { return line.substring(3).strip().equalsIgnoreCase("mermaid"); }

    /** The Mermaid the library serves itself for the diagrams, at {@link #MERMAID_PATH}: the file, its licence and its checksum are under static/ beside this class. */
    static final String MERMAID_VERSION = "11.17.2";
    static final String MERMAID_PATH = "/static/mermaid-" + MERMAID_VERSION + ".min.js";
    /** What a drawn diagram stands in before the browser draws it; {@link #page} adds the drawing script to a body that holds one. */
    static final String DIAGRAM_MARK = "<pre class=\"mermaid\">";

    /**
     * A ```mermaid block: its text in a {@code pre.mermaid} the page's script draws in the browser, and the same text behind a fold, so a
     * reader can see what was drawn or read it where the drawing fails. The text is a model's, so it is escaped: a tag in it is text, never live.
     */
    static String diagram(String text) {
        String src = text == null ? "" : text.stripTrailing();
        String e = esc(src);
        return "<figure class=\"diagram\">" + DIAGRAM_MARK + e + "</pre><details class=\"diagram-text\"><summary>" + t("Show as text") + "</summary><pre>" + e + "</pre></details></figure>";
    }

    /** A picture on a line of its own, at the margin: {@code ![title](address)} with the address of this library or of the web. */
    static final Pattern FIGURE_LINE = Pattern.compile("^!\\[([^\\]]*)\\]\\(((?:https?://|/(?!/))[^)\\s]+)\\)\\s*$");

    /** A picture at reading size: the whole column wide at most, the picture itself a link that opens the whole file in a new tab, its title under it. */
    static String figure(String alt, String src) {
        String a = esc(alt == null ? "" : alt.strip()), s = esc(src);
        return "<figure class=\"fig\"><a href=\"" + s + "\" target=\"_blank\" rel=\"noopener noreferrer\"><img class=\"figure\" src=\"" + s + "\" alt=\"" + a + "\" loading=\"lazy\" referrerpolicy=\"no-referrer\"></a>"
                + (a.isEmpty() ? "" : "<figcaption>" + a + "</figcaption>") + "</figure>";
    }

    /**
     * Draws every {@code pre.mermaid} of the page with the Mermaid the library serves itself: strict (no HTML in a label, no click), the theme
     * after the page's look and drawn again when the look changes; a diagram Mermaid cannot read keeps its text, with the error under it.
     */
    static String diagramJs() {
        return "(function(){if(!window.mermaid)return;var n=0;"
             + "function look(){var t=document.documentElement.getAttribute('data-theme');if(t!=='light'&&t!=='dark')t=window.matchMedia&&matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';return t}"
             + "function draw(){mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:look()==='dark'?'dark':'neutral',suppressErrorRendering:true});"
             + "document.querySelectorAll('pre.mermaid').forEach(function(p){var src=p.getAttribute('data-src');if(src===null){src=p.textContent;p.setAttribute('data-src',src)}var id='rz-diagram-'+(++n);"
             + "mermaid.render(id,src).then(function(r){p.innerHTML=r.svg;p.classList.add('drawn');var e=p.parentNode.querySelector('.diagram-error');if(e)e.remove()})"
             + ".catch(function(err){p.textContent=src;p.classList.remove('drawn');var e=p.parentNode.querySelector('.diagram-error');if(!e){e=document.createElement('p');e.className='diagram-error';p.parentNode.insertBefore(e,p.nextSibling)}"
             + "e.textContent=" + js(t("This diagram could not be drawn; its text is shown instead.")) + "+' '+(err&&(err.str||err.message)?(err.str||err.message):String(err));"
             + "var d=document.getElementById(id);if(d)d.remove();d=document.getElementById('d'+id);if(d)d.remove()})})}"
             + "draw();window.rzDraw=draw;"
             + "new MutationObserver(function(ms){for(var i=0;i<ms.length;i++)if(ms[i].attributeName==='data-theme'){draw();return}}).observe(document.documentElement,{attributes:true});"
             + "if(window.matchMedia){var mq=matchMedia('(prefers-color-scheme: dark)');if(mq.addEventListener)mq.addEventListener('change',function(){draw()})}"
             + "})();";
    }

    private static final Map<String, byte[]> STATIC = new ConcurrentHashMap<>();
    private static final Pattern STATIC_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /**
     * GET /static/{name}: a file the program ships for its pages (Mermaid and its licence, under static/ beside this class), served to anyone and
     * kept by the browser for a year, as the file's name carries its version. A name that is not shipped, or that is not a plain file name, is 404.
     */
    static void staticFile(HttpExchange x, String name) throws IOException {
        byte[] bytes = null;
        if (STATIC_NAME.matcher(name).matches()) {
            bytes = STATIC.get(name);
            if (bytes == null) try (InputStream in = Pages.class.getResourceAsStream("static/" + name)) { if (in != null) { bytes = in.readAllBytes(); STATIC.put(name, bytes); } }
        }
        if (bytes == null) {
            byte[] gone = "There is no file at this address.".getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            x.sendResponseHeaders(404, gone.length);
            try (OutputStream os = x.getResponseBody()) { os.write(gone); }
            return;
        }
        String n = name.toLowerCase(Locale.ROOT);
        x.getResponseHeaders().set("Content-Type", n.endsWith(".js") ? "text/javascript; charset=utf-8" : n.endsWith(".css") ? "text/css; charset=utf-8" : n.endsWith(".txt") || n.endsWith(".md") ? "text/plain; charset=utf-8" : "application/octet-stream");
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        x.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
        x.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = x.getResponseBody()) { os.write(bytes); }
    }

    /**
     * A written link may point at a page or a file of this library by its own path (/exports/…, /entry/…), never a protocol-relative //host.
     * A Markdown picture, {@code ![words](address)}, is shown small ({@code .thumb}, 160 px at most): a kept picture by its /images/ path, a
     * found one by its address on the web, which sends no referrer.
     */
    private static final Pattern LINK = Pattern.compile("!\\[([^\\]]*)\\]\\(((?:https?://|/(?!/))[^)\\s]+)\\)|\\[([^\\]]+)\\]\\(((?:https?://|/(?!/))[^)\\s]+)\\)|\\[\\[([A-Z]-[^\\]|]+)(?:\\|([^\\]]+))?\\]\\]|(?<![\"(>=])(https?://[^\\s<>\"')\\]]+)|\\*\\*([^*]+)\\*\\*|`([^`]+)`");
    static String inline(String s) {
        String e = esc(s);
        Matcher m = LINK.matcher(e);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String rep;
            if (m.group(2) != null) rep = "<img class=\"thumb\" src=\"" + m.group(2) + "\" alt=\"" + m.group(1) + "\" loading=\"lazy\" referrerpolicy=\"no-referrer\">";
            else if (m.group(4) != null) rep = m.group(4).startsWith("/") ? "<a href=\"" + m.group(4) + "\">" + m.group(3) + "</a>" : "<a href=\"" + m.group(4) + "\" rel=\"noreferrer\">" + m.group(3) + "</a>";
            else if (m.group(5) != null) rep = "<a href=\"/entry/" + enc(m.group(5)) + "\">" + (m.group(6) != null ? m.group(6) : m.group(5)) + "</a>";
            else if (m.group(7) != null) rep = "<a href=\"" + m.group(7) + "\" rel=\"noreferrer\">" + m.group(7) + "</a>";
            else if (m.group(8) != null) rep = "<b>" + m.group(8) + "</b>";
            else rep = "<code>" + m.group(9) + "</code>";
            m.appendReplacement(b, Matcher.quoteReplacement(rep));
        }
        m.appendTail(b);
        return b.toString();
    }

    // ---- the frame ----

    // ---- /chat: the page front of the Librarian ----

    /** What a reader who has not signed in is told on the chat page: the conversations are kept per person, so there is none to open. */
    static final String CHAT_SIGN_IN = "Sign in to talk with the Librarian. The person who keeps this library lets you in with a link; after that your name and password sign you in. "
            + "Someone who may only read has conversations of their own, which nobody else sees. Someone who may write shares the conversations of the person who keeps the library.";

    /**
     * The chat, the home: the latest conversation of this patron, the box to say the next thing that stays in view while the turns
     * scroll, the runs the conversation follows beside it, and the earlier conversations behind a button. `?new=1` starts a fresh one,
     * `?session=<id>` opens one. A reader only ever opens their own conversations: a conversation belongs to whoever had it.
     */
    static String chat(LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException { return chat(store, new LibraryProtocol(store), patron, q); }

    static String chat(LibraryStore store, LibraryProtocol lp, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        Librarian.Session s;
        if ("1".equals(q.get("new"))) s = Librarian.Session.open(store, patron);
        else if (q.get("session") != null && !q.get("session").isBlank()) { s = Librarian.Session.resume(store, q.get("session"), patron); if (s == null) s = Librarian.Session.latest(store, patron); }
        else s = Librarian.Session.latest(store, patron);
        if (s == null) return page(store, patron, t("Chat"), "<p>" + esc(t(CHAT_SIGN_IN)) + " <a href=\"/login\">" + t("Sign in") + "</a></p>");
        StringBuilder b = new StringBuilder("<div class=\"shell beside\"><section class=\"conv\">").append(conv(store, lp, patron, s, false, q.getOrDefault("note", "")));
        b.append("</section><aside id=\"runs-strip\" class=\"strip side\" data-session=\"").append(esc(s.id)).append("\">").append(runsStrip(store, lp, patron, s)).append("</aside></div>");
        b.append("<div id=\"scrim\" class=\"scrim\"></div>").append(sessionsDrawer(store, patron, s));
        b.append("<script>").append(chatPageJs()).append("</script>");
        return page(store, patron, t("Chat"), b.toString(), 0, null, "chat", true);
    }

    /** The conversation itself: its head, the turns, the box to say the next thing, and the strip under it when it is not beside. */
    static String conv(LibraryStore store, LibraryProtocol lp, Patrons.Patron patron, Librarian.Session s, boolean stripBelow) throws IOException { return conv(store, lp, patron, s, stripBelow, ""); }

    /** The same, with {@code note} under the latest turn: what came of a delete, said on the page it led to. */
    static String conv(LibraryStore store, LibraryProtocol lp, Patrons.Patron patron, Librarian.Session s, boolean stripBelow, String note) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("<div class=\"convhead\"><button type=\"button\" class=\"btn quiet\" id=\"sessions-toggle\">").append(t("Conversations")).append("</button><span class=\"title\">").append(esc(sessionTitle(s))).append("</span>")
         .append("<a class=\"small k3\" href=\"/chat?new=1\">").append(t("new conversation")).append("</a>").append(Files.exists(s.file) ? deleteForm(s.id, s.id) : "").append("<span class=\"small k3 counts\">").append(counts(lp, patron)).append("</span></div>");   // a conversation with nothing in it yet is on no list, and has nothing to delete
        boolean first = !modelAnswers(store);   // the first screen, until a model answers once; the composer does not take the focus then, or the page would scroll past the screen
        if (first) b.append("<div class=\"first\">").append(connectBlock(store, patron, "", false)).append("</div>");
        b.append("<div id=\"chat-view\" class=\"chat-view\">").append(chatView(store, lp, patron, s, note)).append("</div>");
        b.append("<div class=\"dropcue\" id=\"chat-drop\" hidden>").append(t("Drop to attach")).append("</div>");   // shown by the page's script while files are dragged over the page
        b.append("<form id=\"chat-say\" class=\"composer\" method=\"post\" action=\"/chat\" enctype=\"multipart/form-data\"><input type=\"hidden\" name=\"session\" value=\"").append(esc(s.id)).append("\">")
         .append("<div class=\"chips\" id=\"chat-att\" hidden></div><p class=\"note small\" id=\"chat-att-note\" hidden></p><textarea name=\"say\" rows=\"2\"").append(first ? "" : " autofocus").append(" placeholder=\"").append(esc(t("Ask the Librarian…"))).append("\" autocomplete=\"off\"></textarea>")
         .append("<div class=\"row\"><label class=\"btn quiet\" tabindex=\"0\" role=\"button\" title=\"").append(esc(t("Files go onto the shelves with this turn: one or several, or an archive (zip, tar, tar.gz)"))).append("\">").append(t("@ Attach")).append("<input type=\"file\" name=\"file\" id=\"chat-file\" multiple hidden></label>")
         .append("<a class=\"btn quiet\" id=\"chat-new\" href=\"/chat?new=1\" title=\"").append(esc(t("Start a fresh conversation; this one stays on the list"))).append("\">").append(t("New conversation")).append("</a>")
         .append("<span class=\"hint\">").append(t("Say “Find out …” to start a research run; “yes” accepts an offer. Files attached, dropped onto the page or pasted as a picture go onto the shelves with the turn, up to {0} in one message and {1} together; an archive is unpacked. Enter sends, Shift+Enter breaks a line.", CHAT_FILES, (Uploads.MAX >> 20) + " MB")).append("</span><button class=\"btn primary\">").append(t("Send")).append("</button></div></form>");
        if (stripBelow) b.append("<aside id=\"runs-strip\" class=\"strip below\" data-session=\"").append(esc(s.id)).append("\">").append(runsStrip(store, lp, patron, s)).append("</aside>");
        return b.toString();
    }

    /** The library's counts in a line, for the head of the chat: claims and how many are accepted, reports, open questions. */
    static String counts(LibraryProtocol lp, Patrons.Patron patron) throws IOException {
        JsonNode c = lp.status(args(patron)).path("counts");
        int open = lp.frontierList(false).size();
        return t("{0} claims, {1} accepted", c.path("finding").path("total").asInt(), c.path("finding").path("accepted").asInt()) + " · " + n(c.path("investigation").asInt(), "{0} report", "{0} reports") + " · <a href=\"/questions\">" + n(open, "{0} open question", "{0} open questions") + "</a>";
    }

    /** A conversation's title: the first thing the person said in it, cut short; a new one has none yet. */
    static String sessionTitle(Librarian.Session s) { return s.named(); }

    /** The first thing said in a conversation, read from the head of its file; "A new conversation" when nothing was. */
    static String firstWords(Path file) {
        try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line; int n = 0;
            while ((line = r.readLine()) != null && n++ < 12) {
                if (line.isBlank()) continue;
                JsonNode m; try { m = M.readTree(line); } catch (IOException e) { continue; }
                if (m.path("role").asText().equals("user") && !m.path("by").asText("").equals("library")) return Acquisitions.compress(m.path("content").asText(""), 60);
            }
        } catch (IOException ignored) { }
        return t("A new conversation");
    }

    /** The drawer of this patron's conversations, newest first, by day, each named by its first words; the open one marked. */
    static String sessionsDrawer(LibraryStore store, Patrons.Patron patron, Librarian.Session s) throws IOException {
        StringBuilder b = new StringBuilder("<aside class=\"sessions\" id=\"sessions\"><div class=\"head\"><b>").append(t("Conversations")).append("</b><a class=\"small\" href=\"/chat?new=1\">").append(t("new")).append("</a><button type=\"button\" id=\"sessions-close\" title=\"").append(esc(t("Close"))).append("\">×</button></div><div class=\"body\">");
        List<String> ids = Librarian.Session.list(store, patron);
        Path dir = Librarian.Session.dir(store, patron);
        String today = LocalDate.now().toString(), yesterday = LocalDate.now().minusDays(1).toString(), group = null;
        int n = 0;
        for (String id : ids) {
            if (n++ >= 40) { b.append("<p class=\"small k3\">").append(t("and {0} earlier", ids.size() - 40)).append("</p>"); break; }
            String day = id.length() >= 12 ? id.substring(2, 12) : "", when = id.length() >= 18 ? id.substring(13, 18).replace('-', ':') : "";   // C-2026-11-02-12-44-40
            String g = day.equals(today) ? t("Today") : day.equals(yesterday) ? t("Yesterday") : Chrome.now().day(day);
            if (!g.equals(group)) { b.append("<div class=\"g\">").append(esc(g)).append("</div>"); group = g; }
            String words = dir == null ? id : firstWords(dir.resolve(id + ".jsonl"));
            b.append("<div class=\"srow\"><a class=\"s").append(id.equals(s.id) ? " on" : "").append("\" href=\"/chat?session=").append(enc(id)).append("\">").append(esc(words)).append("<span class=\"w\">").append(esc(when)).append("</span></a>")
             .append(deleteForm(id, s.id)).append("</div>");
        }
        if (ids.isEmpty()) b.append("<p class=\"small k3\">").append(t("No conversations yet.")).append("</p>");
        return b.append("</div></aside>").toString();
    }

    /**
     * Delete… on a conversation, the page's way of removing something: the summary opens what deleting means and the button that does it,
     * with no script, on a phone as on a desk. {@code showing}: the conversation open in the page, where the page stays unless it is the
     * one deleted.
     */
    static String deleteForm(String id, String showing) {
        return "<details class=\"remove\"><summary class=\"k\">" + t("Delete…") + "</summary><form method=\"post\" action=\"/chat\"><input type=\"hidden\" name=\"op\" value=\"delete\">"
             + "<input type=\"hidden\" name=\"session\" value=\"" + esc(id) + "\"><input type=\"hidden\" name=\"showing\" value=\"" + esc(showing) + "\">" + nonceField()
             + "<p class=\"k\">" + esc(t(Librarian.DELETE_MEANS)) + "</p><button class=\"btn quiet\">" + t("Delete this conversation") + "</button></form></details>";
    }

    /**
     * POST /chat op=delete: one of this person's conversations deleted ({@link Librarian.Session#delete}), and where the page goes next, with
     * what came of it in a sentence under the latest turn. The page stays on the conversation it showed; when that is the one deleted, it
     * goes to the most recent one left, or to a new one when none is left. A second click, or the form sent again, goes where the first went.
     */
    static String chatDelete(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        String once = form.getOrDefault("once", "");
        String again = once.isEmpty() ? null : SENT.get(once);
        if (again != null) return again;
        String showing = form.getOrDefault("showing", "").strip();
        Librarian.Session.Deleted d = Librarian.Session.delete(store, form.getOrDefault("session", ""), patron);
        if (d.outcome() == Librarian.Session.Outcome.DELETED) LIVE.remove(d.id());
        boolean stay = !showing.isEmpty() && !(d.outcome() == Librarian.Session.Outcome.DELETED && showing.equals(d.id()));
        String to = "/chat?" + (stay ? "session=" + enc(showing) + "&" : "") + "note=" + enc(d.said());
        if (!once.isEmpty()) SENT.put(once, to);
        return to;
    }

    /**
     * The Chat page's changing part: the turns in their own scrolling box, a note under the latest turn, and a waiting question's two
     * buttons. The page's script puts a reply here in place of what was there, so the box to say the next thing is never redrawn and
     * what is typed in it stays (2026-10-12).
     */
    static String chatView(LibraryStore store, LibraryProtocol lp, Patrons.Patron patron, Librarian.Session s, String note) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        // the runs this conversation started: a finished one is announced once, in the conversation itself
        for (String jobId : s.watched()) {
            try { RunProgress.View v = RunProgress.of(lp.job(args(patron).put("job_id", jobId)).path("job"), System.currentTimeMillis(), RunProgress.typicalSeconds(lp, args(patron).path("patron"))); if (!v.active()) s.told(jobId, v); } catch (Exception ignored) { }
        }
        StringBuilder b = new StringBuilder("<div id=\"chat-scroll\" class=\"turns\">").append(transcript(store, s, Integer.MAX_VALUE));
        if (!note.isEmpty()) b.append("<p class=\"k note\">").append(esc(note)).append("</p>");   // under the latest turn, where the eye is
        List<String[]> waiting = Librarian.stillWaiting(store, s);
        String[] firstAsk = waiting.stream().min(Comparator.comparingInt(Librarian::rank)).orElse(null);
        if (firstAsk != null && Librarian.isChoice(firstAsk)) {
            // the library asked which of these: a button for each, or the number or the name typed; with no answer in time a run is not started
            b.append("<div class=\"ask\"><p>").append(esc(Librarian.choiceQuestion(store, firstAsk))).append("</p><div class=\"opts\">");
            for (String l : Librarian.choiceLabels(store, firstAsk)) b.append("<form class=\"inline page-say\" method=\"post\" action=\"/chat\"><input type=\"hidden\" name=\"session\" value=\"").append(esc(s.id)).append("\"><input type=\"hidden\" name=\"say\" value=\"").append(esc(l)).append("\"><button class=\"btn opt\">").append(esc(Librarian.labelSaid(l))).append("</button></form> ");   // the name as the person reads it; the value posted is the name the answer is read by
            b.append("<span class=\"k3\">").append(t("or say which in your own words. No answer within {0} minutes counts as none.", Librarian.OFFER_MINUTES)).append("</span></div></div>");
        } else if (!waiting.isEmpty()) {
            // the Librarian asked a question that ends in (y/N) and still waits: two buttons answer it; with no answer the run starts as it was filed
            b.append("<div class=\"ask\"><p>").append(t("The Librarian asked a question and waits for an answer.")).append("</p><div class=\"opts\">")
             .append("<form class=\"inline page-say\" method=\"post\" action=\"/chat\"><input type=\"hidden\" name=\"session\" value=\"").append(esc(s.id)).append("\"><input type=\"hidden\" name=\"say\" value=\"y\"><button class=\"btn primary\">").append(t("Yes")).append("</button></form> ")
             .append("<form class=\"inline page-say\" method=\"post\" action=\"/chat\"><input type=\"hidden\" name=\"session\" value=\"").append(esc(s.id)).append("\"><input type=\"hidden\" name=\"say\" value=\"n\"><button class=\"btn quiet\">").append(t("No")).append("</button></form>")
             .append("<span class=\"k3\">").append(t("No answer within {0} minutes counts as no.", Librarian.OFFER_MINUTES)).append("</span></div></div>");
        }
        return b.append("</div>").toString();
    }

    /** The answer being written for a conversation, as the page polls it: the pieces so far, whether the turn is done, and the person's stop. */
    static final class Live {
        final StringBuilder text = new StringBuilder();
        volatile boolean done, stop, streamed;
        final long since = System.currentTimeMillis();
        synchronized void add(String piece) { text.append(piece); streamed = true; }
        synchronized String text() { return text.toString(); }
    }

    static final Map<String, Live> LIVE = new ConcurrentHashMap<>();

    /**
     * One turn from the page, with the answer streamed as the model writes it, for the page to poll, and the person's stop honoured: the
     * drive's call ends within seconds, the words that came are kept as the answer, and the conversation says it was stopped. The note
     * for the page: "" when the turn ended well.
     */
    /** What a turn from the page's script leaves for the page: the note under the latest turn ("" when the turn ended well), and the conversation to show, when a command opened another. */
    record Turn(String note, Librarian.Session show) { }

    static Turn liveTurn(LibrarianDaemon d, LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Librarian.Session s = chatSession(store, patron, form);
        if (s == null) { chatPost(d, store, patron, form); return new Turn("", null); }
        long now = System.currentTimeMillis();
        LIVE.entrySet().removeIf(e -> e.getValue().done && now - e.getValue().since > 600_000);   // an hour's worth would be plenty; ten minutes is
        Live live = new Live();
        LIVE.put(s.id, live);
        final Librarian.Session[] show = {null};
        try {
            Stopping.within(() -> live.stop, () -> {
                DriveClient.streamHere(live::add);
                try { Librarian.Session next = chatPost(d, store, patron, form); if (next != null && !next.id.equals(s.id)) show[0] = next; } catch (IOException e) { throw new UncheckedIOException(e); } finally { DriveClient.streamHere(null); }
                return null;
            });
            return new Turn("", show[0]);
        } catch (Stopping.Requested st) {
            String partial = live.text().strip();
            ObjectNode a = M.createObjectNode(); a.put("role", "assistant"); a.put("content", (partial.isEmpty() ? "" : partial + "\n\n") + (partial.isEmpty() ? t("(Stopped by you before an answer came.)") : t("(Stopped by you.)"))); a.put("at", System.currentTimeMillis());
            s.append(a);
            return new Turn(partial.isEmpty() ? t("Stopped before an answer came.") : t("Stopped by you; the answer so far is kept."), null);
        } catch (UncheckedIOException e) { throw e.getCause(); }
        catch (RuntimeException e) { return new Turn(t("The Librarian could not answer: {0}", e.getMessage()), null); }
        finally { live.done = true; }
    }

    /** GET /chat/live?session=…: the answer being written, for the page's own poll; empty for a conversation that is not the caller's or has no turn going. */
    static ObjectNode chatLive(LibraryStore store, Patrons.Patron patron, String sessionId) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        ObjectNode r = M.createObjectNode();
        Librarian.Session s = sessionId.isBlank() ? null : Librarian.Session.resume(store, sessionId, patron);
        Live live = s == null ? null : LIVE.get(s.id);
        if (live == null) { r.put("text", ""); r.put("done", true); r.put("streamed", false); return r; }
        r.put("text", live.text()); r.put("done", live.done); r.put("streamed", live.streamed); r.put("stopped", live.stop);
        return r;
    }

    /**
     * The conversation a form names, else the patron's latest; null when there is none (not signed in). A conversation the page opened
     * and nothing was said in yet has no file, so it cannot be resumed: its first turn goes to it all the same, by its id, and not to the
     * latest conversation, which is the previous one (found 2026-11-07).
     */
    static Librarian.Session chatSession(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        String named = form.get("session");
        Librarian.Session s = named == null ? null : Librarian.Session.resume(store, named, patron);
        if (s == null && named != null && named.matches("C-\\d{4}-\\d{2}-\\d{2}-\\d{2}-\\d{2}-\\d{2}(-\\d+)?")) {
            Path dir = Librarian.Session.dir(store, patron);
            if (dir != null) s = new Librarian.Session(named, dir.resolve(named + ".jsonl"));
        }
        return s != null ? s : Librarian.Session.latest(store, patron);
    }

    /**
     * The Chat page's script. The box of turns opens at the latest one. The strip of runs refreshes itself in place, every quarter minute
     * while a run is going and every minute otherwise, and at once after a reply; a stop pressed in it is sent from the page. A turn is
     * sent from the page, and the reply replaces the changing part; the buttons are held while the model works, the box is not. The box to
     * say the next thing is never redrawn, so what is being typed stays; the draft is kept by the browser for this tab as well, so a reload
     * brings it back. Enter sends, Shift+Enter breaks a line. The conversations open as a drawer from their button.
     */
    static String chatPageJs() {
        return CHAT_PAGE_JS.replace("'You'", js(t("You"))).replace("'going onto the shelves'", js(t("going onto the shelves"))).replace("'take it off'", js(t("take it off")))
                .replace("'The Librarian is thinking\\u2026 this can take a while. You can type the next thing meanwhile.'", js(t("The Librarian is thinking… this can take a while. You can type the next thing meanwhile.")))
                .replace("'Being written'", js(t("Being written"))).replace("'stop'", js(t("stop"))).replace("'stopping\\u2026'", js(t("stopping…")))
                .replace("'The reply did not arrive here; the conversation keeps it. Reload the page in a moment.'", js(t("The reply did not arrive here; the conversation keeps it. Reload the page in a moment.")))
                .replace("'" + OVER_FILES + "'", js(t(OVER_FILES_EN, CHAT_FILES))).replace("'" + OVER_BYTES + "'", js(t(OVER_BYTES_EN, (Uploads.MAX >> 20) + " MB")));
    }

    /** The most files one chat message carries: the composer keeps the first ten and says so, and the server does the same with a post that carries more. */
    static final int CHAT_FILES = 10;
    /** What the composer says when more files than that are dropped, and when together they weigh more than a post takes; {0} filled for the page's language. */
    static final String OVER_FILES_EN = "Only the first {0} files were taken; one message takes up to {0}. Send the others with the next message.";
    static final String OVER_BYTES_EN = "Together the files weigh more than {0}, the most one message carries. Take one off, or send them in two messages.";
    static final String OVER_FILES = OVER_FILES_EN.replace("{0}", String.valueOf(CHAT_FILES)), OVER_BYTES = OVER_BYTES_EN.replace("{0}", (Uploads.MAX >> 20) + " MB");

    /** A string for the page's script, quoted. */
    static String js(String s) { return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("<", "\\x3c") + "'"; }

    static final String CHAT_PAGE_JS = "(function(){var view=document.getElementById('chat-view'),form=document.getElementById('chat-say');if(!view||!form)return;"
            + "var inp=form.querySelector('[name=say]'),dkey='rz.chat.draft',busy=false,timer=null;"
            + "function box(){return document.getElementById('chat-scroll')}function end(){var s=box();if(s)s.scrollTop=s.scrollHeight}"
            + "try{var d0=sessionStorage.getItem(dkey);if(d0&&!inp.value)inp.value=d0}catch(e){}"
            + "inp.addEventListener('input',function(){try{sessionStorage.setItem(dkey,inp.value)}catch(e){}});"
            + "inp.addEventListener('keydown',function(ev){if(ev.key==='Enter'&&!ev.shiftKey&&!ev.isComposing){ev.preventDefault();if(form.requestSubmit)form.requestSubmit();else form.dispatchEvent(new Event('submit',{cancelable:true}))}});"
            + "function strip(){return document.getElementById('runs-strip')}"
            + "function poll(now){if(timer)clearTimeout(timer);timer=null;var el=strip();if(!el)return;var active=!!el.querySelector('[data-active=\"1\"]');"
            + "timer=setTimeout(function(){var el=strip();if(!el)return;fetch('/chat/runs?session='+encodeURIComponent(el.dataset.session),{credentials:'same-origin'}).then(function(r){return r.text()}).then(function(t){var now=strip();if(now&&t){now.innerHTML=t;stops()}poll()}).catch(function(){poll()})},now?0:active?15000:60000)}"
            + "function stops(){var fs=document.querySelectorAll('#runs-strip form');for(var i=0;i<fs.length;i++){fs[i].addEventListener('submit',function(ev){ev.preventDefault();var f=ev.target;var bs=f.querySelectorAll('button');for(var j=0;j<bs.length;j++)bs[j].disabled=true;"
            + "fetch('/jobs',{method:'POST',body:new URLSearchParams(new FormData(f)),credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'}}).then(function(){poll(true)}).catch(function(){poll(true)})})}}"
            + "function hold(on){var bs=document.querySelectorAll('.conv button');for(var i=0;i<bs.length;i++){bs[i].disabled=on}}"
            + "function wire(){var fs=view.querySelectorAll('form.page-say');for(var i=0;i<fs.length;i++){fs[i].addEventListener('submit',send)}}"
            // the files of the next message, one list whatever way they came: the Attach button, a drop onto the page, a picture pasted into the box
            + "var fi=document.getElementById('chat-file'),att=document.getElementById('chat-att'),note=document.getElementById('chat-att-note'),cue=document.getElementById('chat-drop'),conv=form.closest('.conv')||document.body,picked=[],MAXN=" + CHAT_FILES + ",MAXB=" + Uploads.MAX + ";"
            + "function size(n){return n<1024?n+' B':n<1048576?Math.round(n/1024)+' KB':(Math.round(n/104857.6)/10)+' MB'}function total(){var t=0;for(var i=0;i<picked.length;i++)t+=picked[i].file.size;return t}"
            + "function say(s){if(note){note.hidden=!s;note.textContent=s||''}}"
            + "function chips(){if(!att)return;att.hidden=!picked.length;att.innerHTML='';for(var i=0;i<picked.length;i++)att.appendChild(chipOf(i));if(total()>MAXB)say('" + OVER_BYTES + "');else if(picked.length<MAXN)say('')}"
            + "function chipOf(i){var p=picked[i],c=document.createElement('span');c.className='chip att';c.textContent='@'+p.name;var z=document.createElement('i');z.textContent=size(p.file.size);c.appendChild(z);var x=document.createElement('b');x.title='take it off';x.setAttribute('role','button');x.tabIndex=0;x.textContent='\\u00d7';function off(){picked.splice(i,1);chips()}x.addEventListener('click',off);x.addEventListener('keydown',function(ev){if(ev.key==='Enter'||ev.key===' '){ev.preventDefault();off()}});c.appendChild(x);return c}"
            + "function stamp(){var d=new Date(),q=function(n){return(n<10?'0':'')+n};return d.getFullYear()+q(d.getMonth()+1)+q(d.getDate())+'-'+q(d.getHours())+q(d.getMinutes())+q(d.getSeconds())}"
            + "function add(list){if(!list)return;var left=0;for(var i=0;i<list.length;i++){var f=list[i];if(!f||(!f.size&&!f.name))continue;if(picked.length>=MAXN){left++;continue}var name=f.name;if(!name||/^image\\.[a-z]+$/i.test(name))name='pasted-'+stamp()+'-'+(picked.length+1)+'.'+((f.type||'').split('/')[1]||'png').replace('jpeg','jpg');picked.push({file:f,name:name})}chips();if(left)say('" + OVER_FILES + "')}"
            + "if(fi)fi.addEventListener('change',function(){add(fi.files);fi.value=''});"
            + "var lab=fi?fi.parentNode:null;if(lab&&lab.tagName==='LABEL')lab.addEventListener('keydown',function(ev){if(ev.key==='Enter'||ev.key===' '){ev.preventDefault();fi.click()}});"
            + "inp.addEventListener('paste',function(ev){var cd=ev.clipboardData;if(!cd||!cd.files||!cd.files.length)return;ev.preventDefault();add(cd.files)});"
            + "function dragged(ev){var ts=ev.dataTransfer&&ev.dataTransfer.types;if(!ts)return false;for(var i=0;i<ts.length;i++)if(ts[i]==='Files')return true;return false}var over=0;function cueOn(on){if(cue)cue.hidden=!on;conv.classList.toggle('dropping',on)}"
            + "document.addEventListener('dragenter',function(ev){if(!dragged(ev))return;ev.preventDefault();over++;cueOn(true)});document.addEventListener('dragover',function(ev){if(!dragged(ev))return;ev.preventDefault();ev.dataTransfer.dropEffect='copy'});"
            + "document.addEventListener('dragleave',function(ev){if(!dragged(ev))return;if(--over<=0){over=0;cueOn(false)}});document.addEventListener('drop',function(ev){if(!dragged(ev))return;ev.preventDefault();over=0;cueOn(false);add(ev.dataTransfer.files);inp.focus()});"
            + "function send(ev){ev.preventDefault();if(busy)return;var f=ev.target,src=f.querySelector('[name=say]'),words=(src.value||'').trim(),files=f===form?picked.slice(0):[],file=files.length?files.map(function(x){return x.name}).join(', '):null;if(!words&&!file)return;if(files.length&&total()>MAXB){chips();inp.focus();return}"
            + "var fd0=new FormData(f);fd0.delete('file');var fd=file?fd0:new URLSearchParams(fd0);for(var i=0;i<files.length;i++)fd.append('file',files[i].file,files[i].name);fd.append('page','1');busy=true;hold(true);"
            + "if(src===inp){inp.value='';try{sessionStorage.removeItem(dkey)}catch(e){}}"
            + "var t=view.querySelector('.chat');if(t&&src.type!=='hidden'){var w=document.createElement('div');w.className='turn you';w.innerHTML='<div class=\"who\"></div><div class=\"say\"><p></p></div>';w.querySelector('.who').textContent='You';w.querySelector('p').textContent=words;if(file){var a=document.createElement('p');a.className='att';a.innerHTML='<b></b> ';a.appendChild(document.createTextNode('going onto the shelves'));a.querySelector('b').textContent='@'+file;w.querySelector('.say').appendChild(a)}t.appendChild(w)}"
            + "var th=document.createElement('p');th.className='thinking';th.textContent='The Librarian is thinking\\u2026 this can take a while. You can type the next thing meanwhile.';(box()||view).appendChild(th);end();"
            + "var sid=f.querySelector('input[name=session]')?f.querySelector('input[name=session]').value:'',lv=null,lt=null;function liveOff(){if(lt)clearTimeout(lt);lt=null}"
            + "function live(){lt=setTimeout(function(){fetch('/chat/live?session='+encodeURIComponent(sid),{credentials:'same-origin'}).then(function(r){return r.json()}).then(function(j){if(!busy)return;if(j.text){if(!lv){lv=document.createElement('div');lv.className='turn lib live';lv.innerHTML='<div class=\"mark model\"><b></b></div><div class=\"say\"><p></p></div><p class=\"att\"><button type=\"button\" class=\"stop\"></button></p>';lv.querySelector('.mark b').textContent='Being written';lv.querySelector('.stop').textContent='stop';lv.querySelector('.stop').addEventListener('click',function(){this.disabled=true;this.textContent='stopping\\u2026';fetch('/chat',{method:'POST',body:'op=stop&session='+encodeURIComponent(sid),credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'}})});th.replaceWith(lv)}var ps=lv.querySelector('.say p');ps.textContent=j.text;var c=document.createElement('span');c.className='caret';ps.appendChild(c);end()}if(!j.done)live()}).catch(function(){live()})},700)}"
            + "live();"
            + "fetch('/chat',file?{method:'POST',body:fd,credentials:'same-origin'}:{method:'POST',body:fd,credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'}}).then(function(r){if(!r.ok)throw new Error(String(r.status));return r.text()})"
            + ".then(function(h){liveOff();var had=document.activeElement;view.innerHTML=h;if(view.querySelector('pre.mermaid')){if(window.rzDraw)window.rzDraw();else{location.reload();return}}busy=false;hold(false);var go=view.querySelector('[data-open-session]');if(go){location.href='/chat?session='+encodeURIComponent(go.getAttribute('data-open-session'));return}if(file){picked=[];chips();say('')}wire();end();poll(true);if(had===document.body||(had&&had.form===form))inp.focus()})"
            + ".catch(function(){liveOff();th.textContent='The reply did not arrive here; the conversation keeps it. Reload the page in a moment.';busy=false;hold(false)})}"
            + "var st=document.getElementById('sessions-toggle'),sc=document.getElementById('sessions-close'),scrim=document.getElementById('scrim');function drawer(o){document.body.classList.toggle('sessions-open',o)}"
            + "if(st)st.addEventListener('click',function(){drawer(!document.body.classList.contains('sessions-open'))});if(sc)sc.addEventListener('click',function(){drawer(false)});if(scrim)scrim.addEventListener('click',function(){drawer(false)});"
            + "form.addEventListener('submit',send);wire();stops();end();window.addEventListener('load',end);poll()})();";

    /**
     * The conversation's turns as HTML, the last {@code limit} messages: what the person said, and each answer with a mark saying where it
     * came from (the shelves, a research run started, the library itself, or the model's own words), what was looked up on the way, how
     * long it took, and the claims it cites with their standing and their sources.
     */
    static String transcript(LibraryStore store, Librarian.Session s, int limit) {
        StringBuilder b = new StringBuilder("<div class=\"chat\">");
        List<JsonNode> all = new ArrayList<>(s.messages());
        if (all.size() > limit) all = all.subList(all.size() - limit, all.size());
        List<String> tools = new ArrayList<>();
        long askedAt = 0;
        for (JsonNode m : all) {
            String role = m.path("role").asText();
            if (role.equals("user")) {
                String words = m.path("content").asText("");
                if (m.path("by").asText("").equals("library") || words.startsWith("Say it in words now")) continue;   // the library's own nudge to the model, not the person's words
                tools.clear(); askedAt = m.path("at").asLong(0);
                b.append("<div class=\"turn you\"><div class=\"who\">").append(t("You")).append("</div><div class=\"say\">").append(prose(words)).append(attChip(m.path("att"))).append("</div></div>");
            } else if (role.equals("tool")) tools.add(m.path("name").asText(""));
            else if (role.equals("assistant") && !m.has("tool_calls") && !m.path("content").asText("").isBlank()) {
                String content = m.path("content").asText("");
                b.append("<div class=\"turn lib\">").append(mark(m, tools, askedAt)).append("<div class=\"say\">").append(prose(content)).append("</div>").append(cites(store, content)).append("</div>");
                tools.clear(); askedAt = 0;
            }
        }
        return b.append("</div>").toString();
    }

    /** The files a turn carried, as chips under the words: each its name, and the entry it is on the shelves as; a folder or an archive, what came of it; a file not kept, that. */
    static String attChip(JsonNode att) {
        if (att == null || !att.isObject() || att.path("name").asText("").isEmpty()) return "";
        if (!att.path("files").isArray() || att.path("files").isEmpty()) return attChip(att.path("name").asText(), att.path("id").asText(""), true, "");
        StringBuilder b = new StringBuilder();
        for (JsonNode f : att.path("files")) b.append(attChip(f.path("name").asText(""), f.path("id").asText(""), f.path("kept").asBoolean(true), f.path("said").asText("")));
        return b.toString();
    }

    static String attChip(String name, String id, boolean kept, String said) {
        return "<p class=\"att\"><b>@" + esc(name) + "</b> " + (kept ? t("on the shelves") : t("not kept")) + (id.isEmpty() ? (said.isEmpty() ? "" : " · " + esc(Acquisitions.compress(said, 160))) : " · <a href=\"/entry/" + enc(id) + "\">" + t("the entry") + "</a>") + "</p>";
    }

    /** The words on each answer's mark, by where the answer came from. */
    static final Map<String, String> MARKS = Map.of("shelves", "From the shelves", "research", "Research started", "library", "From the library", "model", "The model's own words");

    /**
     * An answer's mark: from the shelves when the model looked something up, research started when a tool filed a run, from the library
     * when the library answered itself (a command, a field's own reply), and the model's own words when it looked nothing up; then the
     * time the answer took, and what was looked up on the way in plain words.
     */
    static String mark(JsonNode reply, List<String> tools, long askedAt) {
        String kind = reply.path("by").asText("").equals("library") ? "library" : tools.stream().anyMatch(Librarian.STARTS_RUNS::contains) ? "research" : !tools.isEmpty() ? "shelves" : "model";
        long at = reply.path("at").asLong(0);
        StringBuilder b = new StringBuilder("<div class=\"mark ").append(kind).append("\"><b>").append(t(MARKS.get(kind))).append("</b>");
        if (askedAt > 0 && at >= askedAt) b.append("<span class=\"d\">").append(took(at - askedAt)).append("</span>");
        b.append("</div>");
        if (!tools.isEmpty()) {
            Set<String> seen = new LinkedHashSet<>();
            for (String t : tools) seen.add(toolWords(t));
            b.append("<div class=\"trace\">").append(esc(String.join(" · ", seen))).append("</div>");
        }
        return b.toString();
    }

    /** A span of milliseconds as a person reads it: 0.6 s, 12 s, 3 min. */
    static String took(long ms) {
        if (ms < 10_000) return Chrome.now().decimal(ms / 1000.0, 1) + t(" s");
        if (ms < 120_000) return (ms / 1000) + t(" s");
        return t("{0} min", ms / 60_000);
    }

    /** A tool's name in plain words, for the trace under an answer's mark. */
    static String toolWords(String tool) {
        return switch (tool) {
            case "library_ask" -> t("asked the shelves"); case "library_search" -> t("searched the shelves"); case "library_get" -> t("opened an entry");
            case "library_research" -> t("filed a research run"); case "library_job" -> t("looked at a run"); case "library_status" -> t("the library's counts");
            case "library_help" -> t("the guide"); case "library_frontier" -> t("the open questions"); case "library_inbox" -> t("the inbox"); case "library_map" -> t("the map");
            case "library_changes" -> t("what changed"); case "library_sharpen" -> t("sharpened the question"); case "library_submit" -> t("filed a claim");
            case "library_add" -> t("read something in"); case "library_sources" -> t("the sources"); case "library_settings" -> t("the settings"); case "library_models" -> t("the models");
            default -> tool.replace("library_", "").replace('_', ' ');
        };
    }

    static final Pattern CITED = Pattern.compile("\\b((?:F|I)-\\d{4}-[a-z0-9-]+)\\b");

    /** The claims and reports an answer cites, one line under it: each id, a claim's standing as one mark (established, or not yet) and its sources, a report's claims and date. */
    static String cites(LibraryStore store, String content) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher m = CITED.matcher(content);
        while (m.find() && ids.size() < 12) ids.add(m.group(1));
        if (ids.isEmpty()) return "";
        StringBuilder b = new StringBuilder("<div class=\"cites\">");
        for (String id : ids) {
            try {
                if (id.startsWith("F-")) {
                    Finding f = store.finding(id);
                    if (f == null) continue;
                    boolean est = f.state() == Finding.State.accepted;
                    List<String> hosts = new ArrayList<>();
                    for (Finding.Source src : f.sources()) { String h = host(src.locator()); if (!h.isEmpty() && !hosts.contains(h)) hosts.add(h); }
                    String srcs = f.sources().isEmpty() ? t("no source") : hosts.size() <= 2 && hosts.size() == f.sources().size() ? String.join(" · ", hosts) : t("{0} sources", f.sources().size());
                    b.append("<span class=\"c\">").append(idLink(id)).append(" <a class=\"est").append(est ? "" : " no").append("\" href=\"/entry/").append(enc(id)).append("\" title=\"")
                     .append(est ? t("established: accepted") : t("not yet: {0}", t(f.state().name()))).append("\">").append(est ? "●" : "○").append("</a> <span class=\"src\">· ").append(esc(srcs)).append("</span></span>");
                } else {
                    Investigation inv = store.investigation(id);
                    if (inv == null) continue;
                    b.append("<span class=\"c\">").append(idLink(id)).append(" <span class=\"st\">").append(t("report")).append("</span> <span class=\"src\">· ").append(n(inv.findings().size(), "{0} claim", "{0} claims"))
                     .append(inv.recordedAt().length() >= 10 ? " · " + esc(Chrome.now().day(inv.recordedAt().substring(0, 10))) : "").append("</span></span>");
                }
            } catch (Exception ignored) { }   // an id the shelves no longer hold: the link in the answer still stands
        }
        return b.append("</div>").toString();
    }

    /** A source's host, without www; a locator that is not a web address, cut short. */
    static String host(String locator) {
        if (locator == null || locator.isBlank()) return "";
        if (locator.startsWith("http://") || locator.startsWith("https://")) {
            try { String h = URI.create(locator).getHost(); return h == null ? "" : h.startsWith("www.") ? h.substring(4) : h; } catch (Exception e) { return ""; }
        }
        return Acquisitions.compress(locator, 30);
    }

    /**
     * The library's runs, as the strip beside the chat: every run going or waiting, running first, then those finished within the last
     * day, newest first, up to four; a line each with its name, its phase, a bar and the time, a stop while it goes, the report when it
     * is done. Empty, it says when the housekeeping runs.
     */
    static String runsStrip(LibraryStore store, LibraryProtocol lp, Patrons.Patron patron) { return runsStrip(store, lp, patron, null); }

    /** The library's runs are the keeper's and the writers' to see; someone who may only read sees the runs their own conversation follows, and nothing of anyone else's. */
    static String runsStrip(LibraryStore store, LibraryProtocol lp, Patrons.Patron patron, Librarian.Session s) {
        StringBuilder lines = new StringBuilder();
        int on = 0, waiting = 0;
        try {
            Jobs jobs = new Jobs(store, j -> "");
            long typical = RunProgress.typicalSeconds(lp, args(patron).path("patron")), now = System.currentTimeMillis();
            List<ObjectNode> active = new ArrayList<>(), done = new ArrayList<>();
            if (Patrons.mayWrite(store, patron)) { active.addAll(jobs.active()); done.addAll(jobs.recent(8, null)); }
            else if (s != null) for (String id : s.watched()) { ObjectNode raw = jobs.get(id); if (raw == null) continue; if (RunProgress.of(Jobs.view(raw)).active()) active.add(raw); else done.add(raw); }
            active.sort(Comparator.comparingInt((ObjectNode j) -> j.path("state").asText().equals("running") ? 0 : 1));   // running first, then waiting to start or for an answer
            for (ObjectNode raw : active) {
                RunProgress.View v = RunProgress.of(Jobs.view(raw), now, typical);
                if (v.state().equals("running")) on++; else waiting++;
                lines.append(rline(v, runName(store, raw), raw.path("waiting").asText("")));
            }
            int shown = 0;
            for (ObjectNode raw : done) {
                long ended = RunProgress.millis(raw.path("ended_at").asText(""));
                if (shown >= 4 || (ended > 0 && now - ended > 86_400_000L)) break;   // newest first: past a day old, the rest are older still
                lines.append(rline(RunProgress.of(Jobs.view(raw), now, typical), runName(store, raw), "")); shown++;
            }
        } catch (IOException e) { lines.append("<p class=\"quiet\">").append(esc(t("The runs could not be read: {0}", e.getMessage()))).append("</p>"); }
        String head = on + waiting == 0 ? t("none") : (on > 0 ? t("{0} on", on) : "") + (on > 0 && waiting > 0 ? " · " : "") + (waiting > 0 ? t("{0} waiting", waiting) : "");
        String quiet = Tonight.hour() < 0 ? t("Nothing running. The nightly housekeeping is off.") : t("Nothing running. The housekeeping runs at {0}.", String.format(Locale.ROOT, "%02d:00", Tonight.hour()));
        return "<div class=\"striphead\" data-active=\"" + (on + waiting > 0 ? "1" : "0") + "\"><span>" + t("Runs") + "</span><span>" + head + "</span></div>"
             + (lines.length() == 0 ? "<p class=\"quiet\">" + quiet + "</p>" : lines.toString());
    }

    /** A run's name says what it is for, never its id: the title it was filed with, else its kind and its subject, person or question. */
    static String runName(LibraryStore store, ObjectNode raw) {
        JsonNode a = raw.path("args");
        String kind = raw.path("kind").asText(""), title = a.path("title").asText("");
        if (!title.isBlank()) return Acquisitions.compress(title, 60);
        try {
            switch (kind) {
                case "career-search", "career-read" -> {
                    String slug = a.path("seeker").asText("");
                    for (Career.Seeker k : Career.seekers(store)) if (k.slug().equals(slug)) return kind.equals("career-read") ? t("Reading openings for {0}", k.name()) : t("Job search for {0}", k.name());
                    return kind.equals("career-read") ? t("Reading openings") : t("A job search");
                }
                case "home-search" -> {
                    String slug = a.path("search").asText("");
                    for (Home.Search k : Home.searches(store)) if (k.slug().equals(slug)) return t("Home search: {0}", k.name());
                    return t("A home search");
                }
                case "video-recall" -> { return t("A picture in the videos: {0}", Acquisitions.compress(a.path("description").asText(""), 40)); }
                case "crews" -> { return t("The nightly housekeeping"); }
                case "translate" -> { return a.path("subject").asText("").isBlank() ? t("Translating the dossier") : t("Translating the dossier of {0}", Acquisitions.compress(a.path("subject").asText(), 40)); }
                case Translation.JOB_KIND -> { return t("Translating {0} into {1}", Acquisitions.compress(a.path("name").asText(""), 40), ReadIn.name(a.path("to").asText(""))) + " · " + TranslationWho.shortSaid(TranslationWho.facts(raw), Chrome.now()); }   // and who translates and reads, in a few words
                case "merge" -> { return t("Merging the seeks"); }
                case "family" -> { return t("Family history"); }
                default -> { }
            }
        } catch (IOException ignored) { }
        String q = a.path("question").asText("");
        return !q.isBlank() ? Acquisitions.compress(q, 60) : !kind.isBlank() ? kind : raw.path("job_id").asText("");
    }

    /** One run's line in the strip: its name, its phase, a bar and the time while it goes, with a stop; done, the time it took and the report. The id is the name's tooltip. */
    static String rline(RunProgress.View v, String name, String waiting) { return rline(v, name, waiting, ""); }

    /** The same; {@code resumed}: where a run a restart or an update interrupted picked up, said beside its stage. */
    static String rline(RunProgress.View v, String name, String waiting, String resumed) {
        String link = "<a href=\"/jobs/" + enc(v.id()) + "\" title=\"" + esc(v.id()) + "\">" + esc(name) + "</a>";
        if (v.active()) {
            boolean q = !v.state().equals("running");
            String phase = (q ? v.stage() : waiting.isBlank() ? v.stage() : t("waiting for the model: {0}", waiting)) + (resumed == null || resumed.isBlank() ? "" : " · " + resumed);
            return "<div class=\"rline " + (q ? "queued" : "running") + "\"><span class=\"nm\">" + link + "</span>"
                 + "<form class=\"inline\" method=\"post\" action=\"/jobs\"><input type=\"hidden\" name=\"op\" value=\"stop\"><input type=\"hidden\" name=\"job_id\" value=\"" + esc(v.id()) + "\"><button class=\"stop\">" + (v.state().equals("queued") ? t("remove") : t("stop")) + "</button></form>"
                 + "<span class=\"ph\">" + esc(phase) + "</span><span class=\"bar\"><i style=\"width:" + Math.max(0, Math.min(100, v.percent())) + "%\"></i></span>"
                 + "<span class=\"el\">" + (q ? esc(v.state().equals("queued") ? t("waiting") : t("waiting for you")) : esc(RunProgress.elapsed(v.elapsedSeconds())) + " · " + v.percent() + "%") + "</span></div>";
        }
        return "<div class=\"rline done\"><span class=\"nm\">" + link + "</span><span class=\"el\">" + esc(RunProgress.elapsed(v.elapsedSeconds())) + "</span>"
             + "<span class=\"ph\">" + esc(v.stage()) + (v.report().isEmpty() ? "" : " · " + idLink(v.report(), t("the report"))) + "</span></div>";
    }

    /**
     * GET /chat/runs?session=…: the strip alone, for the page's own refresh. A conversation has no file until its first message,
     * so a fresh one (the New conversation button, a first visit, a day's quiet) does not resume; the strip never depends on it for a
     * writer, and for a reader an unknown or foreign conversation lists no runs (nothing of another person's), under the strip's
     * head as always. An empty answer emptied the section on the page within a minute of opening a fresh conversation (2026-11-08).
     */
    static String chatRuns(LibraryStore store, LibraryProtocol lp, Patrons.Patron patron, String sessionId) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        Librarian.Session s = sessionId.isBlank() ? null : Librarian.Session.resume(store, sessionId, patron);
        return runsStrip(store, lp, patron, s);
    }

    /**
     * The files sent with a turn, every one in the order it came: onto the shelves by the Add page's own path (a file as one document, an
     * archive unpacked, a folder as a collection), and one sentence each in the turn's words so the Librarian can open them; "" when no
     * file came; null when nothing could be kept, which the chat says (a reader may not add to the shelves). A picture ask takes the
     * first picture and a translation ask the first file for itself, as with one file; the others go onto the shelves. Past
     * {@link #CHAT_FILES} files, the rest are not taken, and the words and the chips say so.
     */
    static String attach(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Librarian.Session s, Map<String, String> form) throws IOException {
        List<Uploads.Uploaded> posted = Uploads.files(form, "file");
        if (posted.isEmpty()) return "";
        String names = String.join(", ", posted.stream().map(Uploads.Uploaded::name).toList());
        try {
            if (!mayWrite(store, patron)) { chatReplied(s, "(" + t("a file: {0}", names) + ")", t("Only the person who keeps this library and those who may write to it put files on its shelves, so the file was not kept.")); return null; }
            List<Uploads.Uploaded> files = posted.size() > CHAT_FILES ? posted.subList(0, CHAT_FILES) : posted, over = posted.size() > CHAT_FILES ? posted.subList(CHAT_FILES, posted.size()) : List.of();
            String say = form.getOrDefault("say", "");
            List<Shelved> all = new ArrayList<>();   // every file, in order: the one an ask took first, then the rest as shelved, then the ones past the tenth
            List<Uploads.Uploaded> rest = new ArrayList<>(files);
            ObjectNode att = null;
            // a picture with a picture ask ("find more pictures of this person", "what is this?"): the first picture kept on the shelves as the
            // person's own and handed to the turn by its kept file, since the upload's file goes at the end of this method (0.7.0)
            if (ImagesTurn.wantsPicture(say)) {
                Uploads.Uploaded pic = files.stream().filter(u -> Images.isPictureFile(u.path())).findFirst().orElse(null);
                if (pic != null) {
                    Images.Kept k = Images.keepGiven(store, Files.readAllBytes(pic.path()), Images.contentType(pic.name()), pic.name(), "", "");
                    if (k != null) { att = M.createObjectNode().put("name", pic.name()).put("path", store.root().resolve(k.file()).toString()).put("id", k.id()).put("picture", true); all.add(new Shelved(pic.name(), "", "", k.id(), true)); rest.remove(pic); }
                }
            }
            if (att == null && (TranslateTurn.asksTranslation(say) || TranslateTurn.answersWhichFile(say, s))) {   // the first file is to be translated: kept under the library for the run, which shelves it with the translation
                Uploads.Uploaded first = files.get(0);
                Path kept = Translation.keepCopy(store, first.path(), first.name());
                att = M.createObjectNode().put("name", first.name()).put("path", kept.toString());
                all.add(new Shelved(first.name(), "", "", "", true)); rest.remove(0);
            }
            shelveFiles(p, patron, rest, "", all);
            for (Uploads.Uploaded u : over) all.add(new Shelved(u.name(), "One message takes up to " + CHAT_FILES + " files; this one was past the tenth and was not taken. Send it with the next message.", "", "", false));
            if (att == null) {
                if (all.stream().noneMatch(Shelved::kept)) {
                    String why = String.join(" ", all.stream().map(x -> x.name() + ": " + x.full()).toList());
                    chatReplied(s, "(" + t("a file: {0}", names) + ")", t("The file could not be shelved: {0}", why)); return null;
                }
                Shelved first = all.get(0);   // the next turn's words carry the files: the first as before, every one under files[]
                att = M.createObjectNode().put("name", first.name()).put("id", first.id()).put("title", first.id().isEmpty() ? "" : first.said());
            }
            ArrayNode fs = att.putArray("files");
            for (Shelved x : all) fs.addObject().put("name", x.name()).put("id", x.id()).put("title", x.id().isEmpty() ? "" : x.said()).put("kept", x.kept()).put("said", x.id().isEmpty() ? x.said() : "");
            s.att = att;
            StringBuilder b = new StringBuilder();
            for (Shelved x : all.subList(att.has("path") ? 1 : 0, all.size())) {   // the file an ask took for itself needs no sentence: the turn has it
                b.append("\n\n");
                if (!x.id().isEmpty()) b.append("I attached ").append(x.name()).append("; it is on the shelves as ").append(x.id()).append(x.said().isEmpty() ? "" : " (" + x.said() + ")").append('.').append(x.problems());
                else if (x.kept()) b.append("I attached ").append(x.name()).append(". ").append(x.full());
                else b.append("I attached ").append(x.name()).append(", which was not kept: ").append(x.full());
            }
            return b.toString();
        } finally { Uploads.drop(form); }
    }

    /** A turn the library answers itself: what was said and the answer, kept in the session as the model's turns are, the answer marked as the library's. */
    static void chatReplied(Librarian.Session s, String said, String answer) throws IOException {
        long now = System.currentTimeMillis();
        ObjectNode u = M.createObjectNode(); u.put("role", "user"); u.put("content", said); u.put("at", now); if (s.att != null) { u.set("att", s.att); s.att = null; } s.append(u);
        ObjectNode a = M.createObjectNode(); a.put("role", "assistant"); a.put("content", answer); a.put("by", "library"); a.put("at", now); s.append(a);
    }

    /** The browser chat's /help: the same text as the terminal's, through {@link ChatCommands}. */
    static String chatHelp(LibraryStore store) { return ChatCommands.help(store, null); }

    /**
     * One turn from the form: the words go to the Librarian, the reply lands in the session, the page shows it. A line the library answers
     * itself (a command, a field's line) goes through {@link ChatCommands}, the same path as the terminal's. Returns the conversation the
     * page shows next: the same one, or the one {@code /new} or {@code /resume} opened; null when nothing was said.
     */
    static Librarian.Session chatPost(LibrarianDaemon d, LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        String words = form.getOrDefault("say", "").strip();
        if (words.isEmpty() && form.getOrDefault("file.path", "").isBlank()) return null;
        Patrons.check(store, patron, Patrons.Level.read);
        Librarian.Session chosen = chatSession(store, patron, form);
        if (chosen == null) { Uploads.drop(form); throw ProtocolError.forbidden(t(CHAT_SIGN_IN)); }
        // a day's quiet ends a conversation: this message opens a new one, whose first reply says so; the earlier one stays on the list
        final Librarian.Session s = chosen.quietForADay(System.currentTimeMillis()) ? Librarian.Session.open(store, patron) : chosen;
        if (s != chosen) s.opening(t("This is a new conversation; the earlier one, {0} ({1}), is on the list.", sessionTitle(chosen), Chrome.now().day(chosen.day())));
        String attached = attach(store, new LibraryProtocol(store), patron, s, form);   // a file chosen in the composer: onto the shelves first, by the Add page's own path; the turn says so
        if (attached == null) return s;
        words = (words + attached).strip();
        if (words.isEmpty()) return s;
        final Librarian.Session[] opened = {null};
        ChatCommands.Front front = new ChatCommands.Front() {
            @Override public Librarian.Session session() { return s; }
            @Override public String open() throws IOException { opened[0] = Librarian.Session.open(store, patron); return opened[0].id; }
            @Override public Librarian.Session resume(String id) throws IOException { Librarian.Session sx = Librarian.Session.resume(store, id, patron); if (sx != null) opened[0] = sx; return sx; }
        };
        String answer = ChatCommands.reply(store, patron, "web:" + s.id, words, !Librarian.stillWaiting(store, s).isEmpty(), front);
        if (answer != null) { chatReplied(s.gone() && opened[0] != null ? opened[0] : s, words, answer); return opened[0] != null ? opened[0] : s; }   // /delete of this conversation: the exchange goes into the fresh one the page goes to
        new Librarian(store, d.chatDrive(s), patron, s).say(words);
        return s;
    }

    /** Paragraphs from the Librarian's plain text, ids linked. */
    /**
     * The chat's words: paragraphs with their line breaks kept, an entry's id a link. A ```mermaid block is drawn as on a report's page and
     * any other fence is code; a picture in a paragraph of its own is shown at reading size, and one among other lines (the list of
     * candidates "find pictures of …" answers with) small.
     */
    static String prose(String text) {
        if (text.indexOf("```") < 0) return paragraphs(text, false);
        StringBuilder b = new StringBuilder(), plain = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].stripTrailing();
            if (line.startsWith("```")) {
                boolean drawn = isDiagramFence(line);
                StringBuilder code = new StringBuilder();
                for (i++; i < lines.length && !lines[i].stripTrailing().startsWith("```"); i++) code.append(lines[i]).append('\n');
                b.append(paragraphs(plain.toString(), true)); plain.setLength(0);
                b.append(drawn ? diagram(code.toString()) : "<pre>" + esc(code.toString().stripTrailing()) + "</pre>");
                continue;
            }
            plain.append(lines[i]).append('\n');
        }
        return b.append(paragraphs(plain.toString(), true)).toString();
    }

    private static final Pattern PICTURE = Pattern.compile("!\\[([^\\]]*)\\]\\(((?:https?://|/(?!/))[^)\\s]+)\\)");

    /** The paragraphs of some chat words; {@code skipBlank}: a stretch between two fences that holds nothing makes no paragraph. */
    private static String paragraphs(String text, boolean skipBlank) {
        StringBuilder b = new StringBuilder();
        for (String para : text.split("\\n\\s*\\n")) {
            String p = para.strip();
            if (skipBlank && p.isEmpty()) continue;
            Matcher fig = FIGURE_LINE.matcher(p);
            if (fig.matches()) { b.append(figure(fig.group(1), fig.group(2))); continue; }   // a picture the Librarian placed, in a paragraph of its own
            String e = esc(p).replaceAll("\\[((?:F|I|D)-\\d{4}-[a-z0-9-]+)\\]", "[<a href=\"/entry/$1\">$1</a>]").replace("\n", "<br>");
            if (p.indexOf("![") >= 0) {   // pictures among other lines: small
                Matcher m = PICTURE.matcher(e);
                StringBuilder small = new StringBuilder();
                while (m.find()) m.appendReplacement(small, Matcher.quoteReplacement("<img class=\"thumb\" src=\"" + m.group(2) + "\" alt=\"" + m.group(1) + "\" loading=\"lazy\" referrerpolicy=\"no-referrer\">"));
                m.appendTail(small);
                e = small.toString();
            }
            b.append("<p>").append(e).append("</p>");
        }
        return b.toString();
    }

    static String page(LibraryStore store, Patrons.Patron patron, String title, String body) throws IOException { return page(store, patron, title, body, 0, null); }

    /**
     * On a report's page: the way to a new in-depth run that builds on it. The questions the report itself left open, each with a
     * button that sends it as a deep run; and a box for your own follow-up question, sent with the report named at the front, which is
     * how a run is handed the earlier report from the shelves before it starts.
     */
    static String goDeeper(ObjectNode e) {
        String id = e.path("id").asText(), title = Acquisitions.compress(e.path("title").asText(""), 90);
        StringBuilder b = new StringBuilder("<details class=\"deeper\"><summary><b>").append(t("Go deeper")).append("</b></summary>");
        b.append("<p class=\"k\">").append(t("A new research run that starts from this report. The run is given this report first, so it builds on what is here instead of reading it all again.")).append("</p>");
        if (e.path("open_questions").size() > 0) {
            b.append("<p>").append(t("Questions this report left open. Each button sends that question as an in-depth run:")).append("</p>");
            for (JsonNode q : e.path("open_questions")) {
                b.append("<form method=\"post\" action=\"/research\" class=\"inline\"><input type=\"hidden\" name=\"question\" value=\"").append(esc("Follow-up to " + id + " (" + title + "): " + q.asText())).append("\">")
                 .append("<input type=\"hidden\" name=\"mode\" value=\"depth\"><input type=\"hidden\" name=\"sources\" value=\"both\">").append(nonceField())
                 .append("<button class=\"quiet\">").append(t("Send")).append("</button> ").append(esc(q.asText())).append("</form>");
            }
        }
        b.append("<form method=\"post\" action=\"/research\" class=\"stack\"><label>").append(t("Your own follow-up question about this report")).append("<br><textarea name=\"question\" rows=\"3\" placeholder=\"").append(esc(t("for example: which of these practices have been tested in a small team, and what did the tests find?"))).append("\"></textarea></label>")
         .append("<input type=\"hidden\" name=\"follow_up_to\" value=\"").append(esc(id + " (" + title + ")")).append("\"><input type=\"hidden\" name=\"mode\" value=\"depth\"><input type=\"hidden\" name=\"sources\" value=\"both\">").append(nonceField())
         .append("<button>").append(t("Send as an in-depth run")).append("</button> <a class=\"k\" href=\"/research?q=").append(enc("Follow-up to " + id + " (" + title + "): ")).append("\">").append(t("or open the full research form")).append("</a></form></details>");
        return b.toString();
    }

    /** {@code refresh} > 0 makes the page reload itself after that many seconds — at {@code to} when given, else in place. */
    static String page(LibraryStore store, Patrons.Patron patron, String title, String body, int refresh, String to) throws IOException { return page(store, patron, title, body, refresh, to, false); }

    /** {@code wide}: the page uses the whole window (the map), not the reading column. */
    static String page(LibraryStore store, Patrons.Patron patron, String title, String body, int refresh, String to, boolean wide) throws IOException {
        return page(store, patron, title, body, refresh, to, wide ? "wide" : "Chat".equals(title) ? "chat" : "", "Chat".equals(title));
    }

    /** {@code mainClass}: "chat" and "split" fill the window and bring their own head; "wide" uses the whole window; "" is the reading column. */
    static String page(LibraryStore store, Patrons.Patron patron, String title, String body, int refresh, String to, String mainClass, boolean ownHead) throws IOException {
        String name = store.identity().name();
        String meta = refresh > 0 ? "<meta http-equiv=\"refresh\" content=\"" + refresh + (to == null ? "" : ";url=" + esc(to)) + "\">" : "";
        String who = patron.web() && !WebAccess.signInRequired() ? t("open to everyone") + " · <a href=\"/login\">" + t("sign in") + "</a>"
                : patron.web() || patron.anonymous() ? "<a href=\"/login\">" + t("sign in") + "</a>"
                : esc(patron.person() && "the person".equals(patron.name()) ? t("you") : patron.name().isEmpty() ? patron.did() : patron.name()) + " · <a href=\"/account\">" + t("account") + "</a> · <a href=\"/logout\">" + t("sign out") + "</a>";
        String path = PATH.get() == null ? "" : PATH.get();
        Chrome lang = Chrome.now();
        boolean keeper = mayWrite(store, patron), fill = mainClass.equals("chat") || mainClass.equals("split");   // the chat, and a report beside it: the window's height, the whole width
        return "<!doctype html><html lang=\"" + lang.code + "\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc((title == null ? name : title + " — " + name) + " · ResearchZosho") + "</title>" + meta + "<link rel=\"icon\" href=\"/favicon.ico\" type=\"image/png\"><script>" + LOOK_BOOT + "</script><style>" + CSS + "</style></head><body" + (fill ? " class=\"fill\"" : "") + ">"
                + "<header class=\"top\"><a class=\"brand\" href=\"/\">ResearchZosho <i>· " + esc(name) + "</i></a><span class=\"ver\">" + esc(Version.number() == null ? "dev" : Version.number()) + "</span>"
                + "<nav class=\"verbs\">" + rail(store, patron, path) + "</nav>"
                + (keeper ? "<a class=\"keep\" href=\"/keep\"" + (keepPath(path) && !path.equals("/settings") ? " aria-current=\"page\"" : "") + ">" + t("Keep") + "</a>"
                        + "<a class=\"keep\" href=\"/settings\"" + (path.equals("/settings") ? " aria-current=\"page\"" : "") + ">" + t("Settings") + "</a>" : "")   // Settings beside Keep in the top menu (the keeper, 2026-11-07)
                + "<details class=\"contents\"><summary>" + t("all pages") + "</summary><div class=\"groups\">" + siteNav(store, patron) + "</div></details>"
                + "<span class=\"who\">" + who + "</span>"
                + "<span class=\"look-switch\">" + t("look:") + " <button type=\"button\" data-look=\"system\" aria-pressed=\"true\">" + t("system") + "</button> · <button type=\"button\" data-look=\"light\" aria-pressed=\"false\">" + t("light") + "</button> · <button type=\"button\" data-look=\"dark\" aria-pressed=\"false\">" + t("dark") + "</button></span>"
                + langSwitch(lang) + "</header>"
                + "<main" + (mainClass.isEmpty() ? "" : " class=\"" + mainClass + "\"") + ">" + (title == null || ownHead ? "" : "<h1>" + esc(title) + "</h1>") + body + "</main>"
                + "<footer>ResearchZosho " + esc(Version.string()) + " · <a href=\"https://researchzosho.org\">researchzosho.org</a>"
                + (Version.updateNotice().isEmpty() ? "" : " · <b>" + t("{0} is available", esc(Version.latestCached())) + "</b>: " + t("update with the install command on {0}; your library and settings stay", "<a href=\"https://researchzosho.org/#install\">researchzosho.org</a>"))
                + "</footer><script>" + LOOK_JS + "</script>"
                + (body.contains(DIAGRAM_MARK) ? "<script src=\"" + MERMAID_PATH + "\"></script><script>" + diagramJs() + "</script>" : "")   // a diagram to draw: Mermaid from this library's own /static/, and its drawing
                + "</body></html>";
    }

    /**
     * The language switch in the header, beside the look switch: a small menu that shows the language in use and opens to each language
     * the chrome is written in, by its own name (日本語, 中文, 한국어, Deutsch, Français, Español, Português, Italiano, Русский, English),
     * the one in use marked; a choice keeps itself in the browser. A menu, not a row, so ten languages fit a phone's width; no script.
     */
    static String langSwitch(Chrome lang) {
        List<String> all = Chrome.available();
        if (all.size() < 2) return "";
        List<String> order = new ArrayList<>(all.subList(1, all.size())); order.add(Chrome.EN);   // the languages with a file, English last
        String href = HREF.get() == null ? "/" : HREF.get();
        StringBuilder b = new StringBuilder("<details class=\"lang-switch\"><summary>").append(t("language:")).append(" <span lang=\"").append(lang.code).append("\">").append(esc(Chrome.nativeName(lang.code))).append("</span></summary><div class=\"langs\">");
        for (String c : order)
            b.append("<a href=\"").append(esc(Chrome.switchHref(href, c))).append("\" hreflang=\"").append(c).append("\" lang=\"").append(c).append("\"").append(c.equals(lang.code) ? " aria-current=\"true\"" : "").append(">").append(esc(Chrome.nativeName(c))).append("</a>");
        return b.append("</div></details>").toString();
    }

    /** The rail: the chat, the past and the updates with the count of what is new, the page it is on marked. Keep, for the keeper, stands beside it. */
    static String rail(LibraryStore store, Patrons.Patron patron, String path) {
        int n = patron.anonymous() ? 0 : updatesCount(store, patron);
        return railLink("/", t("Chat"), path.equals("/") || path.startsWith("/chat"))
             + railLink("/past", t("Past"), path.equals("/past") || path.startsWith("/jobs") || path.startsWith("/entry/") || path.startsWith("/dossier") || path.equals("/read"))
             + railLink("/updates", t("Updates") + (n > 0 ? " <span class=\"n\">" + n + "</span>" : ""), path.equals("/updates") || path.equals("/inbox") || path.equals("/changes"));
    }

    static String railLink(String href, String label, boolean current) { return "<a href=\"" + href + "\"" + (current ? " aria-current=\"page\"" : "") + ">" + label + "</a>"; }

    /** The addresses that are the keeper's: Keep itself and the pages it leads to. */
    static boolean keepPath(String path) { return path.equals("/keep") || path.equals("/settings") || path.equals("/sources") || path.equals("/owned") || path.startsWith("/account"); }

    /** Before the stylesheet: the look the browser remembered, so the page never flashes the other one. Nothing remembered follows the computer's own setting. */
    static final String LOOK_BOOT = "(function(){var t=null;try{t=localStorage.getItem('rz.theme')}catch(e){}if(t==='light'||t==='dark')document.documentElement.setAttribute('data-theme',t)})();";

    /** The look switch in the header: system, light or dark, remembered by the browser; system forgets the choice and follows the computer. */
    static final String LOOK_JS = "(function(){var bs=document.querySelectorAll('.look-switch [data-look]');if(!bs.length)return;"
            + "function mark(v){for(var i=0;i<bs.length;i++)bs[i].setAttribute('aria-pressed',bs[i].getAttribute('data-look')===(v||'system')?'true':'false')}"
            + "var v=null;try{v=localStorage.getItem('rz.theme')}catch(e){}if(v!=='light'&&v!=='dark')v=null;mark(v);"
            + "for(var i=0;i<bs.length;i++)bs[i].addEventListener('click',function(){var v=this.getAttribute('data-look');"
            + "if(v==='system'){document.documentElement.removeAttribute('data-theme');try{localStorage.removeItem('rz.theme')}catch(e){}}"
            + "else{document.documentElement.setAttribute('data-theme',v);try{localStorage.setItem('rz.theme',v)}catch(e){}}mark(v)})})();";

    /**
     * The stylesheet, on every page. The brand's palette: cream, plum and teal by day, night, plum and lavender by dark; light unless
     * the computer or the switch says dark; every colour a token on :root, the dark ones under the computer's setting and under the
     * switch's. The old six names stay as aliases for the pages that style a thing of their own (the map, a dossier's columns).
     */
    static final String CSS = """
            :root{color-scheme:light;--paper:#faf3ea;--paper-2:#f2e8da;--paper-3:#e8dcc9;--ink:#2d1436;--ink-2:#574866;--ink-3:#6f6180;--rule:#e4d9cb;--rule-2:#cbbcae;--accent:#1a6b75;--on-accent:#ffffff;--accept:#2a6f3f;--dispute:#b03a2b;--wait:#7d5f16;--run:#3b5fa8;--serif:Georgia,'Noto Serif',serif;--mono:ui-monospace,'SF Mono',Menlo,Consolas,monospace;--sans:system-ui,-apple-system,'Segoe UI',sans-serif;--col:44em;--strip:17em;--bg:var(--paper);--card:var(--paper-2);--line:var(--rule);--k:var(--ink-2)}
            @media (prefers-color-scheme:dark){:root:not([data-theme=light]){color-scheme:dark;--paper:#16121c;--paper-2:#2a1745;--paper-3:#35215a;--ink:#e8dcf2;--ink-2:#bcaccb;--ink-3:#9688a8;--rule:#322744;--rule-2:#4b3b66;--accent:#c08cf0;--on-accent:#16121c;--accept:#7fc48f;--dispute:#ef8b78;--wait:#d8b45a;--run:#8fb0ec}}
            :root[data-theme=dark]{color-scheme:dark;--paper:#16121c;--paper-2:#2a1745;--paper-3:#35215a;--ink:#e8dcf2;--ink-2:#bcaccb;--ink-3:#9688a8;--rule:#322744;--rule-2:#4b3b66;--accent:#c08cf0;--on-accent:#16121c;--accept:#7fc48f;--dispute:#ef8b78;--wait:#d8b45a;--run:#8fb0ec}
            *{box-sizing:border-box}
            body{margin:0;background:var(--paper);color:var(--ink);font:17px/1.55 var(--serif);-webkit-font-smoothing:antialiased}
            a{color:var(--accent);text-decoration:none}a:hover{text-decoration:underline}
            button,input,textarea,select{font:inherit;color:inherit}input,textarea,select{max-width:100%}
            header.top{display:flex;flex-wrap:wrap;align-items:baseline;gap:.4em 1.4em;padding:1em 1.6em .7em;border-bottom:1px solid var(--rule);position:relative}
            header.top .brand{font-weight:600;color:var(--ink)}header.top .brand i{font-weight:400;font-style:italic;color:var(--ink-2)}header.top .ver{font:.72em var(--mono);color:var(--ink-3);border:1px solid var(--rule-2);border-radius:.4em;padding:0 .35em;margin-left:-.6em}
            header.top nav.verbs{display:flex;gap:1.2em;flex-wrap:wrap}header.top nav.verbs a{color:var(--ink-2)}header.top nav.verbs a[aria-current]{color:var(--ink);border-bottom:2px solid var(--accent)}
            header.top nav.verbs a .n{font:.78em var(--mono);color:var(--accent);margin-left:.3em}
            header.top .keep{margin-left:auto;color:var(--ink-2);border:1px solid var(--rule-2);border-radius:2em;padding:.15em .8em;font-size:.88em;white-space:nowrap}header.top .keep[aria-current]{color:var(--ink);border-color:var(--accent)}
            header.top .who{color:var(--ink-3);font-size:.92em}header.top .who a{color:var(--ink-2)}
            details.contents{display:inline}details.contents>summary{list-style:none;cursor:pointer;color:var(--ink-2);font-size:.92em}details.contents>summary::-webkit-details-marker{display:none}
            details.contents>summary::after{content:" ▾";font-size:.8em}details.contents[open]>summary::after{content:" ▴"}
            details.contents:not([open]) .groups{display:none}details.contents .groups{position:absolute;left:0;right:0;top:100%;z-index:5;background:var(--paper-2);border-bottom:1px solid var(--rule);padding:1.2em 1.6em 1.6em;display:grid;grid-template-columns:repeat(auto-fit,minmax(12em,1fr));gap:1.2em 2em;box-shadow:0 12px 24px -16px rgba(0,0,0,.4)}
            .nav-group b{display:block;font-weight:500;font-variant-caps:all-small-caps;letter-spacing:.1em;color:var(--ink-3);margin-bottom:.3em}.nav-group a{display:block;color:var(--ink);padding:.12em 0}.nav-group .ico{display:inline-block;width:1.5em;color:var(--ink-3)}
            .look-switch,.lang-switch{font-size:.86em;color:var(--ink-3);white-space:nowrap}.lang-switch a{color:var(--ink-2);text-decoration:underline;text-decoration-color:var(--rule-2);text-underline-offset:.2em}.lang-switch a[aria-current]{color:var(--ink);text-decoration-color:var(--accent)}.look-switch button{border:0;background:none;color:var(--ink-2);cursor:pointer;padding:0 .2em;text-decoration:underline;text-decoration-color:var(--rule-2);text-underline-offset:.2em}.look-switch button[aria-pressed=true]{color:var(--ink);text-decoration-color:var(--accent)}
            details.lang-switch{position:relative;display:inline-block}details.lang-switch>summary{list-style:none;cursor:pointer;color:var(--ink-3)}details.lang-switch>summary::-webkit-details-marker{display:none}details.lang-switch>summary span{color:var(--ink-2);text-decoration:underline;text-decoration-color:var(--rule-2);text-underline-offset:.2em}
            details.lang-switch>summary::after{content:" ▾";font-size:.8em}details.lang-switch[open]>summary::after{content:" ▴"}details.lang-switch:not([open]) .langs{display:none}
            details.lang-switch .langs{position:absolute;right:0;top:100%;z-index:6;display:flex;flex-direction:column;min-width:9em;max-width:calc(100vw - 2em);margin-top:.3em;padding:.35em 0;background:var(--paper-2);border:1px solid var(--rule);border-radius:4px;box-shadow:0 12px 24px -16px rgba(0,0,0,.4)}
            details.lang-switch .langs a{padding:.3em .9em;text-decoration:none}details.lang-switch .langs a:hover{background:var(--paper-3)}details.lang-switch .langs a[aria-current]{font-weight:600}
            main{max-width:var(--col);margin:0 auto;padding:2em 1.4em 4em;overflow-wrap:anywhere}main.wide{max-width:none}
            footer{text-align:center;color:var(--ink-3);font-size:.86em;padding:2em 1em 3em;border-top:1px solid var(--rule);margin-top:2em}
            .kicker{font-variant-caps:all-small-caps;letter-spacing:.12em;color:var(--ink-3);margin:0 0 .3em}
            h1{font-weight:500;font-size:2.05em;line-height:1.15;letter-spacing:-.01em;margin:0 0 .5em}h1 i{font-weight:400;color:var(--ink-2)}
            h2{font-size:1.08em;font-weight:600;margin:2.4em 0 .7em;padding-top:.7em;border-top:1px solid var(--rule)}h2 .k,h2 a.k{font-weight:400;color:var(--ink-3);font-size:.85em;margin-left:.8em}
            h3{font-size:1em;font-weight:600;margin:1.4em 0 .4em}
            p{margin:.6em 0}.k{color:var(--ink-2);font-size:.92em}.k3{color:var(--ink-3)}.small{font-size:.88em}.err{color:var(--dispute)}
            .lede{font-size:1.08em;color:var(--ink-2);font-style:italic}
            button{font-size:.9em;padding:.42em 1em;border:1px solid var(--accent);background:var(--accent);color:var(--on-accent);border-radius:2px;cursor:pointer}
            button.quiet{background:transparent;color:var(--ink-2);border-color:var(--rule-2)}button:disabled,.btn:disabled{opacity:.5;cursor:default}
            .btn{display:inline-block;font-size:.9em;padding:.42em 1em;border:1px solid var(--ink);background:transparent;color:var(--ink);border-radius:2px;cursor:pointer;white-space:nowrap;text-decoration:none}
            .btn.primary{background:var(--accent);border-color:var(--accent);color:var(--on-accent)}.btn.quiet{border-color:var(--rule-2);color:var(--ink-2)}.btn.text{border:0;padding:.2em .3em;color:var(--accent)}
            input.line,textarea.line,select.line{width:100%;border:0;border-bottom:1px solid var(--rule-2);background:transparent;padding:.35em 0;outline:0;color:var(--ink)}input.line:focus,textarea.line:focus{border-bottom-color:var(--ink)}
            form.big,form.ask{display:flex;align-items:center;flex-wrap:wrap;gap:.6em .8em;border-bottom:2px solid var(--ink);padding:.35em 0;margin:.4em 0 1.8em}
            form.big input,form.ask input{flex:1;min-width:12em;border:0;background:transparent;font-size:1.15em;font-style:italic;outline:0;padding:.2em 0;color:var(--ink)}form.big input::placeholder,form.ask input::placeholder{color:var(--ink-3)}form.big input:focus,form.ask input:focus{font-style:normal}
            form.stack label{display:block;margin:.8em 0}form.stack textarea,form.stack input,form.stack select{font:inherit;padding:.4em;border:1px solid var(--rule-2);background:var(--paper-2);color:var(--ink);width:100%;max-width:min(40em,100%)}
            form.pick ul{list-style:none;padding-left:0}form.pick ul ul{padding-left:1.6em}form.pick h3.group{margin:1em 0 .2em;font-size:1.02em}form.pick details{margin:.2em 0 .2em 1.6em}form.pick summary{cursor:pointer}form.pick li{margin:.35em 0}form.pick label{display:block}form.pick .bar{display:flex;gap:.7em;align-items:center;flex-wrap:wrap;margin:.8em 0;position:sticky;top:0;background:var(--paper);padding:.4em 0}
            form.stack input[size],form.stack input[type=checkbox]{width:auto}form.stack label.choice{display:block}ul.tight{padding-left:1.2em}ul.tight li{margin:.2em 0}ol.hits li{margin:.7em 0}.snip{color:var(--ink-3)}form.inline{display:inline}
            .card{border:1px solid var(--rule-2);padding:1em 1.2em;background:var(--paper-2);margin:.8em 0}.card h2{border:0;margin:0 0 .4em;padding:0;font-size:1.1em}.card h3{margin:.2em 0;font-size:1.05em}.card p{margin:.4em 0}
            .badge{display:inline-block;padding:0 .5em;border-radius:.6em;font-size:.8em;background:var(--paper-3)}.badge.accepted{background:var(--accept);color:var(--paper)}.badge.disputed,.badge.failed{background:var(--dispute);color:var(--paper)}.badge.running{background:var(--run);color:var(--paper)}
            pre{white-space:pre-wrap;word-break:break-word;background:var(--paper-2);border:1px solid var(--rule);padding:.8em;font-size:.9em}pre.raw{max-height:70vh;overflow:auto}code{font-family:var(--mono);font-size:.9em}
            .body{margin:1em 0;word-break:break-word}.triple{font-style:italic}
            .notice{border:1px solid var(--rule-2);border-left:3px solid var(--accent);padding:.7em 1em;margin:1.2em 0;background:var(--paper-2)}.notice p{margin:.3em 0}
            details.sec{border-top:1px solid var(--rule)}details.sec:last-of-type{border-bottom:1px solid var(--rule)}
            details.sec>summary{list-style:none;cursor:pointer;padding:.85em 0;display:grid;grid-template-columns:1.1em minmax(0,1fr) auto;gap:.7em;align-items:baseline}details.sec>summary::-webkit-details-marker{display:none}
            details.sec>summary::before{content:"›";color:var(--ink-3);transition:transform .15s}details.sec[open]>summary::before{transform:rotate(90deg)}
            details.sec>summary h2{border:0;margin:0;padding:0;font-size:1.05em}details.sec>summary .gist{color:var(--ink-2);font-size:.92em;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
            details.sec>.in{padding:0 0 1.4em 1.8em}details.sub>summary{cursor:pointer;color:var(--ink-2)}
            .working{display:flex;gap:1em;align-items:flex-start;margin:1.5em 0}.spin{flex:none;width:22px;height:22px;margin-top:1em;border:3px solid var(--rule);border-top-color:var(--accent);border-radius:50%;animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}
            p.src{margin:.3em 0}progress{width:10em;height:.7em;vertical-align:middle;accent-color:var(--run)}p.run{margin:.3em 0}
            p.ref{margin:.3em 0;padding-left:2.2em;text-indent:-2.2em;word-break:break-word}p.ref b{color:var(--ink-3)}
            .tablewrap,.tw{overflow-x:auto;margin:.8em 0}table{border-collapse:collapse;width:100%;font-size:.92em}
            th{text-align:left;font-weight:500;font-variant-caps:all-small-caps;letter-spacing:.06em;color:var(--ink-3);border-bottom:1px solid var(--ink);padding:.3em .8em .3em 0;white-space:nowrap}
            td{padding:.5em .8em .5em 0;border-bottom:1px solid var(--rule);vertical-align:top}td.num,th.num{text-align:right}td.num{font-variant-numeric:tabular-nums}
            .id{font:.74em var(--mono);color:var(--ink-3);white-space:nowrap}.id:hover{color:var(--accent)}
            .cite,a.cite{font-size:.74em;color:var(--ink-3);vertical-align:super;line-height:0;margin-left:.1em;text-decoration:none}
            .st{font-variant-caps:all-small-caps;letter-spacing:.07em;color:var(--ink-2);text-decoration:underline;text-decoration-thickness:2px;text-underline-offset:.2em;text-decoration-color:var(--rule-2);white-space:nowrap}.st.accepted{text-decoration-color:var(--accept)}.st.disputed{text-decoration-color:var(--dispute)}.st.waiting{text-decoration-color:var(--wait)}
            .meta{font-size:.84em;color:var(--ink-3)}.mark{font-size:.8em;padding:0 .4em;border-radius:.5em;background:var(--paper-3)}.mark.bad{background:var(--dispute);color:var(--paper)}
            .entries{margin:0;padding:0;list-style:none}.entries li{padding:.65em 0;border-top:1px solid var(--rule);display:grid;grid-template-columns:7.2em minmax(0,1fr);gap:.2em 1em}.entries:last-child li:last-child{border-bottom:1px solid var(--rule)}
            .entries .when{font:.78em/1.9 var(--mono);color:var(--ink-3)}.entries .what{margin:0}.entries .what .k3{display:block;font-size:.9em}.entries.plain li{grid-template-columns:1fr}
            .chips{display:flex;flex-wrap:wrap;gap:.4em .6em;font-size:.86em}.chip{border:1px solid var(--rule-2);border-radius:1em;padding:.15em .7em;color:var(--ink-2)}.chip b{color:var(--ink);font-weight:500}.chip.none{border-style:dashed}.chip.on{background:var(--ink);color:var(--paper);border-color:var(--ink)}
            .sheet{background:var(--paper-2);border-left:3px solid var(--rule-2);padding:.9em 1.2em;margin:1em 0}.sheet.liked{border-left-color:var(--accent)}.sheet p{margin:.4em 0}.sheet .t{font-weight:600}.sheet .t a{color:var(--ink)}
            .reading p{margin:.8em 0}
            .cards{display:grid;grid-template-columns:1fr 1fr;gap:1.6em;margin:1.4em 0}.cards .card{margin:0}.card .found{border-left:3px solid var(--accept);padding-left:.7em;margin:.6em 0}.card .nf{border-left:3px solid var(--rule-2);padding-left:.7em;margin:.6em 0;color:var(--ink-3)}
            .drop{margin:1.4em 0;padding:1em 1.2em;border:1px dashed var(--rule-2);text-align:center;color:var(--ink-2)}.drop .btns{display:flex;flex-wrap:wrap;gap:.5em;justify-content:center;margin-top:.6em}
            h2.plain{border:0;margin:0 0 .4em;padding:0;font-size:1.6em;font-weight:500}.first{flex:none;border-bottom:1px solid var(--rule);margin-bottom:.6em}.first .cards{grid-template-columns:1fr 1fr}body.fill:has(.first){height:auto;overflow:auto}body.fill:has(.first)>main{display:block}body.fill:has(.first) .shell{height:auto}body.fill:has(.first) .conv{height:auto}body.fill:has(.first) .turns{max-height:40vh}body.fill:has(.first)>footer{display:block}
            .urow{display:grid;grid-template-columns:6.5em minmax(0,1fr) auto;gap:.2em 1em;padding:.75em 0;border-top:1px solid var(--rule);align-items:baseline}.updates .urow:last-child{border-bottom:1px solid var(--rule)}
            .urow .w{font:.8em var(--mono);color:var(--ink-3)}.urow .rep{display:block;font-weight:500}.urow .rep a{color:var(--ink)}.urow .kind{display:inline;font-variant-caps:all-small-caps;letter-spacing:.08em;color:var(--ink-3);font-weight:400}.urow .g{display:block;color:var(--ink-2);font-size:.9em}.urow .acts{display:flex;gap:.4em;flex-wrap:wrap;justify-content:flex-end}.urow .acts .btn{font-size:.82em;padding:.3em .7em}
            .urow.seen .rep{font-weight:400}.urow.seen .rep a{color:var(--ink-2)}
            main.split{max-width:none;padding:0}.split .cols{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.3fr);gap:0 2.4em;max-width:96em;margin:0 auto;padding:1.4em 1.4em 1em;align-items:stretch;height:100%}
            .split .conv{max-width:none}.split .conv .strip.below{flex:none;max-height:12em;overflow:auto;margin-top:.8em}
            .reading{min-width:0;height:100%;min-height:0;overflow:auto;border-left:1px solid var(--rule);padding:0 .4em 2em 2.4em}.reading .rhead{display:flex;align-items:baseline;gap:1em;border-bottom:1px solid var(--rule);padding-bottom:.5em;margin-bottom:.6em}.reading .rhead .kicker{margin:0;flex:1}.reading .rhead a.close{font-size:1.1em;color:var(--ink-2)}.reading .back{display:none}
            .legend{font-size:.86em;color:var(--ink-3);margin:.2em 0 .8em}.legend .est{cursor:default}
            .made dt{font-variant-caps:all-small-caps;letter-spacing:.07em;color:var(--ink-3);float:left;clear:left;width:6.5em}.made dd{margin:0 0 .3em 7em}
            @media (max-width:900px){.split .cols{grid-template-columns:1fr;height:auto}.split .conv{display:none}.reading{border-left:0;padding-left:0;height:auto;overflow:visible}.reading .back{display:inline-block;margin-bottom:.8em}.reading .rhead a.close{display:none}body.fill:has(main.split){height:auto;overflow:auto}body.fill:has(main.split)>footer{display:block}
            }
            @media (max-width:700px){.made dt{float:none;width:auto}.made dd{margin-left:0}}
            .prow{display:grid;grid-template-columns:7em minmax(0,1fr) 6.5em;gap:.2em 1em;padding:.7em 0;border-top:1px solid var(--rule);align-items:baseline}.past .prow:last-child{border-bottom:1px solid var(--rule)}
            .prow .kind{font-variant-caps:all-small-caps;letter-spacing:.08em;color:var(--ink-3)}.prow .t{font-weight:500}.prow .t a{color:var(--ink)}.prow .w{font:.8em var(--mono);color:var(--ink-3);text-align:right}
            .state{font-size:.95em;color:var(--ink-2);border-left:3px solid var(--rule-2);padding:.45em .9em;margin:.6em 0 1em;background:var(--paper-2)}.state b{color:var(--ink);font-weight:500}.state.being-made{border-left-color:var(--run)}.state.declined,.state.failed,.state.refused{border-left-color:var(--dispute)}
            .prow .state{display:block;margin:.3em 0 0;font-size:.88em;padding:.3em .7em}.prow.being-made .t a,.prow.declined .t a,.prow.failed .t a,.prow.stopped .t a,.prow.not-yet .t a{color:var(--ink-2)}
            .chips{margin:.6em 0}a.chip{text-decoration:none}a.chip:hover{border-color:var(--ink)}
            body.fill{height:100vh;height:100dvh;display:flex;flex-direction:column;overflow:hidden}body.fill>header{flex:none}body.fill>footer{display:none}body.fill>main{flex:1;min-height:0;min-width:0;width:100%;max-width:none;padding:0;display:flex;flex-direction:column}
            .shell{display:grid;grid-template-columns:minmax(0,1fr);max-width:calc(var(--col) + 3em + var(--strip));margin:0 auto;padding:1.4em 1.4em 1em;gap:0 2.2em;align-items:stretch;height:100%;width:100%;grid-template-rows:minmax(0,1fr)}.shell.beside{grid-template-columns:minmax(0,1fr) var(--strip)}
            .shell,.conv,.strip{min-width:0}.conv{display:flex;flex-direction:column;height:100%;min-height:0;max-width:var(--col);position:relative}
            .convhead{flex:none;display:flex;flex-wrap:wrap;align-items:baseline;gap:.4em 1em;padding:0 0 .6em;border-bottom:1px solid var(--rule)}.convhead .title{font-weight:500}.convhead .btn{font-size:.84em}.convhead .counts{margin-left:auto}
            .chat-view{flex:1;min-height:0;display:flex;flex-direction:column}.turns{flex:1;min-height:6em;overflow:auto;padding-right:.4em;overscroll-behavior:contain}
            .turn{padding:1.1em 0;border-top:1px solid var(--rule)}.turn:first-child{border-top:0}.turn .who{font-variant-caps:all-small-caps;letter-spacing:.1em;color:var(--ink-3);margin-bottom:.25em}.turn.you .who{color:var(--ink-2)}.turn.you .say{font-style:italic}
            .thumb{display:block;max-width:160px;max-height:160px;width:auto;height:auto;margin:.3em 0;border-radius:4px;border:1px solid var(--rule)}
            figure.fig{margin:1em 0}figure.fig a{display:inline-block;max-width:100%}.figure{display:block;max-width:100%;max-height:60vh;width:auto;height:auto;border-radius:4px;border:1px solid var(--rule)}figure.fig figcaption{font-size:.86em;color:var(--ink-3);margin-top:.35em}
            figure.diagram{margin:1em 0}pre.mermaid.drawn{background:none;border:0;padding:0;white-space:normal;overflow-x:auto}pre.mermaid.drawn svg{max-width:100%;height:auto}details.diagram-text{font-size:.86em;color:var(--ink-3)}details.diagram-text summary{cursor:pointer}details.diagram-text pre{font-size:1em;margin-top:.4em}.diagram-error{font-size:.86em;color:var(--dispute)}
            .turn .say p{margin:0 0 .6em}.turn .say p:last-child{margin-bottom:0}.turn.lib .say p:first-child{margin-top:0}.turn .att{font-style:normal;font-size:.86em;color:var(--ink-3);margin-top:.4em}.turn .att b{font-weight:500;color:var(--ink-2);font-family:var(--mono);font-size:.92em}
            .chip.att{font-family:var(--mono);font-size:.82em;border-color:var(--rule-2);background:var(--paper)}.chip.att b{font-weight:400;color:var(--ink-3);margin-left:.4em;cursor:pointer}.chip.att i{font-style:normal;color:var(--ink-3);margin-left:.45em;font-size:.9em}form.composer .chips{margin:0 0 .4em}form.composer .note{margin:0 0 .4em;color:var(--ink-2)}form.composer label.btn input{display:none}
            .dropcue[hidden],.chips[hidden],.note[hidden]{display:none}.dropcue{position:absolute;inset:0;z-index:9;display:flex;align-items:center;justify-content:center;background:var(--paper-2);opacity:.92;border:2px dashed var(--accent);font-size:1.15em;color:var(--ink);pointer-events:none}.dropping form.composer{border-color:var(--accent)}
            .turn .mark{display:inline-flex;align-items:baseline;gap:.5em;font-variant-caps:all-small-caps;letter-spacing:.08em;color:var(--ink-3);margin-bottom:.35em;padding:0 0 0 .7em;border-left:3px solid var(--rule-2);border-radius:0;background:none;font-size:1em}
            .turn .mark b{font-weight:500;color:var(--ink-2)}.turn .mark .d{font-variant-caps:normal;letter-spacing:0;font-size:.9em}.turn .mark.shelves{border-left-color:var(--accept)}.turn .mark.research{border-left-color:var(--run)}.turn .mark.library{border-left-color:var(--wait)}.turn .mark.model{border-left-color:var(--rule-2)}
            .trace{font-size:.8em;color:var(--ink-3);margin:0 0 .5em}
            .cites{margin:.7em 0 0;padding:.5em 0 0;border-top:1px dotted var(--rule-2);font-size:.86em;color:var(--ink-3);display:flex;flex-wrap:wrap;gap:.3em 1.2em}.cites .c{white-space:nowrap}.cites .c .src{color:var(--ink-3)}
            .est{font:.8em var(--sans);color:var(--accept);text-decoration:none;margin:0 .1em;cursor:help;vertical-align:.1em}.est.no{color:var(--wait)}.est:hover{text-decoration:none}
            .ask{margin:.8em 0 0;padding:.7em .9em;border:1px solid var(--rule-2);background:var(--paper-2)}.ask p{margin:0 0 .5em}.ask .opts{display:flex;flex-wrap:wrap;gap:.5em;align-items:center}.ask .k3{font-size:.86em}.ask .opts .btn.opt{text-align:left;white-space:normal}
            .thinking{font-style:italic;color:var(--ink-3)}.note{margin:.4em 0}.caret{display:inline-block;width:.55em;height:1em;background:var(--ink-2);vertical-align:-.15em;margin-left:.1em;animation:blink 1s steps(2) infinite}@keyframes blink{50%{opacity:0}}@media (prefers-reduced-motion:reduce){.caret{animation:none}}.turn.live .stop{font-size:.86em;color:var(--accent);background:none;border:0;padding:0;cursor:pointer}
            form.composer{flex:none;margin-top:1em;border:1px solid var(--ink);background:var(--paper-2);padding:.6em .8em}form.composer textarea{width:100%;border:0;background:transparent;resize:vertical;min-height:3.2em;outline:0;color:var(--ink);display:block}form.composer textarea::placeholder{color:var(--ink-3);font-style:italic}
            form.composer .row{display:flex;flex-wrap:wrap;gap:.5em 1em;align-items:center;border-top:1px solid var(--rule);padding-top:.5em;margin-top:.3em}form.composer .row .hint{flex:1;min-width:12em;font-size:.84em;color:var(--ink-3)}
            .strip{font-size:.92em;max-height:100%;overflow:auto;min-height:0}.strip .striphead{display:flex;justify-content:space-between;align-items:baseline;font-variant-caps:all-small-caps;letter-spacing:.1em;color:var(--ink-3);border-bottom:1px solid var(--rule);padding:0 0 .35em;margin:0 0 .3em}
            .rline{display:grid;grid-template-columns:minmax(0,1fr) auto;grid-template-areas:"nm stop" "ph ph" "bar el";gap:.15em .7em;align-items:center;padding:.55em 0;border-bottom:1px solid var(--rule)}
            .rline .nm{grid-area:nm;font-weight:500}.rline .nm a{color:var(--ink)}.rline form{grid-area:stop;justify-self:end}.rline .ph{grid-area:ph;color:var(--ink-2);font-size:.92em}.rline .bar{grid-area:bar;height:3px;background:var(--rule);position:relative}.rline .bar i{position:absolute;left:0;top:0;bottom:0;background:var(--run)}.rline .el{grid-area:el;font:.8em var(--mono);color:var(--ink-3);white-space:nowrap}
            .rline .stop{font-size:.84em;color:var(--accent);background:none;border:0;padding:0;cursor:pointer}.rline.queued .bar{background:repeating-linear-gradient(90deg,var(--rule-2) 0 5px,transparent 5px 10px)}.rline.queued .nm{color:var(--ink-2);font-weight:400}
            .rline.done{grid-template-areas:"nm el" "ph ph"}.rline.done .nm::before{content:"✓ ";color:var(--accept)}.rline.done .ph a{color:var(--accent)}.strip .quiet{color:var(--ink-3);font-style:italic;margin:.4em 0}
            .sessions{position:fixed;top:0;left:0;bottom:0;width:min(22em,100vw);background:var(--paper-2);border-right:1px solid var(--rule-2);box-shadow:12px 0 32px -20px rgba(0,0,0,.5);transform:translateX(-100%);transition:transform .2s;z-index:30;display:flex;flex-direction:column}
            body.sessions-open .sessions{transform:none}.sessions .head{display:flex;align-items:baseline;gap:.6em;padding:.9em 1.2em .6em;border-bottom:1px solid var(--rule)}.sessions .head b{font-weight:500;flex:1}.sessions .head button{border:0;background:none;color:var(--ink-2);cursor:pointer;padding:0 .3em}
            .sessions .body{flex:1;overflow:auto;padding:.6em 1.2em 1.4em}.sessions .g{font-variant-caps:all-small-caps;letter-spacing:.1em;color:var(--ink-3);margin:.9em 0 .2em}.sessions a.s{display:block;color:var(--ink);padding:.3em 0;border-top:1px solid var(--rule);font-size:.95em}.sessions a.s.on{color:var(--accent)}.sessions a.s .w{font:.76em var(--mono);color:var(--ink-3);margin-left:.4em}
            .sessions .srow{display:flex;flex-wrap:wrap;align-items:baseline;gap:0 .6em;border-top:1px solid var(--rule)}.sessions .srow a.s{flex:1;min-width:0;border-top:0}
            .sessions details.remove>summary,.convhead details.remove>summary{list-style:none;cursor:pointer;font-size:.78em;color:var(--ink-3)}.sessions details.remove>summary::-webkit-details-marker,.convhead details.remove>summary::-webkit-details-marker{display:none}
            .sessions details.remove[open],.convhead details.remove[open]{flex-basis:100%}details.remove form p{margin:.3em 0 .5em}
            .scrim{position:fixed;inset:0;background:rgba(0,0,0,.25);z-index:29;display:none}body.sessions-open .scrim{display:block}
            @media (max-width:900px){.shell.beside{display:flex;flex-direction:column;grid-template-columns:1fr}.shell.beside .conv{flex:1;min-height:0;max-width:none}.strip.side{position:static;margin-top:.8em;max-height:12em;flex:none}}
            @media (max-width:700px){body{font-size:16px}header.top{padding:.8em 1em .6em;gap:.4em 1em}header.top nav.verbs{gap:.6em 1em;width:100%;order:3;font-size:.95em}header.top .keep{margin-left:0;order:4}header.top .who{font-size:.86em}.look-switch{order:5}.lang-switch{order:5}details.contents{order:6}.btn,header.top .keep{white-space:normal;max-width:100%}button{max-width:100%;white-space:normal}
            main{padding:1.6em 1em 4em}h1{font-size:1.7em}.entries li{grid-template-columns:1fr;gap:0}table{display:block;overflow-x:auto;max-width:100%}
            table.stack thead{display:none}table.stack tr{display:block;padding:.5em 0;border-bottom:1px solid var(--rule)}table.stack td{display:block;border:0;padding:.05em 0}table.stack td.num{text-align:left}table.stack td[data-l]::before{content:attr(data-l) ": ";font-variant-caps:all-small-caps;letter-spacing:.07em;color:var(--ink-3)}
            .strip{font-size:.84em}.strip.side{max-height:9.5em}.rline,.rline.done{grid-template-columns:minmax(0,1fr) 5.5em auto auto;grid-template-areas:"nm bar el stop";padding:.3em 0;gap:.1em .5em}.rline .ph{display:none}.rline.done{grid-template-areas:"nm ph el ."}.rline.done .ph{display:block}.rline.done .bar{display:none}.cites .c{white-space:normal}.rline .el{white-space:normal}
            .cards,.first .cards{grid-template-columns:1fr}.shell{padding:.8em 1em .6em}.convhead .counts{margin-left:0}.prow{grid-template-columns:1fr;gap:.1em}.prow .w{text-align:left}.urow{grid-template-columns:1fr;gap:.2em}.urow .acts{justify-content:flex-start}
            details.sec>summary{grid-template-columns:1em minmax(0,1fr)}details.sec>summary .gist{grid-column:2;white-space:normal}details.sec>.in{padding-left:0}}
            """;

    // ---- http ----

    static Map<String, String> form(String body, String contentType) {
        Map<String, String> m = new LinkedHashMap<>();
        if (contentType == null || !contentType.startsWith("application/x-www-form-urlencoded")) return m;
        for (String kv : body.split("&")) {
            if (kv.isEmpty()) continue;
            int eq = kv.indexOf('=');
            m.put(URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8), eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    /** Whether the request came through the shared door, where the relay in front is HTTPS. */
    static boolean onDoor(HttpExchange x) { return Boolean.TRUE.equals(x.getAttribute(LibrarianDaemon.DOOR)); }
    /** Whether a cookie set now wants Secure: on the shared door, which a relay reaches over https, or when a trusted proxy says the person came over https. */
    static boolean secure(HttpExchange x) { return onDoor(x) || Proxy.https(x); }

    /**
     * The sign-in cookie: Secure on the shared door and behind a trusted proxy on https, Lax everywhere — with Strict, a join link clicked in a message signed the browser in
     * and then showed the home page as a visitor, because the cookie is withheld on the redirect that follows a cross-site click.
     */
    static void setCookie(HttpExchange x, String token) { x.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax" + (secure(x) ? "; Secure" : "") + "; Max-Age=" + Accounts.sessionDays() * 86400L); }
    static void clearCookie(HttpExchange x) { x.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Lax" + (secure(x) ? "; Secure" : "") + "; Max-Age=0"); }
    static void clearAsk(HttpExchange x) { x.getResponseHeaders().add("Set-Cookie", ASK + "=; Path=/; HttpOnly; SameSite=Lax" + (secure(x) ? "; Secure" : "") + "; Max-Age=0"); }
    static void redirectTo(HttpExchange x, String to) throws IOException { redirect(x, to); }

    static String cookie(HttpExchange x, String name) {
        List<String> all = x.getRequestHeaders().get("Cookie");
        if (all == null) return null;
        for (String h : all) for (String c : h.split(";")) {
            String s = c.strip();
            if (s.startsWith(name + "=")) return s.substring(name.length() + 1);
        }
        return null;
    }

    /** A JSON answer, for the passkey calls the page's script makes. */
    static void sendJson(HttpExchange x, int status, JsonNode body) throws IOException {
        byte[] bytes = M.writeValueAsBytes(body);
        x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = x.getResponseBody()) { os.write(bytes); }
    }

    static void send(HttpExchange x, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-store");   // every page is the library as it is now; a browser must never show an old one (a denial kept showing after access opened, 2026-10-09)
        // no scripts, except: the map (its own script, its own data), the runs strip, the look switch in every page's header, a pick form (the "all" and group boxes tick the others
        // with an inline handler; with scripts blocked they ticked nothing, 2026-09-09), and the Mermaid this library serves itself on a page that draws a diagram
        // pictures come from this library alone, except a found picture shown small from its address on the web (the chat's "find pictures of …"), which that page then allows over https
        String img = html.contains("class=\"thumb\" src=\"https://") || html.contains("class=\"figure\" src=\"https://") ? "img-src 'self' https:" : "img-src 'self'";
        // a page that draws a diagram loads Mermaid from this library's own /static/ ('self' joins its script sources); every other page's policy is as it was
        String script = html.contains("<script src=\"" + MERMAID_PATH + "\"") ? "script-src 'self' 'unsafe-inline'" : "script-src 'unsafe-inline'";
        x.getResponseHeaders().set("Content-Security-Policy", html.contains("id=\"c\"></canvas>") || html.contains("id=\"runs-strip\"") || html.contains("id=\"chat-scroll\"") || html.contains("class=\"look-switch\"") || html.contains("id=\"signin\"") || html.contains("id=\"passkey-add\"")
                ? "default-src 'none'; " + img + "; style-src 'unsafe-inline'; " + script + "; connect-src 'self'; form-action 'self'"
                : html.contains("class=\"pick\"")
                ? "default-src 'none'; " + img + "; style-src 'unsafe-inline'; " + script + "; form-action 'self'"
                : "default-src 'none'; " + img + "; style-src 'unsafe-inline'; form-action 'self'");
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = x.getResponseBody()) { os.write(bytes); }
    }

    static void redirect(HttpExchange x, String to) throws IOException {
        x.getResponseHeaders().set("Location", to);
        x.sendResponseHeaders(303, -1);
        x.close();
    }

    /**
     * The library holds a field's own work (a family read in, a family-history run asked for), so the field's pages are in the menu: told
     * by where the work came from, never by what an ordinary claim happens to say, and without reading a claim.
     */
    static boolean hasFamily(LibraryStore store) { return Fields.holdsModuleWork(store); }

    /** Every page, grouped, behind the header's one word: the library, the work, what is yours, and each field's own pages. */
    static String siteNav(LibraryStore store, Patrons.Patron patron) {
        boolean yours = patron.person() || (patron.web() && !WebAccess.signInRequired());
        StringBuilder b = new StringBuilder();
        b.append(navGroup(t("Library"), navLink("/", t("Home"), "⌂") + navLink("/add", t("Add"), "+") + navLink("/ask", t("Ask"), "?") + navLink("/search", t("Search"), "⌕") + navLink("/subjects", t("Subjects"), "☷") + navLink("/map", t("Map"), "⊞") + navLink("/dossier", t("Dossiers"), "▤") + navLink(ImagesPage.PATH, t("Pictures"), "▣")));   // the dossiers are the library's own front door to its people and organizations, not a field's page
        b.append(navGroup(t("Work"), navLink("/inbox", t("Inbox"), "✉") + navLink("/questions", t("Open questions"), "⁇") + navLink("/jobs", t("Runs"), "▶") + navLink("/research", t("Research"), "⚗") + navLink(SeeksPage.PATH, t("Seeks"), "⫶") + navLink(BridgesPage.PATH, t("Bridges"), "⌁") + navLink(ForecastsPage.PATH, t("Forecasts"), "◔") + navLink("/sources", t("Sources"), "☵") + navLink("/suggest", t("Suggested"), "✦") + navLink("/changes", t("Changes"), "↻")));
        b.append(navGroup(t("Ways in"), navLink(PerspectivesPage.PATH, t("Perspectives"), "⁂") + navLink(SubmitPage.PATH, t("Submit a claim"), "✎") + navLink(LaunchPage.PATH, tc("Read in", READ_IN_PAGE), "⇲") + (yours ? navLink(DbPage.PATH, t("Databases"), "▦") : "")));
        b.append(navGroup(t("Yours"), navLink("/chat", t("Chat"), "❝") + navLink("/help", t("Help"), "ⓘ") + (yours ? navLink("/owned", t("Owned"), "◆") + navLink("/settings", t("Settings"), "⚙") : "") + (SignIn.hasAccountPage(patron) ? navLink("/account", t("Account"), "☺") : "")));
        String fields = fieldMenu(store, patron);
        if (!fields.isEmpty()) b.append(navGroup(t("Fields"), fields));
        return b.toString();
    }

    static String navLink(String href, String label, String symbol) { return "<a href=\"" + href + "\" title=\"" + esc(label) + "\"><span class=\"ico\">" + symbol + "</span><span class=\"lbl\">" + esc(label) + "</span></a>"; }

    static String navGroup(String name, String links) { return "<div class=\"nav-group\"><b>" + name + "</b>" + links + "</div>"; }

    static String fieldMenu(LibraryStore store, Patrons.Patron patron) {
        StringBuilder b = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();   // two fields may lead to one page: once
        for (Profile f : Fields.enabled(store)) {   // a field's pages: when it holds work, or when its page is where one begins (the career field); the chat's tools wait for the work
            if (f.joinsOnlyWhenAsked() && !f.menuAlways() && !Fields.holdsWork(store, f)) continue;
            for (Profile.Link l : f.menu()) if ((!l.writersOnly() || mayWrite(store, patron)) && seen.add(l.href())) b.append(navLink(l.href(), t(l.label()), l.symbol()));   // a glyph of its own, like the core's entries
        }
        return b.toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20"); }
}
