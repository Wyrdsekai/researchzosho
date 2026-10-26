package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HexFormat;
import java.security.SecureRandom;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.net.URI;
import java.nio.file.Files;
import java.net.URLDecoder;
import java.time.Instant;
import java.util.Collections;
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
    /** The cookie a visitor who asked to be let in carries: the request's id and claim secret, until the owner answers. */
    static final String ASK = "rz_ask";

    private Pages() { }

    static boolean isPage(String path) {
        return path.equals("/") || path.equals("/search") || path.equals("/ask") || path.startsWith("/entry/") || path.equals("/read")
                || path.equals("/subjects") || path.equals("/changes") || path.equals("/questions") || path.equals("/dossier") || path.equals("/suggest") || path.equals("/inbox") || path.equals("/jobs") || path.startsWith("/jobs/")
                || path.equals("/research") || path.equals("/login") || path.equals("/login/code") || path.equals("/login/passkey") || path.equals("/login/passkey/options") || path.equals("/logout") || path.equals("/join") || path.equals("/account") || path.equals("/account/passkey") || path.equals("/account/passkey/options") || path.equals("/explain") || path.equals("/download") || path.equals("/map") || path.equals("/chat") || path.equals("/chat/runs") || path.equals("/chat/panel") || path.equals("/remove") || path.equals("/settings") || path.equals("/owned")
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
        LibraryProtocol p = new LibraryProtocol(store);
        TYPICAL = () -> RunProgress.typicalSeconds(p, LibrarianDaemon.patronNode(Patrons.Patron.PERSON));   // looked up at most every five minutes
        try {
            switch (path) {
                case "/" -> send(x, 200, home(d, store, p, patron));
                case "/search" -> send(x, 200, search(store, p, patron, q));
                case "/ask" -> send(x, 200, ask(store, p, patron, q));
                case "/chat/runs" -> send(x, 200, chatRuns(store, p, patron, q.getOrDefault("session", "")));
                case "/chat/panel" -> send(x, 200, chatPanel(store, p, patron, ""));
                case "/chat" -> {
                    if ("POST".equals(method) && "1".equals(form.get("panel"))) {   // the panel beside a page: the reply comes back as the panel's content, the page stays
                        String note = "";
                        try { chatPost(d, store, patron, form); } catch (RuntimeException e) { note = "The Librarian could not answer: " + e.getMessage(); }
                        send(x, 200, chatPanel(store, p, patron, note));
                    } else if ("POST".equals(method) && "1".equals(form.get("page"))) {   // the Chat page's own script: the reply comes back as the page's changing part; the box to say the next thing is never sent back, so what is typed in it stays
                        String note = "";
                        try { chatPost(d, store, patron, form); } catch (RuntimeException e) { note = "The Librarian could not answer: " + e.getMessage(); }
                        Librarian.Session s = chatSession(store, patron, form);
                        send(x, 200, s == null ? "<p class=\"k\">" + esc(CHAT_SIGN_IN) + " <a href=\"/login\">Sign in</a></p>" : chatView(store, p, patron, s, note));
                    } else if ("POST".equals(method)) { chatPost(d, store, patron, form); redirect(x, "/chat"); }
                    else send(x, 200, chat(store, patron, q));
                }
                case "/read" -> send(x, 200, read(store, p, patron, q));
                case "/subjects" -> send(x, 200, subjects(store, p, patron));
                case "/changes" -> send(x, 200, changes(store, p, patron, q));
                case "/suggest" -> {
                    if ("POST".equals(method) && form.containsKey("outcome")) {
                        Patrons.check(store, patron, Patrons.Level.write);
                        try { Suggestions.mark(store, form.getOrDefault("id", ""), form.getOrDefault("outcome", ""), ""); } catch (IllegalArgumentException ignored) { }
                    }
                    send(x, 200, suggestPage(store, patron));
                }
                case "/dossier" -> {
                    if ("POST".equals(method) && form.containsKey("watch")) {   // the page's Watch button: a standing search on the subject
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
                case "/jobs" -> { if ("POST".equals(method)) { jobsPost(p, patron, form); redirect(x, "/jobs"); } else send(x, 200, jobs(store, p, patron, q)); }
                case "/explain" -> send(x, 200, explain(store, p, patron, q));
                case "/download" -> download(x, store, patron, q);
                case "/settings" -> {
                    String op = form.getOrDefault("op", "");
                    if ("POST".equals(method) && op.equals("invite")) {
                        Patrons.check(store, patron, Patrons.Level.write);
                        Invites.Invite inv = Invites.make(store, form.getOrDefault("name", ""), Patrons.levelOf(form.getOrDefault("level", "read")));
                        send(x, 200, settings(store, p, patron, Map.of("join", inv.link())));   // the link is shown once, on the page, never in an address
                    }
                    else if ("POST".equals(method) && op.equals("reader-link")) {
                        Patrons.check(store, patron, Patrons.Level.write);
                        try { send(x, 200, settings(store, p, patron, Map.of("join", SignIn.renewLink(store, form.getOrDefault("did", "").strip())))); }
                        catch (IOException e) { redirect(x, "/settings?note=" + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8)); }
                    }
                    else if ("POST".equals(method) && op.startsWith("reader-")) { String note = readersPost(store, patron, form); redirect(x, "/settings?note=" + URLEncoder.encode(note, StandardCharsets.UTF_8)); }
                    else if ("POST".equals(method)) { String note = settingsPost(p, patron, form); redirect(x, "/settings?note=" + URLEncoder.encode(note, StandardCharsets.UTF_8)); }
                    else send(x, 200, settings(store, p, patron, q));
                }
                case "/owned" -> { if ("POST".equals(method)) { ownedPost(p, patron, form); redirect(x, "/owned"); } else send(x, 200, owned(store, p, patron, q)); }
                case "/map" -> { Patrons.check(store, patron, Patrons.Level.read); send(x, 200, page(store, patron, "Map", MapPage.body(q.getOrDefault("focus", "")), 0, null, true)); }
                case "/research" -> {
                    if ("POST".equals(method) && "1".equals(form.get("sharpen"))) send(x, 200, sharpenStart(store, patron, form.getOrDefault("question", ""), form.getOrDefault("field", "")));
                    else if ("POST".equals(method)) send(x, 200, researchPost(d, store, patron, form));
                    else if (q.containsKey("sharpen")) send(x, 200, sharpenPoll(store, patron, q.get("sharpen"), q.getOrDefault("field", "")));
                    else send(x, 200, researchForm(store, patron, q.getOrDefault("q", ""), null, q.getOrDefault("field", "")));
                }
                case "/login" -> { if ("POST".equals(method)) SignIn.login(x, store, form); else send(x, 200, SignIn.loginPage(store, null, x)); }
                case "/login/code" -> { if ("POST".equals(method)) SignIn.code(x, store, form); else redirect(x, "/login"); }
                case "/login/passkey/options" -> SignIn.passkeyOptions(x, store, patron, false);
                case "/login/passkey" -> SignIn.passkeyAssert(x, store);
                case "/logout" -> { String c = cookie(x, COOKIE); if (c != null && !c.isBlank()) Accounts.signOut(store, c); clearCookie(x); redirect(x, "/"); }
                case "/join" -> SignIn.join(x, store, method, q, form);   // a link the person who keeps the library sent: the invited person chooses a password and is signed in
                case "/account" -> {
                    if ("POST".equals(method)) { String note = SignIn.accountPost(x, store, patron, form); redirect(x, "signout-all".equals(form.get("op")) && cookie(x, COOKIE) != null ? "/" : "/account?note=" + enc(note)); }
                    else send(x, 200, SignIn.account(store, patron, q, x));
                }
                case "/account/passkey/options" -> SignIn.passkeyOptions(x, store, patron, true);
                case "/account/passkey" -> SignIn.passkeyRegister(x, store, patron);
                default -> {
                    Profile.Page own;
                    if (path.startsWith("/entry/")) send(x, 200, entry(store, p, patron, path.substring("/entry/".length())));
                    else if (path.startsWith("/jobs/")) send(x, 200, job(store, p, patron, path.substring("/jobs/".length()), q));
                    else if ((own = fieldPage(store, patron, path, method, q, form)) != null) {
                        if (own.redirect() != null) redirect(x, own.redirect()); else send(x, 200, page(store, patron, own.title(), own.body(), 0, null, own.wide()));
                    }
                    else send(x, 404, page(store, patron, "Page not found", "<p>There is no page at this address.</p>"));
                }
            }
        } catch (Declined declined) {
            send(x, 200, page(store, patron, "Declined by the model", "<p>" + esc(declined.statement()) + "</p>"));
        } catch (ProtocolError e) {
            int status = LibrarianDaemon.status(e.code);
            if ("forbidden".equals(e.code) && onDoor(x) && (patron.web() || patron.anonymous()) && cookie(x, COOKIE) == null) {
                // through the door, a visitor is shown the way in rather than a refusal: sign in, or ask
                send(x, 403, SignIn.loginPage(store, "This library is shared with named readers. Sign in with your name and password, or ask to be let in.", x));
                return;
            }
            String hint = "forbidden".equals(e.code) && patron.anonymous() ? " <a href=\"/login\">Sign in</a> to do this." : "";
            String c = cookie(x, COOKIE);
            if ("forbidden".equals(e.code) && c != null && !c.isBlank()) {
                // the browser sent a sign-in that is refused now (a token removed or denied since): forget it here, so the next load is a plain visit
                clearCookie(x);
                hint = " <b>This browser was signed in with a sign-in the library refuses now; that sign-in has been cleared.</b> <a href=\"" + esc(path) + "\">Load the page again</a> to visit as everyone does, or <a href=\"/login\">sign in</a> again.";
            }
            send(x, status, page(store, patron, "forbidden".equals(e.code) ? "Not allowed" : "not_found".equals(e.code) ? "Not found" : "Something went wrong", "<p class=\"err\">" + esc(e.getMessage()) + hint + "</p>"));
        }
    }

    // ---- pages ----

    private static String home(LibrarianDaemon d, LibraryStore store, LibraryProtocol p, Patrons.Patron patron) throws IOException {
        ObjectNode status = p.status(args(patron));
        JsonNode c = status.path("counts");
        StringBuilder b = new StringBuilder();
        b.append("<form class=\"big\" action=\"/ask\"><input name=\"q\" placeholder=\"Ask the library a question…\" autofocus> <button>Ask</button> <a class=\"k\" href=\"/search\">or search the library</a></form>");
        List<Suggestions.Entry> now = new ArrayList<>(); try { for (Suggestions.Entry e : Suggestions.open(store)) if (e.klass().equals("now") && now.size() < 2) now.add(e); } catch (IOException ignored) { }
        for (Suggestions.Entry e : now) b.append("<p class=\"notice\">✦ ").append(esc(e.text())).append(" — ").append(esc(e.reason())).append(". <a href=\"/suggest\">all suggestions</a></p>");
        b.append("<p class=\"k\">This library has ").append(c.path("finding").path("total").asInt()).append(" claims (")
         .append(c.path("finding").path("accepted").asInt()).append(" accepted, ").append(c.path("finding").path("disputed").asInt()).append(" disputed, ")
         .append(c.path("finding").path("draft").asInt()).append(" waiting for review), ").append(c.path("investigation").asInt()).append(" reports, ")
         .append(c.path("article").asInt()).append(" summaries and ").append(c.path("raw").asInt()).append(" saved pages, on ")
         .append(c.path("subject").asInt()).append(" subjects. Last change ").append(when(status.path("last_updated").asText("never"))).append(". ")
         .append(esc(d.describeWorkers())).append(".</p>");
        if (!WebAccess.signInRequired()) {
            b.append("<p class=\"notice\">").append(esc(WebAccess.OPEN_NOTICE)).append(" ").append(esc(WebAccess.OPEN_HOWTO).replace("run: ", "run <code>")).append("</code></p>");
        }
        // what is happening
        ObjectNode jobs = p.job(args(patron));
        if (jobs.path("active").size() > 0) {
            b.append("<h2>Running now</h2><ul>");
            for (JsonNode j : jobs.path("active")) b.append("<li>").append(jobLine(j)).append("</li>");
            b.append("</ul>");
        }
        // what changed
        List<Changes.Change> tail = Changes.tail(store, 8);
        if (!tail.isEmpty()) {
            b.append("<h2>What changed lately <a class=\"k\" href=\"/changes\">all changes</a></h2><ul class=\"tight\">");
            for (int i = tail.size() - 1; i >= 0; i--) b.append("<li>").append(changeLine(tail.get(i))).append("</li>");
            b.append("</ul>");
        }
        // what is open
        List<ObjectNode> open = p.frontierList(false);
        if (!open.isEmpty()) {
            b.append("<h2>Open questions <a class=\"k\" href=\"/questions\">all ").append(open.size()).append("</a></h2><ul class=\"tight\">");
            for (int i = open.size() - 1; i >= Math.max(0, open.size() - 6); i--) {
                ObjectNode o = open.get(i);
                b.append("<li>").append(withIdLinks(o.path("text").asText())).append(" <a class=\"k\" href=\"/research?q=").append(enc(o.path("text").asText())).append(o.hasNonNull("field") ? "&field=" + enc(o.path("field").asText()) : "").append("\">look into this</a></li>");
            }
            b.append("</ul>");
        }
        for (Profile f : Librarian.chatFields(store)) if (f.joinsOnlyWhenAsked()) b.append(f.homeSection(store, mayWrite(store, patron)));   // a field whose work the library holds
        b.append("<h2>Also</h2><p><a href=\"/map\">The map</a> shows how the people, places and things in this library connect. To open the library in Obsidian or SoloMD, run <code>researchzosho vault</code> and open the folder <code>")
         .append(esc(store.root().getFileName().toString())).append("-vault</code> next to the library.</p>");
        return page(store, patron, null, b.toString());
    }

    /** /suggest: what the library suggests the person do next, now first, each with its reason, its action and its cost; a mark teaches the library. */
    static String suggestPage(LibraryStore store, Patrons.Patron patron) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        List<Suggestions.Entry> open = Suggestions.refresh(store, null), all = Suggestions.all(store);
        boolean writer = mayWrite(store, patron);
        StringBuilder b = new StringBuilder("<h1>Suggested</h1><p class=\"k\">What the shelves' own events suggest you do next: a forecast due, a story behind the shelves, a question of yours with an answer waiting, a report that landed, a dossier gone stale. Now first, then the rest by how likely you are to take it up. A mark teaches the library.</p>");
        if (open.isEmpty()) b.append("<p>Nothing to suggest right now.</p>");
        else {
            b.append("<table><tr><th>Id</th><th>When</th><th>Suggestion</th><th>Why</th><th>Do</th><th>Costs</th>").append(writer ? "<th>Mark</th>" : "").append("</tr>");
            for (Suggestions.Entry e : open) {
                b.append("<tr><td>").append(esc(e.id())).append("</td><td><b>").append(esc(e.klass())).append("</b>").append(e.pTake() == null ? "" : " <span class=\"k\">" + Math.round(e.pTake() * 100) + "%</span>").append("</td><td>").append(withIdLinks(esc(e.text()))).append("</td><td class=\"k\">").append(esc(e.reason()))
                 .append("</td><td><code>").append(esc(e.action())).append("</code></td><td class=\"k\">").append(esc(e.cost())).append("</td>");
                if (writer) b.append("<td><form method=\"post\" action=\"/suggest\" style=\"display:inline\"><input type=\"hidden\" name=\"id\" value=\"").append(esc(e.id())).append("\"><button name=\"outcome\" value=\"taken\">taken</button> <button name=\"outcome\" value=\"later\">later</button> <button name=\"outcome\" value=\"dismissed\">dismiss</button></form></td>");
                b.append("</tr>");
            }
            b.append("</table>");
        }
        Double brier = Suggestions.brier(all);
        if (brier != null) b.append("<p class=\"k\">Brier score over your marks: ").append(String.format(Locale.ROOT, "%.3f", brier)).append(" (0 is perfect, 0.25 is coin-tossing).</p>");
        b.append("<p class=\"k\">At the command line: researchzosho suggest · in the chat: /suggest · the nightly housekeeping classes new suggestions with the model's typed judge; this page uses the rules.</p>");
        return page(store, patron, "Suggested", b.toString());
    }

    private static String search(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        String query = q.getOrDefault("q", "").strip();
        String subject = q.getOrDefault("subject", "").strip();
        StringBuilder b = new StringBuilder();
        b.append("<form class=\"big\" action=\"/search\"><input name=\"q\" value=\"").append(esc(query)).append("\" placeholder=\"Words, a title, a name…\">");
        if (!subject.isEmpty()) b.append("<input type=\"hidden\" name=\"subject\" value=\"").append(esc(subject)).append("\">");
        b.append(" <button>Search</button></form>");
        if (!subject.isEmpty()) b.append("<p class=\"k\">in the subject <b>").append(esc(subject)).append("</b> <a href=\"/search?q=").append(enc(query)).append("\">(everything)</a></p>");
        if (query.isEmpty() && subject.isEmpty()) return page(store, patron, "Search", b.toString());
        ObjectNode a = args(patron);
        a.put("query", query.isEmpty() ? subject.replace("--", " ") : query);
        a.put("k", 25);
        if (!subject.isEmpty()) a.put("subject", subject);
        if (q.containsKey("cursor")) a.put("cursor", q.get("cursor"));
        ObjectNode r = p.search(a);
        if (r.path("hits").size() == 0) b.append("<p>Nothing in the library matches these words. <a href=\"/research?q=").append(enc(query)).append("\">Research this question</a>.</p>");
        else {
            b.append("<ol class=\"hits\">");
            for (JsonNode h : r.path("hits")) b.append("<li>").append(hitLine(h)).append("</li>");
            b.append("</ol>");
            if (r.hasNonNull("next_cursor")) b.append("<p><a href=\"/search?q=").append(enc(query)).append("&subject=").append(enc(subject)).append("&cursor=").append(enc(r.get("next_cursor").asText())).append("\">More</a></p>");
        }
        return page(store, patron, "Search", b.toString());
    }

    private static String ask(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        String question = q.getOrDefault("q", "").strip();
        StringBuilder b = new StringBuilder();
        b.append("<form class=\"big\" action=\"/ask\"><input name=\"q\" value=\"").append(esc(question)).append("\" placeholder=\"Ask the library a question…\"> <button>Ask</button></form>");
        if (question.isEmpty()) return page(store, patron, "Ask", b.toString());
        ObjectNode a = args(patron); a.put("question", question); a.put("k", 8); a.put("peers", "none");
        ObjectNode r = p.ask(a);
        if (r.path("holds_nothing").asBoolean()) {
            b.append("<p>The library has nothing on this yet.");
            if (r.path("filed_as_demand").asBoolean()) b.append(" The question was added to the open questions.");
            b.append(" <a href=\"/research?q=").append(enc(question)).append("\">Research this question</a>.</p>");
        } else {
            b.append("<p class=\"k\">What the library has on this, best match first. Each one lists its sources; open it to see them.</p>");
            for (JsonNode e : r.path("entries")) b.append(entryCard(e));
        }
        if (r.path("open_threads").size() > 0) {
            b.append("<h2>Open questions about this</h2><ul class=\"tight\">");
            for (JsonNode t : r.path("open_threads")) b.append("<li>").append(esc(t.path("text").asText())).append(" <span class=\"k\">").append(esc(t.path("date").asText())).append("</span></li>");
            b.append("</ul>");
        }
        return page(store, patron, "Ask", b.toString());
    }

    static String entry(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, String id) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        if (!LibraryStore.safeName(id)) throw ProtocolError.notFound("entry " + id);
        ObjectNode e = p.entry(id, p.kindOf(id), true);
        if (e == null) throw ProtocolError.notFound("entry " + id);
        String kind = e.path("kind").asText();
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\">").append(esc(kind)).append(" · ").append(badge(e.path("state").asText()));
        if (!"raw".equals(kind)) b.append(" · confidence ").append(esc(e.path("confidence").asText())).append(" · ").append(esc(e.path("claim_type").asText()));
        b.append(" · by ").append(esc(e.path("writer").asText())).append(" · ").append(when(e.path("recorded_at").asText()));
        if (e.hasNonNull("valid_as_of") && !e.path("valid_as_of").asText().isEmpty()) b.append(" · valid as of ").append(esc(e.path("valid_as_of").asText()));
        if (e.hasNonNull("review_by")) b.append(" · review due ").append(esc(e.path("review_by").asText()));
        b.append("</p>");
        if ("investigation".equals(kind)) {   // a report on a person or an organisation leads to their dossier, the whole of what the shelves hold
            Investigation inv = store.investigation(id);
            String subject = inv == null ? null : inv.title().startsWith("The story of ") ? inv.title().substring("The story of ".length()).strip() : TimelineClaims.subjectOf(store, inv);
            if (subject != null && !subject.isBlank()) b.append("<p class=\"k\">Dossier: <a href=\"/dossier?subject=").append(enc(subject)).append("\">").append(esc(subject)).append("</a> — everything the library holds on them, as one page.</p>");
        }
        if (!"raw".equals(kind)) b.append(ladder(id, Explain.Rung.written)).append(downloads(id, null));
        if (!"raw".equals(kind) && mayWrite(store, patron)) b.append(removeForm(id, kind));
        if ("investigation".equals(kind) && mayWrite(store, patron)) b.append(goDeeper(e));
        if (e.path("subjects").size() > 0) {
            b.append("<p class=\"k\">Subjects: ");
            for (JsonNode s : e.path("subjects")) b.append("<a href=\"/search?subject=").append(enc(s.asText())).append("\">").append(esc(s.asText())).append("</a> ");
            b.append("</p>");
        }
        if (e.hasNonNull("triple")) {
            JsonNode t = e.get("triple");
            b.append("<p class=\"triple\"><a href=\"/map?focus=").append(enc(t.path("subject").asText())).append("\">").append(esc(t.path("subject").asText())).append("</a> — ")
             .append(esc(t.path("predicate").asText())).append(" — <a href=\"/map?focus=").append(enc(t.path("object").asText())).append("\">").append(esc(t.path("object").asText())).append("</a></p>");
        }
        if ("raw".equals(kind)) {
            b.append("<p>Saved from ").append(locatorLink(e.path("locator").asText())).append(" (").append(e.path("chars").asInt()).append(" characters). ")
             .append("<a href=\"/read?locator=").append(enc(e.path("locator").asText())).append("\">Read the whole saved page</a>.</p>");
            b.append("<pre class=\"raw\">").append(esc(e.path("body").asText())).append("</pre>");
        } else {
            b.append("<div class=\"body\">").append(withRefs(md(e.path("body").asText()), e.path("body").asText())).append("</div>");
        }
        if (e.path("sources").size() > 0 && !"raw".equals(kind)) {
            int n = e.path("sources").size(), ind = e.path("independent_sources").asInt(n);
            b.append("<h2>Sources</h2>");
            b.append("<p class=\"k\">").append(n).append(n == 1 ? " source" : " sources").append(ind < n ? ", " + ind + " independent (copies of one text count once)" : "")
             .append(ind <= 1 ? ". <span class=\"mark\">one source</span> — nothing else backs this up yet" : "").append("</p><ul>");
            for (JsonNode s : e.path("sources")) {
                b.append("<li>").append(locatorLink(s.path("locator").asText()));
                if (s.hasNonNull("edition") && !s.path("edition").asText().isEmpty()) b.append(" <span class=\"k\">").append(esc(s.path("edition").asText())).append("</span>");
                StringBuilder facts = new StringBuilder();
                if (s.hasNonNull("published")) {
                    facts.append("published ").append(esc(s.path("published").asText()));
                    facts.append(esc(Researcher.ageNote(s.path("published").asText())));
                }
                String tier = s.path("tier").asText("");
                if (!tier.isEmpty() && !tier.equals("web")) { if (facts.length() > 0) facts.append(" · "); facts.append(esc(tier)); }
                if (s.hasNonNull("rule")) { if (facts.length() > 0) facts.append(" · "); facts.append("trust".equals(s.path("rule").asText()) ? "<span class=\"mark\">trusted by you</span>" : "<span class=\"mark bad\">on your refused list</span>"); }
                if (facts.length() > 0) b.append(" <span class=\"k\">").append(facts).append("</span>");
                if (!s.path("why").asText().isEmpty()) b.append("<br><span class=\"k\">").append(esc(s.path("why").asText())).append("</span>");
                b.append("</li>");
            }
            b.append("</ul>");
        }
        if (e.path("findings").size() > 0) {
            b.append("<h2>Claims that came out of it</h2><ul class=\"tight\">");
            for (JsonNode f : e.path("findings")) b.append("<li>").append(idLink(f.asText())).append("</li>");
            b.append("</ul>");
        }
        if (e.path("notes").size() > 0) {
            b.append("<h2>Notes on this claim</h2><ul>");
            for (JsonNode n : e.path("notes")) b.append("<li><b>").append(esc(n.path("kind").asText())).append("</b> ").append(esc(n.path("text").asText())).append(" <span class=\"k\">").append(esc(n.path("by").asText())).append(", ").append(esc(n.path("date").asText())).append("</span></li>");
            b.append("</ul>");
        }
        if (e.hasNonNull("last_checked")) {
            JsonNode lc = e.get("last_checked");
            String v = lc.path("verdict").asText("");
            b.append("<p class=\"k\">Last read against its source ").append(when(lc.path("at").asText())).append(": ")
             .append("supported".equals(v) ? "the source still says it" : "unsupported".equals(v) ? "<span class=\"mark bad\">the source does not say it</span>" : "no-capture".equals(v) ? "no saved copy of the source to read" : "could not tell from the source").append("</p>");
        }
        if (e.hasNonNull("review")) {
            JsonNode rv = e.get("review");
            b.append("<p class=\"k\">Reviewed: ").append(esc(rv.path("decision").asText())).append(" by ").append(esc(rv.path("reviewer").asText())).append(" on ").append(when(rv.path("at").asText()))
             .append(rv.path("stale").asBoolean() ? " (it has changed since then)" : "").append("</p>");
        }
        StringBuilder links = new StringBuilder();
        for (JsonNode s : e.path("supersedes")) links.append("<li>replaces ").append(idLink(s.asText())).append("</li>");
        for (JsonNode s : e.path("superseded_by")) links.append("<li>replaced by ").append(idLink(s.asText())).append("</li>");
        for (JsonNode s : e.path("related")) {
            links.append("<li>related ").append(idLink(s.path("id").asText()));
            if (s.path("shared").size() > 0) { links.append(" <span class=\"k\">(shares "); for (JsonNode sh : s.path("shared")) links.append(esc(sh.asText())).append(' '); links.append(")</span>"); }
            links.append("</li>");
        }
        if (links.length() > 0) b.append("<h2>Connected</h2><ul class=\"tight\">").append(links).append("</ul>");
        return page(store, patron, e.path("title").asText(id), b.toString());
    }

    private static String read(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        String locator = q.getOrDefault("locator", "").strip();
        if (locator.isEmpty()) return page(store, patron, "Read", "<p>Say which saved page to read.</p>");
        ObjectNode a = args(patron); a.put("locator", locator); a.put("max_chars", 400_000);
        ObjectNode r = p.read(a);
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\">").append(locatorLink(r.path("locator").asText())).append(" · saved ").append(when(r.path("captured_at").asText())).append(" · ").append(r.path("chars").asInt()).append(" characters")
         .append(r.path("truncated").asBoolean() ? " (shown in part)" : "").append(" · <a href=\"/entry/").append(enc(r.path("raw_id").asText())).append("\">the entry</a></p>");
        b.append("<pre class=\"raw\">").append(esc(r.path("text").asText())).append("</pre>");
        return page(store, patron, r.path("title").asText(locator), b.toString());
    }

    private static String subjects(LibraryStore store, LibraryProtocol p, Patrons.Patron patron) throws IOException {
        ObjectNode r = p.subjects(args(patron));
        Map<String, List<JsonNode>> byFacet = new LinkedHashMap<>();
        for (JsonNode s : r.path("subjects")) if (s.hasNonNull("broader") || !s.path("narrower").isEmpty() == false) byFacet.computeIfAbsent(s.path("facet").asText(), k -> new ArrayList<>()).add(s);
        StringBuilder b = new StringBuilder();
        if (byFacet.isEmpty()) b.append("<p>No subjects yet. They appear as claims are added.</p>");
        for (var f : byFacet.entrySet()) {
            b.append("<h2>").append(esc(f.getKey())).append("</h2><ul class=\"tight\">");
            for (JsonNode s : f.getValue()) {
                if (s.path("narrower").size() > 0 && !s.hasNonNull("broader")) continue;   // the facet row itself
                b.append("<li><a href=\"/search?subject=").append(enc(s.path("id").asText())).append("\">").append(esc(s.path("label").asText())).append("</a> <span class=\"k\">").append(esc(s.path("id").asText())).append(" · ").append(s.path("count").asInt()).append("</span></li>");
            }
            b.append("</ul>");
        }
        return page(store, patron, "Subjects", b.toString());
    }

    static String changes(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        List<Changes.Change> tail = Changes.tail(store, 200);
        StringBuilder b = new StringBuilder("<p class=\"k\">What changed in the library, newest first.</p>");
        if (tail.isEmpty()) b.append("<p>Nothing has changed yet.</p>");
        else { b.append("<ul class=\"tight\">"); for (int i = tail.size() - 1; i >= 0; i--) b.append("<li>").append(changeLine(tail.get(i))).append("</li>"); b.append("</ul>"); }
        return page(store, patron, "Changes", b.toString());
    }

    /** /dossier?subject=…: the target package on a person or an organisation, {@link DossierPage}, from what the shelves hold. */
    /** The dossier read in another language: the translation the library keeps when it is current; else a job that makes it, and a word to come back. */
    static String dossierIn(LibrarianDaemon d, LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        String subject = q.getOrDefault("subject", "").strip(), lang = Translate.code(q.getOrDefault("in", ""));
        String name = Translate.LANGUAGES.getOrDefault(lang, lang);
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\"><a href=\"/dossier?subject=").append(enc(subject)).append("\">").append(esc(subject)).append("</a>, read in ").append(esc(name))
                .append(": the same page, each piece of prose translated by the model. The cites, the claim ids and the dates stand as they are, and the claims keep their own language on the shelves.</p>");
        Translate.Translated t = Translate.current(store, subject, lang);
        if (t != null) return page(store, patron, "Dossier: " + subject + " (" + name + ")", DossierPage.render(store, subject, t.lookup(), lang));   // the same page, its pieces translated
        String running = d == null ? null : Translate.pending(d.jobs(), subject, lang);
        if (running != null) b.append("<p>Being translated into ").append(esc(name)).append(" (").append(esc(running)).append("): a few minutes on the local model. Come back to this page.</p>");
        else if (d != null && mayWrite(store, patron)) {
            ObjectNode a = new ObjectMapper().createObjectNode(); a.put("subject", subject); a.put("lang", lang);
            String id = d.jobs().submit("translate", patron.did(), a);
            b.append("<p>Translating into ").append(esc(name)).append(" now (").append(esc(id)).append("): a few minutes on the local model, section by section. Come back to this page.</p>");
        } else b.append("<p>No translation into ").append(esc(name)).append(" yet. A writer's visit to this page starts one; the command is researchzosho dossier \"").append(esc(subject)).append("\" --in ").append(esc(lang)).append(".</p>");
        return page(store, patron, "Dossier: " + subject + " (" + name + ")", b.toString());
    }

    static String dossierPage(LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        String subject = q.getOrDefault("subject", "").strip();
        return page(store, patron, subject.isEmpty() ? "Dossier" : "Dossier: " + subject, DossierPage.render(store, subject));
    }

    static String questions(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        StringBuilder b = new StringBuilder();
        // the searches the housekeeping keeps
        b.append("<h2>Searches kept up to date</h2><p class=\"k\">Nightly maintenance runs each one again every so many days. Anything new is listed on the Changes page.</p>");
        List<Serials.Shelf> kept = Serials.shelves(store);
        if (kept.isEmpty()) b.append("<p>None yet.</p>");
        else {
            b.append("<ul class=\"tight\">");
            for (Serials.Shelf s : kept) b.append("<li>").append(s.parked() ? "<span class=\"badge\">parked</span> " : "").append("<b>").append(esc(s.slug())).append("</b>: ").append(esc(s.query())).append(" <span class=\"k\">(last ").append(esc(s.lastChecked())).append(")</span> ")
                    .append("<form method=\"post\" action=\"/questions\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"every\"><input type=\"hidden\" name=\"name\" value=\"").append(esc(s.slug())).append("\">every <input name=\"every\" size=\"3\" value=\"").append(s.everyDays()).append("\"> days <button>Change</button></form> ")
                    .append("<form method=\"post\" action=\"/questions\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"").append(s.parked() ? "unpark-search" : "park-search").append("\"><input type=\"hidden\" name=\"name\" value=\"").append(esc(s.slug())).append("\"><button>").append(s.parked() ? "Resume" : "Park").append("</button></form> ")
                    .append("<form method=\"post\" action=\"/questions\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"unkeep\"><input type=\"hidden\" name=\"name\" value=\"").append(esc(s.slug())).append("\"><button>Stop this search</button></form></li>");
            b.append("</ul>");
        }
        b.append("<form method=\"post\" action=\"/questions\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"keep\">")
         .append("<label>Keep a search<br><input name=\"query\" size=\"60\" placeholder=\"what to search for, every so often\"></label>")
         .append("<label>Name <input name=\"name\" size=\"16\" placeholder=\"short-name\"></label> <label>Every <input name=\"every\" size=\"3\" value=\"7\"> days</label> <button>Keep it</button></form>");
        // the open questions: the queue, with the filters a person sorts a long list by; tick any number, then one button for all of them
        Map<String, String> f = new LinkedHashMap<>();
        for (String k : List.of("type", "show", "report", "fate", "who", "subject", "language", "q", "view", "similar")) if (q.containsKey(k) && !q.get(k).isBlank()) f.put(k, q.get(k).strip());
        f.putIfAbsent("show", "queued");
        String show = f.get("show");
        b.append("<h2>Open questions</h2><p class=\"k\">The nightly research takes its questions from this list: ").append(Crews.explorerBudget()).append(" research run(s) a night, and related questions share a run. The Runs page shows tonight's. ")
         .append("Questions left over from a report start out parked: they wait here until you put them in the queue. Tick any number, then research them now, move them, park them, or drop them.</p>");
        List<ObjectNode> open = p.frontierList(true);
        int dups = Frontier.duplicates(store).size();
        if (dups > 0) b.append("<form method=\"post\" action=\"/questions\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"tidy\">").append(dups).append(" of these are second copies of the same question. <button>Remove the copies</button></form>");
        // the facets, counted over the questions that pass the OTHER filters, so every count says what a click would show
        b.append("<form method=\"get\" action=\"/questions\" class=\"inline\">");
        for (var e : f.entrySet()) if (!e.getKey().equals("q")) b.append("<input type=\"hidden\" name=\"").append(esc(e.getKey())).append("\" value=\"").append(esc(e.getValue())).append("\">");
        b.append("<input name=\"q\" size=\"32\" value=\"").append(esc(f.getOrDefault("q", ""))).append("\" placeholder=\"words in the question\"> <button>Find</button>")
         .append(f.size() > 1 || !show.equals("queued") ? " <a class=\"k\" href=\"/questions\">clear the filters</a>" : "").append("</form>");
        b.append(facetRow(open, f, "show", "Show", List.of(new String[]{"queued", "queued"}, new String[]{"parked", "parked"}, new String[]{"all", "all"}), false));
        b.append(facetRow(open, f, "type", "Type", List.of(new String[]{"report", "from reports"}, new String[]{"asked", "asked, unanswered"}, new String[]{"person", "added by you"}, new String[]{"dispute", "from disputes"}, new String[]{"check", "source checks"}), true));
        List<String[]> reports = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ObjectNode o : open) { String id = o.path("report").asText(""); if (!id.isEmpty() && seen.add(id)) reports.add(new String[]{id, shortReport(id, o.path("report_title").asText(""))}); }
        if (reports.size() > 1) b.append(facetRow(open, f, "report", "Left by", reports, true));
        List<String[]> fates = new ArrayList<>();
        for (String[] x : new String[][]{{"kept", "a report you kept"}, {"waiting", "a report still in the inbox"}, {"disputed", "a disputed report"}, {"retired", "a retired report"}, {"none", "a report with no claims"}}) for (ObjectNode o : open) if (o.path("report_fate").asText("").equals(x[0])) { fates.add(x); break; }
        if (fates.size() > 1) b.append(facetRow(open, f, "fate", "What became of it", fates, true));
        Map<String, Integer> whos = new TreeMap<>();
        for (ObjectNode o : open) if (o.hasNonNull("perspective")) whos.merge(o.path("perspective").asText(), 1, Integer::sum);
        if (whos.size() > 1) { List<String[]> ws = new ArrayList<>(); for (String w : whos.keySet()) ws.add(new String[]{w, w}); b.append(facetRow(open, f, "who", "Asked from", ws, true)); }
        Map<String, Integer> subjects = new TreeMap<>();
        for (ObjectNode o : open) for (var x : o.path("subjects")) subjects.merge(x.asText(), 1, Integer::sum);
        if (!subjects.isEmpty()) { List<String[]> ss = new ArrayList<>(); for (String x : subjects.keySet()) ss.add(new String[]{x, x}); b.append(facetRow(open, f, "subject", "Subject", ss, true)); }
        Map<String, Integer> langs = new TreeMap<>();
        for (ObjectNode o : open) langs.merge(o.path("language").asText("english"), 1, Integer::sum);
        if (langs.size() > 1) { List<String[]> ls = new ArrayList<>(); for (String x : langs.keySet()) ls.add(new String[]{x, x}); b.append(facetRow(open, f, "language", "Language", ls, true)); }
        String view = f.getOrDefault("view", reports.isEmpty() ? "list" : "report");
        String similar = f.getOrDefault("similar", "folded");
        b.append("<p class=\"k\">View: ").append(view.equals("report") ? "<b>by report</b>" : "<a href=\"" + href(f, "view", "report") + "\">by report</a>").append(" · ")
         .append(view.equals("list") ? "<b>one list</b>" : "<a href=\"" + href(f, "view", "list") + "\">one list</a>")
         .append(" &nbsp; Questions that read alike: ").append(similar.equals("folded") ? "<b>folded</b>" : "<a href=\"" + href(f, "similar", "folded") + "\">folded</a>").append(" · ")
         .append(similar.equals("shown") ? "<b>shown</b>" : "<a href=\"" + href(f, "similar", "shown") + "\">shown</a>").append("</p>");
        if (f.getOrDefault("type", "").equals("check")) b.append("<p class=\"k\">Source checks are not research questions. A re-read found that the source does not support the claim. The claim is in the <a href=\"/inbox\">Inbox</a>. Decide it there. The nightly research never takes these.</p>");
        List<ObjectNode> rows = new ArrayList<>();
        for (ObjectNode o : open) if (LibraryProtocol.matches(o, f)) rows.add(o);
        if (rows.isEmpty()) b.append("<p>No questions match these filters.</p>");
        else {
            b.append("<form method=\"post\" action=\"/questions\" class=\"pick\"><input type=\"hidden\" name=\"op\" value=\"selected\">")
             .append("<div class=\"bar\"><label><input type=\"checkbox\" onclick=\"for(const c of this.form.querySelectorAll('input[name^=q]'))c.checked=this.checked\"> all</label> ")
             .append("<button name=\"do\" value=\"run\">Research now</button> <button name=\"do\" value=\"next\">Run next</button> <button name=\"do\" value=\"later\">Run later</button> ");
            if (!show.equals("queued")) b.append("<button name=\"do\" value=\"unpark\">Put back in the queue</button> ");
            if (!show.equals("parked")) b.append("<button name=\"do\" value=\"park\">Park</button> ");
            b.append("<button name=\"do\" value=\"drop\">Drop</button></div>");
            // grouped by the report that left them (a group's box ticks the group), or one list; alike questions fold under their first
            Map<String, List<ObjectNode>> groups = new LinkedHashMap<>();
            for (ObjectNode o : rows) groups.computeIfAbsent(view.equals("report") ? o.path("report").asText("") : "", k -> new ArrayList<>()).add(o);
            int[] n = {0};
            for (var e : groups.entrySet()) {
                String id = e.getKey();
                if (view.equals("report")) {
                    ObjectNode first = e.getValue().get(0);
                    b.append("<h3 class=\"group\"><label><input type=\"checkbox\" onclick=\"for(const c of this.closest('h3').nextElementSibling.querySelectorAll('input[name^=q]'))c.checked=this.checked\"> ");
                    if (id.isEmpty()) b.append("Not from a report");
                    else b.append("Left by ").append(idLink(id, shortReport(id, first.path("report_title").asText("")))).append(" ").append(fateBadge(first.path("report_fate").asText("")));
                    b.append(" <span class=\"k\">").append(e.getValue().size()).append("</span></label>");
                    if (!id.isEmpty() && first.path("report_fate").asText("").equals("waiting")) b.append(" <a class=\"k\" href=\"/inbox?report=").append(enc(id)).append("\">decide its claims</a>");
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
                        b.append("<details><summary class=\"k\">").append(under.size()).append(under.size() == 1 ? " question reads alike" : " questions read alike").append("</summary><ul>");
                        for (ObjectNode x : under) b.append("<li>").append(questionRow(x, n, show)).append("</li>");
                        b.append("</ul></details>");
                    }
                    b.append("</li>");
                }
                b.append("</ul>");
            }
            b.append("</form>");
        }
        b.append("<form method=\"post\" action=\"/questions\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"budget\">The nightly research takes <input name=\"standing\" size=\"2\" value=\"").append(Crews.explorerPerNight()).append("\"> run(s) a night; tonight only: <input name=\"tonight\" size=\"2\" value=\"").append(Crews.explorerTonight() > 0 ? String.valueOf(Crews.explorerTonight()) : "").append("\" placeholder=\"same\"> <button>Set</button></form>");
        b.append("<form method=\"post\" action=\"/questions\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"add\">")
         .append("<label>Add an open question<br><input name=\"question\" size=\"70\" placeholder=\"a question for the nightly research to take on a coming night\"></label> <button>Add it</button></form>");
        return page(store, patron, "Open questions", b.toString());
    }

    /** One open question as a list row: its box, tonight's mark, its place, the text, and what a person filters on. */
    private static String questionRow(ObjectNode o, int[] n, String show) {
        String text = o.path("text").asText();
        String t = o.path("type").asText();
        StringBuilder b = new StringBuilder("<label><input type=\"checkbox\" name=\"q").append(n[0]++).append("\" value=\"").append(esc(text)).append("\"> ");
        if (o.path("tonight").asBoolean()) b.append("<b class=\"badge accepted\">tonight</b> ");
        if (o.path("parked").asBoolean() && !show.equals("parked")) b.append("<span class=\"badge\">parked</span> ");
        b.append("<span class=\"k\">").append(o.path("position").asInt()).append(".</span> ").append(withIdLinks(Frontier.strip(text)));
        if (o.hasNonNull("parked_why")) b.append(" <span class=\"k\">— waits: ").append(esc(o.path("parked_why").asText())).append("</span>");
        b.append(" <span class=\"k\">").append(esc(t)).append(t.equals("asked") ? " ×" + o.path("asked").asInt() : "").append(o.hasNonNull("field") ? " · " + esc(o.path("field").asText()) + " mode" : "").append(" · ").append(esc(o.path("date").asText()));
        if (!o.path("language").asText("english").equals("english")) b.append(" · ").append(esc(o.path("language").asText()));
        for (var x : o.path("subjects")) b.append(" · ").append(esc(x.asText()));
        b.append("</span>");
        if (o.hasNonNull("answered")) b.append(" <span class=\"k\">— maybe answered already: ").append(idLink(o.path("answered").path("id").asText(), o.path("answered").path("title").asText())).append("</span>");
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
        if (withAll) b.append(chosen.isEmpty() ? "<b>all</b>" : "<a href=\"" + href(base, f, key, "") + "\">all</a>");
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
            case "kept" -> "<span class=\"badge accepted\">kept</span>";
            case "waiting" -> "<span class=\"badge\">in the inbox</span>";
            case "disputed" -> "<span class=\"badge disputed\">disputed</span>";
            case "retired" -> "<span class=\"badge\">retired</span>";
            case "none" -> "<span class=\"badge\">no claims</span>";
            default -> "";
        };
    }

    /** The forms on the questions page. Returns where to go next. */
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
        if (!recallHits.isEmpty()) b.append("<p class=\"notice\">").append(recallHits.size()).append(" recall notice(s) may concern something you own. <a href=\"/owned\">See them on the Owned page</a>, where each can be put away.</p>");
        if (mayWrite(store, patron)) {
            List<AccessRequests.Request> asking = new ArrayList<>();
            for (AccessRequests.Request r : AccessRequests.list(store)) if (r.state().equals("pending")) asking.add(r);
            if (!asking.isEmpty()) {
                b.append("<h2>Asking to be let in</h2><table><tr><th>who</th><th>note</th><th>asked</th><th></th></tr>\n");
                for (AccessRequests.Request r : asking) b.append("<tr><td>").append(esc(r.name().isEmpty() ? r.did() : r.name())).append(r.did().startsWith("did:mail:") ? " <small>" + esc(r.did().substring(9)) + "</small>" : "").append("</td><td>").append(esc(r.note())).append("</td><td>").append(esc(r.date())).append("</td><td>")
                 .append("<form class=\"inline\" method=\"post\" action=\"/inbox\"><input type=\"hidden\" name=\"request\" value=\"").append(esc(r.id())).append("\"><button name=\"do\" value=\"let-in-read\">Let in to read</button> <button name=\"do\" value=\"let-in-write\">Let in to write</button> <input name=\"why\" size=\"18\" placeholder=\"why not\"> <button name=\"do\" value=\"refuse\" class=\"quiet\">Refuse</button></form></td></tr>\n");
                b.append("</table>\n");
            }
        }
        b.append("<p class=\"k\">Claims waiting for your decision: new claims, and accepted claims whose review is out of date. Accept puts a claim into every answer from now on. Dispute records your reason. Retire stops using the claim but keeps the record. Tick any number, then choose.</p>");
        if (all.isEmpty()) { b.append("<p>No claims are waiting. New claims appear here after a research run.</p>"); return page(store, patron, "Inbox", b.toString()); }
        b.append("<form method=\"get\" action=\"/inbox\" class=\"inline\">");
        for (var e : f.entrySet()) if (!e.getKey().equals("q")) b.append("<input type=\"hidden\" name=\"").append(esc(e.getKey())).append("\" value=\"").append(esc(e.getValue())).append("\">");
        b.append("<input name=\"q\" size=\"32\" value=\"").append(esc(f.getOrDefault("q", ""))).append("\" placeholder=\"words in the claim\"> <button>Find</button>")
         .append(f.isEmpty() ? "" : " <a class=\"k\" href=\"/inbox\">clear the filters</a>").append("</form>");
        BiPredicate<ObjectNode, Map<String, String>> m = LibraryProtocol::inboxMatches;
        List<String[]> reports = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (ObjectNode o : all) { String id = o.path("report").asText(""); if (!id.isEmpty() && seen.add(id)) reports.add(new String[]{id, shortReport(id, o.path("report_title").asText(""))}); }
        if (reports.size() > 1 || (reports.size() == 1 && all.size() > reports.size())) b.append(facetRow("/inbox", m, all, f, "report", "From the report", reports, true));
        b.append(facetRow("/inbox", m, all, f, "state", "Waiting because", List.of(new String[]{"draft", "new claim"}, new String[]{"stale", "review is out of date"}), true));
        b.append(facetRow("/inbox", m, all, f, "kind", "Kind", List.of(new String[]{"extraction", "extraction"}, new String[]{"synthesis", "synthesis"}, new String[]{"interpretation", "interpretation"}, new String[]{"speculation", "speculation"}), true));
        List<String[]> tiers = new ArrayList<>(); for (SourceTier t : SourceTier.values()) for (ObjectNode o : all) if (o.path("tier").asText("").equals(t.name())) { tiers.add(new String[]{t.name(), t.name()}); break; }
        if (tiers.size() > 1) b.append(facetRow("/inbox", m, all, f, "tier", "Strongest source", tiers, true));
        b.append(facetRow("/inbox", m, all, f, "confidence", "Confidence", List.of(new String[]{"high", "high"}, new String[]{"medium", "medium"}, new String[]{"low", "low"}), true));
        Map<String, Integer> writers = new TreeMap<>(); for (ObjectNode o : all) writers.merge(o.path("writer").asText(""), 1, Integer::sum);
        if (writers.size() > 1) { List<String[]> ws = new ArrayList<>(); for (String w : writers.keySet()) ws.add(new String[]{w, w}); b.append(facetRow("/inbox", m, all, f, "writer", "Written by", ws, true)); }
        Map<String, Integer> subjects = new TreeMap<>(); for (ObjectNode o : all) for (var x : o.path("subjects")) subjects.merge(x.asText(), 1, Integer::sum);
        if (!subjects.isEmpty()) { List<String[]> ss = new ArrayList<>(); for (String x : subjects.keySet()) ss.add(new String[]{x, x}); b.append(facetRow("/inbox", m, all, f, "subject", "Subject", ss, true)); }
        Map<String, Integer> langs = new TreeMap<>(); for (ObjectNode o : all) langs.merge(o.path("language").asText("english"), 1, Integer::sum);
        if (langs.size() > 1) { List<String[]> ls = new ArrayList<>(); for (String x : langs.keySet()) ls.add(new String[]{x, x}); b.append(facetRow("/inbox", m, all, f, "language", "Language", ls, true)); }
        String view = f.getOrDefault("view", reports.isEmpty() ? "list" : "report");
        b.append("<p class=\"k\">View: ").append(view.equals("report") ? "<b>by report</b>" : "<a href=\"" + href("/inbox", f, "view", "report") + "\">by report</a>").append(" · ")
         .append(view.equals("list") ? "<b>one list</b>" : "<a href=\"" + href("/inbox", f, "view", "list") + "\">one list</a>").append("</p>");
        List<ObjectNode> rows = new ArrayList<>();
        for (ObjectNode o : all) if (m.test(o, f)) rows.add(o);
        if (rows.isEmpty()) { b.append("<p>No claims match these filters.</p>"); return page(store, patron, "Inbox", b.toString()); }
        b.append("<form method=\"post\" action=\"/inbox\" class=\"pick\">")
         .append("<div class=\"bar\"><label><input type=\"checkbox\" onclick=\"for(const c of this.form.querySelectorAll('input[name^=f]'))c.checked=this.checked\"> all</label> ")
         .append("<button name=\"do\" value=\"accept\">Accept the ticked ones</button> <button name=\"do\" value=\"retire\">Retire the ticked ones</button> ")
         .append("<span>Dispute the ticked ones: <input name=\"why\" size=\"36\" placeholder=\"why\"> <button name=\"do\" value=\"dispute\">Dispute</button></span></div>");
        Map<String, List<ObjectNode>> groups = new LinkedHashMap<>();
        for (ObjectNode o : rows) groups.computeIfAbsent(view.equals("report") ? o.path("report").asText("") : "", k -> new ArrayList<>()).add(o);
        int n = 0;
        for (var e : groups.entrySet()) {
            String id = e.getKey();
            if (view.equals("report")) {
                b.append("<h3 class=\"group\"><label><input type=\"checkbox\" onclick=\"for(const c of this.closest('h3').nextElementSibling.querySelectorAll('input[name^=f]'))c.checked=this.checked\"> ");
                if (id.isEmpty()) b.append("Not from a report");
                else b.append("From ").append(idLink(id, shortReport(id, e.getValue().get(0).path("report_title").asText(""))));
                b.append(" <span class=\"k\">").append(e.getValue().size()).append("</span></label>");
                if (!id.isEmpty()) b.append(" <a class=\"k\" href=\"/questions?show=all&amp;report=").append(enc(id)).append("\">its open questions</a>");
                b.append("</h3>");
            }
            b.append("<ul>");
            for (ObjectNode o : e.getValue()) {
                b.append("<li><label><input type=\"checkbox\" name=\"f").append(n++).append("\" value=\"").append(esc(o.path("id").asText())).append("\"> ")
                 .append("<a href=\"/entry/").append(enc(o.path("id").asText())).append("\">").append(esc(o.path("title").asText())).append("</a> <span class=\"k\">")
                 .append(o.path("stale").asBoolean() ? "review out of date" : esc(o.path("state").asText())).append(" · ").append(esc(o.path("kind").asText())).append(" · ").append(esc(o.path("tier").asText()))
                 .append(" · ").append(esc(o.path("confidence").asText())).append(" · ").append(esc(o.path("date").asText()));
                for (var x : o.path("subjects")) b.append(" · ").append(esc(x.asText()));
                b.append("</span></label></li>");
            }
            b.append("</ul>");
        }
        b.append("</form>");
        return page(store, patron, "Inbox", b.toString());
    }

    /** Whether the patron may write, without throwing: the form is shown only to those who could use it. */
    static boolean mayWrite(LibraryStore store, Patrons.Patron patron) { return Patrons.mayWrite(store, patron); }

    /** The family's pages that take its word (Who is who, Decisions): for the people who may write, and a plain sentence for anyone else. */
    static void writersOnly(LibraryStore store, Patrons.Patron patron) {
        if (!mayWrite(store, patron)) throw ProtocolError.forbidden("This page is for the people who may change the library: its owner, and anyone the owner lets write to it. "
                + "What is answered here changes the library and its research. The family tree and the rest of the library are open to you.");
    }

    /** The remove form on an entry: for a report the three choices, for a claim the one; the plan is shown before anything goes. */
    static String removeForm(String id, String kind) {
        StringBuilder b = new StringBuilder("<details class=\"remove\"><summary class=\"k\">Delete…</summary><form method=\"post\" action=\"/remove\"><input type=\"hidden\" name=\"id\" value=\"").append(esc(id)).append("\">");
        if ("investigation".equals(kind)) {
            b.append("<label><input type=\"radio\" name=\"what\" value=\"all\" checked> Delete the report and its claims</label><br>")
             .append("<label><input type=\"radio\" name=\"what\" value=\"report\"> Delete only the report. Keep its claims.</label><br>")
             .append("<label><input type=\"radio\" name=\"what\" value=\"claims\"> Delete only the claims. Keep the report.</label><br>");
        } else b.append("<input type=\"hidden\" name=\"what\" value=\"all\"><p class=\"k\">Delete this claim. To keep the record but stop using it, retire it from the Inbox instead.</p>");
        b.append("<button class=\"quiet\">Preview</button></form></details>");
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
        b.append("<p>These are the settings of this library, from ").append(esc(r.path("config_file").asText())).append(". A change takes effect at once, except where a setting says otherwise. A key is shown only as set or not.</p>\n");
        // the models by name, and what the runs cost on them
        {
            java.util.Map<String, ModelEntries.Entry> entries = ModelEntries.all();
            b.append("<h2>Models</h2><p class=\"k\">The models a run may use, by name (researchzosho models adds one; the research form, /model in the chat and the MCP tool pick one).</p><table><tr><th>name</th><th>model</th><th>host</th><th>prices per M</th><th>cap</th><th>key</th></tr>");
            for (ModelEntries.Entry e : entries.values()) b.append("<tr><td>").append(esc(e.name())).append("</td><td>").append(esc(e.model())).append("</td><td>").append(esc(e.host())).append("</td><td>").append(esc(e.pricesText())).append("</td><td>").append(e.cap() > 0 ? Spend.dollars(e.cap()) + " a month" : "").append("</td><td>").append(!e.hosted() ? "none needed" : DriveClient.hostKey(e.host()) != null || (e.name().equals(ModelEntries.DEFAULT) && Config.get("RESEARCHZOSHO_API_KEY") != null) ? "kept" : "<b>none</b>").append("</td></tr>");
            b.append("</table>");
            com.fasterxml.jackson.databind.node.ObjectNode sum = Spend.summary(store);
            if (!sum.path("hosts").isEmpty()) {
                b.append("<h2>Spend</h2><p class=\"k\">What research runs cost at each model's list prices, this month (").append(esc(sum.path("month").asText())).append(") and in all: the library's arithmetic on the servers' token counts, not a bill.</p><table><tr><th>host</th><th>this month</th><th>in all</th></tr>");
            for (com.fasterxml.jackson.databind.JsonNode row : sum.path("hosts")) b.append("<tr><td>").append(esc(row.path("name").asText())).append("</td><td>").append(Spend.dollars(row.path("month").asDouble())).append(" (").append(row.path("runs_month").asInt()).append(" run").append(row.path("runs_month").asInt() == 1 ? "" : "s").append(")</td><td>").append(Spend.dollars(row.path("all").asDouble())).append(" (").append(row.path("runs_all").asInt()).append(")</td></tr>");
                b.append("</table><table><tr><th>named model</th><th>this month</th><th>in all</th><th>cap</th></tr>");
                for (com.fasterxml.jackson.databind.JsonNode row : sum.path("entries")) b.append("<tr><td>").append(esc(row.path("name").asText())).append("</td><td>").append(Spend.dollars(row.path("month").asDouble())).append("</td><td>").append(Spend.dollars(row.path("all").asDouble())).append("</td><td>").append(row.has("cap") ? Spend.dollars(row.path("cap").asDouble()) : "").append("</td></tr>");
                b.append("</table>");
            }
        }
        b.append("<form method=\"post\" action=\"/settings\"><p><label>Setting <select name=\"name\">");
        for (JsonNode s : r.path("settings")) b.append("<option value=\"").append(esc(s.path("name").asText())).append("\">").append(esc(s.path("name").asText())).append("</option>");
        b.append("</select></label> <label>Value <input name=\"value\" size=\"50\" placeholder=\"leave empty to unset\"></label> <button type=\"submit\">Save</button></p></form>\n");
        b.append("<table><tr><th>setting</th><th>value</th><th>what it is for</th></tr>\n");
        for (JsonNode s : r.path("settings")) {
            String v = s.path("value").asText("");
            b.append("<tr><td><code>").append(esc(s.path("name").asText())).append("</code></td><td>").append(v.isEmpty() ? "<i>not set</i>" + (s.hasNonNull("example") && !s.path("example").asText().isEmpty() ? " <small>for example " + esc(s.path("example").asText()) + "</small>" : "") : esc(v))
             .append("</td><td>").append(esc(s.path("what").asText())).append(s.has("choices") ? " <small>(" + esc(String.join(", ", toStrings(s.path("choices")))) + ")</small>" : "").append("</td></tr>\n");
        }
        b.append("</table>\n<p>The same from a terminal: <code>researchzosho settings</code>; and in the chat, in a sentence.</p>\n");
        if (mayWrite(store, patron)) {
            b.append("<h2>Readers</h2><p class=\"k\">Who may read and write this library. Invite a person by name: the link, opened within seven days, lets them choose a password; then they sign in with their name and it, from any phone or computer. \"New link\" is for a forgotten password; \"Set\" puts in a password you typed for them.</p>\n");
            String joined = q.getOrDefault("join", "");
            if (!joined.isEmpty()) b.append("<p class=\"note\">Send this link; it works once, for seven days: <code>").append(esc(joined)).append("</code></p>\n");
            b.append("<form method=\"post\" action=\"/settings\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"invite\"><label>Name <input name=\"name\" size=\"24\"></label> <label>May <select name=\"level\"><option value=\"read\">read</option><option value=\"write\">write</option></select></label> <button>Invite</button></form>\n");
            Patrons.Policy pol = Patrons.load(store);
            b.append("<table><tr><th>who</th><th>may</th><th>signs in with</th><th></th></tr>\n<tr><td><i>anyone not listed</i></td><td>").append(pol.dflt()).append("</td><td></td><td></td></tr>\n");
            for (Patrons.Entry e : pol.listed()) {
                Accounts.Account acc = Accounts.byDid(store, e.did());
                String ways = acc == null ? (e.tokenHash() == null ? "nothing yet" : "a token (a program)") : acc.ways();
                b.append("<tr><td>").append(esc(e.name().isEmpty() ? e.did() : e.name())).append(" <small>").append(esc(e.did())).append("</small></td><td>").append(e.level()).append("</td><td>").append(esc(ways)).append("</td><td><form class=\"inline\" method=\"post\" action=\"/settings\"><input type=\"hidden\" name=\"did\" value=\"").append(esc(e.did())).append("\"><button name=\"op\" value=\"reader-read\" class=\"quiet\">read</button> <button name=\"op\" value=\"reader-write\" class=\"quiet\">write</button> <button name=\"op\" value=\"reader-deny\" class=\"quiet\">deny</button> <button name=\"op\" value=\"reader-remove\" class=\"quiet\">remove</button> <button name=\"op\" value=\"reader-link\" class=\"quiet\">new link</button> <input type=\"password\" name=\"password\" size=\"12\" placeholder=\"a password\" autocomplete=\"off\"> <button name=\"op\" value=\"reader-password\" class=\"quiet\">set</button></form></td></tr>\n");
            }
            b.append("</table>\n<p class=\"k\">The same from a terminal: <code>researchzosho reader invite \"a name\" read</code>, <code>reader link \"a name\"</code>, <code>reader password \"a name\"</code>, <code>reader list</code>, <code>reader allow</code>, <code>reader remove</code>. Your own account: <a href=\"/account\">Account</a>.</p>\n");
        }
        return page(store, patron, "Settings", b.toString(), 0, null, true);
    }

    /** {@code /owned}: what the person owns, from their own records — a search box over it and what the ledger holds. */
    static String owned(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        ObjectNode st = M.createObjectNode(); st.put("op", "status"); st.set("patron", LibrarianDaemon.patronNode(patron));
        JsonNode status = p.owned(st);
        String words = q.getOrDefault("q", "").strip(), year = q.getOrDefault("year", "").strip(), shop = q.getOrDefault("shop", "").strip();
        StringBuilder b = new StringBuilder();
        b.append("<p>").append(esc(status.path("summary").asText())).append("</p>\n");
        List<Recalls.Hit> recallHits = Recalls.hits(store, true);
        if (!recallHits.isEmpty()) {
            b.append("<h2>Recall notices that may concern what you own</h2><table><tr><th>notice</th><th>you have</th><th>found by</th><th></th></tr>\n");
            for (Recalls.Hit h : recallHits) {
                b.append("<tr><td><a href=\"").append(esc(h.noticeUrl())).append("\">").append(esc(h.noticeTitle())).append("</a> <small>").append(esc(h.feed().toUpperCase(Locale.ROOT))).append(", ").append(esc(h.noticeDate())).append("</small></td>")
                 .append("<td>").append(esc(h.lineTitle())).append(" <small>").append(esc(h.shop())).append(", ").append(esc(h.lineDate())).append("</small></td>")
                 .append("<td>").append(esc(h.why())).append(" <small>(").append(esc(h.tier())).append(")</small></td>")
                 .append("<td><form class=\"inline\" method=\"post\" action=\"/owned\"><input type=\"hidden\" name=\"dismiss\" value=\"").append(esc(h.id())).append("\"><button class=\"quiet\">Put away</button></form></td></tr>\n");
            }
            b.append("</table>\n<p class=\"k\">A notice is matched by a barcode (exact), by brand and model number (high), or by the maker named in the notice and the words of the name (medium). Putting one away says it is not yours or is dealt with.</p>\n");
        }
        b.append("<form method=\"get\" action=\"/owned\"><p><input name=\"q\" size=\"40\" value=\"").append(esc(words)).append("\" placeholder=\"words of the thing, the shop or the order number\"> ")
         .append("<input name=\"year\" size=\"6\" value=\"").append(esc(year)).append("\" placeholder=\"year\"> <input name=\"shop\" size=\"14\" value=\"").append(esc(shop)).append("\" placeholder=\"shop\"> <button type=\"submit\">Find</button></p></form>\n");
        if (status.path("lines").asInt() > 0 && (!words.isEmpty() || !year.isEmpty() || !shop.isEmpty() || "1".equals(q.get("all")))) {
            ObjectNode f = M.createObjectNode(); f.put("op", "find"); f.put("words", words); f.put("year", year); f.put("shop", shop); f.put("limit", 200); f.set("patron", LibrarianDaemon.patronNode(patron));
            JsonNode found = p.owned(f);
            b.append("<p>").append(esc(found.path("summary").asText())).append("</p>\n<table><tr><th>date</th><th>shop</th><th>what</th><th>paid</th></tr>\n");
            for (JsonNode l : found.path("lines")) {
                String price = l.path("total").asText().isEmpty() ? l.path("unit_price").asText() : l.path("total").asText();
                b.append("<tr><td>").append(esc(l.path("date").asText())).append("</td><td>").append(esc(l.path("shop").asText())).append("</td><td>").append(esc(l.path("title").asText()))
                 .append(l.path("qty").asInt(1) > 1 ? " ×" + l.path("qty").asInt() : "").append(l.path("confidence").asText().equals("guessed") ? " <small>(read from the mail's text; may be off)</small>" : "")
                 .append("</td><td>").append(esc((price + " " + l.path("currency").asText()).strip())).append("</td></tr>\n");
            }
            b.append("</table>\n");
        } else if (status.path("lines").asInt() > 0) {
            b.append("<p><a href=\"/owned?all=1\">Show the newest 200</a></p>\n");
        }
        if (status.path("sources").size() > 0) {
            b.append("<h2>Where it comes from</h2><table><tr><th>file</th><th>kind</th><th>lines</th><th>records</th></tr>\n");
            for (JsonNode s : status.path("sources")) b.append("<tr><td>").append(esc(s.path("file").asText())).append("</td><td>").append(esc(s.path("kind").asText())).append("</td><td>").append(s.path("lines").asInt())
                    .append("</td><td>").append(esc(s.path("records_begin").asText().isEmpty() ? "no dates" : s.path("records_begin").asText() + " to " + s.path("records_end").asText())).append("</td></tr>\n");
            b.append("</table>\n<p>Where a file's records begin is its first record, not when the account was opened.</p>\n");
        }
        b.append("<p>To add records: <code>researchzosho owned import &lt;file&gt;</code> on the machine the library runs on, with the zip a shop's data request returns, a mailbox file or folder, or a statement as CSV. The ledger is a reference the research and the chat may use; nothing needs it.</p>\n");
        return page(store, patron, "Owned", b.toString(), 0, null, true);
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
            if (r.path("claims_go").size() == 0 && !r.path("report_goes").asBoolean()) b.append("<p>Nothing to delete.</p>");
            else b.append("<form method=\"post\" action=\"/remove\"><input type=\"hidden\" name=\"id\" value=\"").append(esc(id)).append("\"><input type=\"hidden\" name=\"what\" value=\"").append(esc(what)).append("\"><input type=\"hidden\" name=\"confirm\" value=\"1\">")
                  .append(nonceField()).append("<button>Delete</button> <a class=\"quiet\" href=\"/entry/").append(enc(id)).append("\">Cancel</a></form><p class=\"k\">This cannot be undone.</p>");
            return page(store, patron, "Delete " + id, b.toString());
        }
        SENT.put(once, id);
        b.append("<p>").append(esc(r.path("summary").asText())).append("</p><ul>");
        for (JsonNode x : r.path("removed")) b.append("<li>").append(esc(x.asText())).append("</li>");
        b.append("</ul><p class=\"k\">This is recorded in the <a href=\"/changes\">change log</a>.</p>");
        return page(store, patron, "Deleted", b.toString());
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
        b.append("<h2>Running and waiting</h2>");
        boolean paused = r.path("paused").asBoolean();
        b.append("<form method=\"post\" action=\"/jobs\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"").append(paused ? "resume" : "pause").append("\">")
         .append(paused ? "<span class=\"badge\">paused</span> Research is paused: queued runs wait, and a running one pauses at its next turn. <button>Resume</button>" : "<button>Pause research</button> <span class=\"k\">queued runs wait, a running one pauses at its next turn, and nightly maintenance still runs</span>")
         .append("</form>");
        if (r.path("active").size() == 0) b.append("<p class=\"k\">Nothing is running.</p>");
        else {
            b.append("<ul>");
            for (JsonNode j : r.path("active")) b.append("<li>").append(jobLine(j))
                    .append(" <form method=\"post\" action=\"/jobs\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"stop\"><input type=\"hidden\" name=\"job_id\" value=\"").append(esc(j.path("job_id").asText())).append("\"><button>Stop</button></form>")
                    .append("</li>");
            b.append("</ul>");
        }
        b.append("<h2>Finished</h2>");
        if (r.path("finished").size() == 0) b.append("<p class=\"k\">None yet.</p>");
        else { b.append("<ul class=\"tight\">"); for (JsonNode j : r.path("finished")) b.append("<li>").append(jobLine(j)).append("</li>"); b.append("</ul>"); }
        return page(store, patron, "Runs", b.toString());
    }

    /** The Runs page's forms: pause or resume the runner, stop one run. */
    private static void jobsPost(LibraryProtocol p, Patrons.Patron patron, Map<String, String> form) throws IOException {
        ObjectNode a = args(patron);
        String op = form.getOrDefault("op", "");
        if (!op.equals("pause") && !op.equals("resume") && !op.equals("stop")) throw ProtocolError.invalidArgs("This form is not known. Go back and try again.");
        a.put("op", op);
        if (op.equals("stop")) a.put("job_id", form.getOrDefault("job_id", ""));
        p.job(a);
    }

    /** What the housekeeping will do at its next run, from its own plan. */
    static String tonight(LibraryStore store) throws IOException {
        Tonight.Plan t = Tonight.plan(store);
        StringBuilder b = new StringBuilder();
        b.append("<h2>Tonight</h2><p>Nightly maintenance runs at ").append(String.format("%02d:00", t.hour())).append(" on ").append(t.date()).append(".</p>");
        b.append("<p><b>Searches you keep.</b> ");
        if (t.due().isEmpty() && t.later().isEmpty()) b.append("None yet. Add one with <code>researchzosho shelf add &lt;name&gt; &lt;query&gt; [days]</code>.</p>");
        else {
            b.append("</p><ul class=\"tight\">");
            for (Serials.Shelf s : t.due()) b.append("<li><b>runs tonight</b> ").append(esc(s.slug())).append(": ").append(esc(s.query())).append(" <span class=\"k\">(every ").append(s.everyDays()).append(" days, last ").append(esc(s.lastChecked())).append(")</span></li>");
            for (Serials.Shelf s : t.later()) b.append("<li><span class=\"k\">").append(s.parked() ? "parked" : "not yet due").append("</span> ").append(esc(s.slug())).append(": ").append(esc(s.query())).append(" <span class=\"k\">(every ").append(s.everyDays()).append(" days, last ").append(esc(s.lastChecked())).append(")</span></li>");
            b.append("</ul>");
        }
        b.append("<p><b>Open questions it will research:</b> ").append(t.budget()).append(" research run(s) tonight")
         .append(t.override() > 0 ? " (set for tonight only; the usual number is " + t.standing() + ")" : "").append(". Related questions share a run. ")
         .append("Reorder, park, or drop them on the <a href=\"/questions\">Open questions</a> page.</p>");
        if (t.bundles().isEmpty()) b.append("<p class=\"k\">None: no queued question for the nightly research to take.</p>");
        else {
            b.append("<ol class=\"tight\">");
            for (Crews.Bundle bd : t.bundles()) {
                b.append("<li>").append(esc(bd.question())).append(" <span class=\"k\">").append(esc(bd.head().type())).append("</span>");
                List<Frontier.Line> more = bd.more();
                if (!more.isEmpty()) { b.append("<ul class=\"tight\">"); for (Frontier.Line l : more) b.append("<li class=\"k\">+ ").append(esc(Frontier.strip(l.text()))).append("</li>"); b.append("</ul>"); }
                b.append("</li>");
            }
            b.append("</ol>");
            if (t.stillOpen() > 0) b.append("<p class=\"k\">… ").append(t.stillOpen()).append(" more queued for later nights").append(t.parked() > 0 ? "; " + t.parked() + " parked" : "").append(".</p>");
        }
        if (Shelf.path() != null) {
            int minutes = Config.getInt(ShelfDna.MINUTES_KEY, ShelfDna.MINUTES);
            int labelled = Dna.counts(Dna.BOOK)[1];
            b.append("<p class=\"k\">Your shelf: ").append(minutes > 0
                ? "its books are read for their DNA for " + minutes + " minutes, the ones you went for first (" + labelled + " labelled so far)."
                : "the reading of its books for their DNA is turned off (dna_minutes is 0); " + labelled + " labelled so far.").append("</p>");
        }
        b.append("<p class=\"k\">Also: ").append(t.inventoryPerNight()).append(" accepted claims checked against their sources again; new reports reviewed and given subjects; preprints and retractions checked; the search index refreshed; a backup made.")
         .append(t.weekly() ? " Weekly extras tonight: the list of duplicate claims and the list of saved pages nothing refers to." : "")
         .append(t.monthly() ? " Monthly extra tonight: the search index is rebuilt." : "").append("</p>");
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
            b.append("<div class=\"working\"><span class=\"spin\"></span><div><p><b>").append("queued".equals(state) ? "Waiting its turn…" : "Working on it…").append("</b></p>");
            b.append("<p class=\"k\">").append(j.path("elapsed_s").asLong()).append(" s so far. ").append(j.path("args").path("quick").asBoolean() || j.path("quick").asBoolean() ? "A quick lookup: a few minutes. " : "")
             .append("This page updates itself").append(back.isEmpty() ? "." : ", and goes back to the explanation when the answer is ready.").append("</p></div></div>");
        } else if (!back.isEmpty()) {
            b.append("<p>Done. <a href=\"").append(esc(back)).append("\">Read the explanation</a>.</p>");
        }
        b.append("<p class=\"k\">").append(esc(j.path("kind").asText())).append(" · ").append(badge(j.path("state").asText())).append(" · queued ").append(when(j.path("queued_at").asText()));
        if (j.hasNonNull("started_at")) b.append(" · started ").append(when(j.path("started_at").asText()));
        if (j.hasNonNull("ended_at")) b.append(" · ended ").append(when(j.path("ended_at").asText()));
        b.append(" · ").append(j.path("elapsed_s").asLong()).append(" s");
        if (j.hasNonNull("drive")) b.append(" · on ").append(esc(j.path("drive").asText()));
        b.append("</p>");
        if (live && j.path("state").asText().equals("running")) { RunProgress.View v = RunProgress.of(j, System.currentTimeMillis(), RunProgress.typicalSeconds(p, args(patron).path("patron"))); b.append("<p><b>").append(esc(v.stage())).append("</b> · ").append(esc(RunProgress.elapsed(v.elapsedSeconds()))).append(" elapsed · about ").append(v.percent()).append("% done <progress max=\"100\" value=\"").append(v.percent()).append("\"></progress></p>"); }
        if (live && j.has("progress")) b.append("<p class=\"k\">").append(esc(progressLine(j.get("progress")))).append("</p>");
        if (live && j.hasNonNull("waiting")) b.append("<p class=\"k\">Waiting for the model: ").append(esc(j.get("waiting").asText())).append(". A model server that sleeps between uses is starting.</p>");
        if (j.hasNonNull("question")) b.append("<p><b>").append(esc(j.path("question").asText())).append("</b></p>");
        if (j.hasNonNull("investigation")) b.append("<p>The answer is saved as ").append(idLink(j.path("investigation").asText())).append(".</p>");
        boolean declinedRun = j.path("declined").path("run").asBoolean(false);
        if (j.path("declined").isObject()) {
            // the model declined the run, or parts of it: said in its own words, with what the model said
            b.append("<h2>").append(declinedRun ? "Declined by the model" : "Parts declined by the model").append("</h2><p>").append(esc(j.path("declined").path("statement").asText())).append("</p>");
            if (!declinedRun && j.path("declined").path("parts").size() > 0) {
                b.append("<ul>");
                for (JsonNode part : j.path("declined").path("parts")) b.append("<li>").append(esc((part.path("model").asText("").isBlank() ? "It" : "The model " + part.path("model").asText()) + " declined " + part.path("step").asText("a step of the work") + "."))
                        .append(part.path("said").asText("").isBlank() ? "" : " " + esc("What it said: \"" + Acquisitions.compress(part.path("said").asText(), 300) + "\"")).append("</li>");
                b.append("</ul>");
            }
        }
        if (!declinedRun && j.hasNonNull("result") && !j.path("result").asText().isEmpty()) b.append("<h2>").append(j.path("is_error").asBoolean() ? "What went wrong" : "Result").append("</h2><div class=\"body\">").append(md(j.path("result").asText())).append("</div>");
        if (live) return page(store, patron, "Run " + id, b.toString(), 5, null);
        if (!back.isEmpty() && "done".equals(state)) return page(store, patron, "Run " + id, b.toString(), 3, back);
        return page(store, patron, "Run " + id, b.toString());
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
            b.append("<p class=\"notice\">").append(esc(s.path("offer").asText())).append(" The question \"").append(esc(question)).append("\" was sent as ordinary research, as <a href=\"/jobs/")
             .append(enc(id.strip())).append("\">").append(esc(id.strip())).append("</a>. To research it in ").append(esc(field)).append(" mode, <a href=\"/research?q=").append(enc(question)).append("&field=")
             .append(enc(field)).append("\">open it on the Research page with the box ticked</a> and send it.</p>");
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
            b.append("<label class=\"choice\"><input type=\"checkbox\" name=\"field\" value=\"").append(esc(p.name())).append("\"").append(on ? " checked" : "").append("> ").append(esc(p.choice())).append("</label>");
            if (s != null && s.field().equals(p.name()) && !on) { b.append("<p class=\"notice\">").append(esc(s.offer())).append(" Tick the box above to use it. Sent as it is, the question is researched as ordinary research.</p>"); told = true; }
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
                .append("> Let in what the library leaves out by default, for this question only: ").append(esc(ContentOffer.described(List.of(ContentPolicy.EXPLICIT, ContentPolicy.HOWTO)))).append("</label>");
        if (needs != null && !needs.isEmpty()) b.append("<p class=\"notice\">").append(esc(ContentOffer.offer(needs))).append(" Tick the box above to let it in. Sent as it is, the question is researched with that material left out.</p>")
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
            b.append("<p>To ask for research you need to <a href=\"/login\">sign in</a> first.</p>");
        }
        b.append("<p class=\"k\">A question that takes real reading. The library reads, checks every source it uses, and saves the answer as a report you can read here.</p>");
        b.append("<form method=\"post\" action=\"/research\" class=\"stack\">");
        b.append("<label>The question<br><textarea name=\"question\" rows=\"3\" required>").append(esc(q)).append("</textarea></label>");
        String mode = sent.getOrDefault("mode", "broad"), sources = sent.getOrDefault("sources", "both");
        b.append("<label>How<br><select name=\"mode\"><option value=\"broad\">broad — cover the whole topic</option><option value=\"depth\"").append(mode.equals("depth") ? " selected" : "").append(">deep — go into detail on the best sources</option></select></label>");
        b.append("<label>Where to read <small>(this library: the documents you added and what earlier runs found; the web: everything else, the archives and the podcasts included)</small><br><select name=\"sources\"><option value=\"both\">this library and the web</option><option value=\"shelves\"").append(sources.equals("shelves") ? " selected" : "").append(">this library only</option><option value=\"web\"").append(sources.equals("web") ? " selected" : "").append(">the web only</option></select></label>");
        b.append(modelChoice(sent.getOrDefault("model", "")));
        b.append("<label>Limits, if you want any (0 = no limit)<br><input name=\"max_turns\" value=\"").append(num(sent.get("max_turns"))).append("\" size=\"6\"> model turns &nbsp; <input name=\"max_minutes\" value=\"").append(num(sent.get("max_minutes"))).append("\" size=\"6\"> minutes</label>");
        // harm_ok: the person's yes to researching it after the help was shown, carried so that the help is not shown twice
        for (String hidden : List.of("quick", "follow_up_to", "back", "harm_ok")) if (sent.containsKey(hidden) && !sent.get(hidden).isBlank()) b.append("<input type=\"hidden\" name=\"").append(hidden).append("\" value=\"").append(esc(sent.get(hidden))).append("\">");
        b.append(fieldChoices(store, q, field));
        b.append(allowChoice(needs, "1".equals(sent.get("allow"))));
        b.append(nonceField()).append("<button>Send it</button> <button name=\"sharpen\" value=\"1\" class=\"quiet\">Refine it first</button></form>");
        b.append("<p class=\"k\">Refine it first: the library reads what it already holds, finds who studies this, and gives the question back more precise, with what it assumed and a plan. You edit, then send. No research runs yet.</p>");
        return page(store, patron, "Research", b.toString());
    }

    /**
     * Start sharpening in the background (or join the one already running for this question) and show the working page. {@code field}: the
     * field the person ticked, kept ticked on the refined form.
     */
    private static String sharpenStart(LibraryStore store, Patrons.Patron patron, String question, String field) throws IOException {
        String q = question.strip();
        if (q.length() < 8) return researchForm(store, patron, q, "The question is too short to refine.", field);
        Patrons.check(store, patron, Patrons.Level.read);
        Researcher.Drive drive = Explain.drive();
        if (drive == null) throw ProtocolError.unavailable("No model is answering right now. Refining a question needs one.");
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
        if (w == null) return researchForm(store, patron, "", "That refinement has expired. Type the question again.", field);
        if (!w.done) {
            long secs = (System.currentTimeMillis() - w.started) / 1000;
            String body = "<div class=\"working\"><span class=\"spin\"></span><div><p><b>Refining the question…</b></p>"
                    + "<p class=\"k\">" + secs + " seconds so far. The library reads what it already holds, finds who studies this, and writes the plan. This takes about half a minute.</p>"
                    + "<p class=\"k\">This page updates itself.</p></div></div>";
            return page(store, patron, "Refining", body, 2, "/research?sharpen=" + key + (field == null || field.isBlank() ? "" : "&field=" + enc(field.strip())));
        }
        if (w.error instanceof Declined declined) throw declined;   // the model declined: the page says so
        if (w.error != null) throw w.error instanceof ProtocolError pe ? pe : ProtocolError.unavailable("The question could not be refined: " + w.error.getMessage());
        return sharpened(store, patron, w.result, field);
    }

    /** The sharpened question: what it assumed, the plan, what you hold, and the form filled back in with it, the field ticked as it was. */
    private static String sharpened(LibraryStore store, Patrons.Patron patron, Sharpen.Sharpened s, String field) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\">You asked: ").append(esc(s.original())).append("</p>");
        b.append("<h2>Refined</h2><p><b>").append(esc(s.question())).append("</b></p>");
        if (!s.assumptions().isEmpty()) {
            b.append("<p class=\"k\">To get there it assumed:</p><ul class=\"tight\">");
            for (String a : s.assumptions()) b.append("<li>").append(esc(a)).append("</li>");
            b.append("</ul>");
        }
        if (!s.questionsForYou().isEmpty()) {
            b.append("<p>It would help to know:</p><ul class=\"tight\">");
            for (String x : s.questionsForYou()) b.append("<li>").append(esc(x)).append("</li>");
            b.append("</ul><p class=\"k\">Put the answers into the question below before you send it.</p>");
        }
        if (!s.held().isEmpty()) {
            b.append("<h2>Already in the library</h2><ul class=\"tight\">");
            for (Sharpen.Held h : s.held()) b.append("<li>").append(idLink(h.id(), h.title())).append(" <span class=\"k\">").append(esc(h.state())).append("</span></li>");
            b.append("</ul>");
        }
        b.append("<h2>The plan</h2><pre>").append(esc(s.brief().render())).append("</pre>");
        b.append("<p class=\"k\">Mode: ").append(esc(s.mode())).append(" · size: ").append("quick".equals(s.size()) ? "quick — a few pages should settle it" : "full — it takes real reading");
        if (!s.lanes().isEmpty()) { b.append(" · languages: "); for (Lanes.Lane l : s.lanes()) b.append(esc(l.name())).append(' '); }
        b.append("</p>");
        b.append("<h2>Send it</h2><p class=\"k\">This is what the library will research. Change anything you like.</p>");
        b.append("<form method=\"post\" action=\"/research\" class=\"stack\">");
        b.append("<label>The question<br><textarea name=\"question\" rows=\"14\" required>").append(esc(s.researchQuestion())).append("</textarea></label>");
        b.append("<label>How<br><select name=\"mode\"><option value=\"broad\"").append("broad".equals(s.mode()) ? " selected" : "").append(">broad — cover the whole topic</option><option value=\"depth\"").append("depth".equals(s.mode()) ? " selected" : "").append(">deep — go into detail on the best sources</option></select></label>");
        b.append("<label>Where to read <small>(this library: the documents you added and what earlier runs found; the web: everything else, the archives and the podcasts included)</small><br><select name=\"sources\"><option value=\"both\">this library and the web</option><option value=\"shelves\">this library only</option><option value=\"web\">the web only</option></select></label>");
        boolean quick = "quick".equals(s.size());
        b.append("<label>Limits, if you want any (0 = no limit)<br><input name=\"max_turns\" value=\"").append(quick ? Explain.QUICK_TURNS : 0).append("\" size=\"6\"> model turns &nbsp; <input name=\"max_minutes\" value=\"").append(quick ? Explain.QUICK_MINUTES : 0).append("\" size=\"6\"> minutes</label>");
        if (quick) b.append("<input type=\"hidden\" name=\"quick\" value=\"1\">");
        b.append(fieldChoices(store, s.researchQuestion(), field));
        b.append(allowChoice(List.of(), false));
        b.append(nonceField()).append("<button>Send it</button> <button name=\"sharpen\" value=\"1\" class=\"quiet\">Refine again</button></form>");
        return page(store, patron, "Refined", b.toString());
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
        b.append("<p>Do you want the library to research this question?</p><form method=\"post\" action=\"/research\" class=\"inline\">");
        for (String k : List.of("question", "mode", "sources", "max_turns", "max_minutes", "quick", "follow_up_to", "back", "field", "allow", "content_told", "told"))
            if (form.containsKey(k) && !form.get(k).isBlank()) b.append("<input type=\"hidden\" name=\"").append(k).append("\" value=\"").append(esc(form.get(k))).append("\">");
        b.append("<input type=\"hidden\" name=\"harm_ok\" value=\"1\">").append(nonceField()).append("<button>Yes, research it</button></form> ");
        b.append("<a href=\"/research\">No, do not research it</a>");
        b.append("<p class=\"k\">Nothing is sent unless you say yes.</p>");
        return page(store, patron, "Before you send it", b.toString());
    }

    /** The model to run on, when the library has more than its default: a dropdown of the named models, the default first. */
    static String modelChoice(String chosen) {
        Map<String, ModelEntries.Entry> all = ModelEntries.all();
        if (all.size() < 2) return "";
        StringBuilder b = new StringBuilder("<label>Which model<br><select name=\"model\">");
        b.append("<option value=\"auto\"").append(Auto.NAME.equals(chosen) || (chosen.isEmpty() && Auto.isDefault()) ? " selected" : "").append(">auto — best judgement picks, and says why</option>");
        for (ModelEntries.Entry e : all.values()) {
            String label = e.name().equals(ModelEntries.DEFAULT) ? "default — " + e.model() + " via " + e.host() : e.name() + " — " + e.model() + " via " + e.host() + (e.prices() == null ? "" : ", $" + e.pricesText() + " per M");
            b.append("<option value=\"").append(esc(e.name())).append("\"").append(e.name().equals(chosen) ? " selected" : "").append(">").append(esc(label)).append("</option>");
        }
        return b.append("</select> <small>researchzosho models adds one</small></label>").toString();
    }

    private static String researchPost(LibrarianDaemon d, LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);   // before anything is written down about the question
        String once = form.getOrDefault("once", "");
        if (!once.isEmpty() && SENT.containsKey(once)) {
            String id = SENT.get(once);
            return page(store, patron, "Already sent", "<p>This question was already sent, as <a href=\"/jobs/" + enc(id) + "\">" + esc(id) + "</a>. A second click or a refresh does not send it again.</p>"
                    + "<p class=\"k\"><a href=\"/research\">Send a different question</a> · <a href=\"/jobs\">the runs</a></p>");
        }
        ObjectNode body = M.createObjectNode();
        String question = form.getOrDefault("question", "").strip(), followUpTo = form.getOrDefault("follow_up_to", "").strip();
        if (!followUpTo.isEmpty() && !question.isEmpty() && !question.startsWith("Follow-up to ")) question = "Follow-up to " + followUpTo + ": " + question;   // the earlier report is handed to the run
        body.put("question", question);
        body.put("mode", form.getOrDefault("mode", "broad"));
        body.put("sources", form.getOrDefault("sources", "both"));
        body.put("max_turns", num(form.get("max_turns"))); body.put("max_minutes", num(form.get("max_minutes")));
        if ("1".equals(form.get("quick"))) body.put("quick", true);
        String modelEntry = form.getOrDefault("model", "").strip();
        if (!modelEntry.isEmpty() && !modelEntry.equals(ModelEntries.DEFAULT)) body.put("model", modelEntry);
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
        return page(store, patron, "Sent", help + "<p>Sent as <a href=\"" + jobUrl + "\">" + esc(id) + "</a>" + (r.hasNonNull("field") ? ", in " + esc(r.path("field").asText()) + " mode" : "") + (quick ? ", first in line" : "") + ". " + r.path("queued_ahead").asInt() + " before it, " + r.path("workers").asInt()
                + " worker(s). The <a href=\"/jobs\">runs</a> page shows how it is going. The answer appears there, and in the library, when it is done.</p>"
                + (back.isEmpty() ? "" : "<p>When it is done, <a href=\"" + esc(back) + "\">read the explanation</a>" + (quick ? " — a few minutes" : "") + ".</p>"),
                quick ? 2 : 0, quick ? jobUrl : null);
    }

    static String explain(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        String id = q.getOrDefault("id", "").strip(), term = q.getOrDefault("term", "").strip(), in = q.getOrDefault("in", "").strip();
        Explain.Rung rung = Explain.Rung.of(q.getOrDefault("rung", "beginner"));
        boolean fresh = "1".equals(q.get("fresh"));
        if (id.isEmpty() && term.isEmpty()) return page(store, patron, "Explain", "<p>Open an entry and pick a reading level, or type a word to explain.</p>");
        Patrons.check(store, patron, Patrons.Level.read);
        String key = (term.isEmpty() ? "id=" + id : "term=" + term.toLowerCase(Locale.ROOT) + "&in=" + in) + "&rung=" + rung.name();
        Working w = WORKING.get(key);
        if (w != null && !w.done) return workingPage(store, patron, w, term.isEmpty() ? id : term, in.isEmpty() ? id : in, rung);
        if (w != null && w.error instanceof Declined declined) { WORKING.remove(key); throw declined; }   // the model declined: the page says so
        if (w != null && w.error != null) { WORKING.remove(key); throw w.error instanceof ProtocolError pe ? pe : ProtocolError.unavailable("The explanation could not be written: " + w.error.getMessage()); }
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
            if (drive == null) throw ProtocolError.unavailable("No model is answering right now. The first explanation needs one.");
            Working nw = new Working();
            if (WORKING.putIfAbsent(key, nw) == null) {
                final boolean f = fresh;
                Thread t = new Thread(() -> {
                    try {
                        if (term.isEmpty()) Explain.entry(store, drive, id, rung, f, st -> nw.stage = st);
                        else Explain.term(store, drive, term, in, rung, f, st -> nw.stage = st);
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
            b.append("<p class=\"k\">").append(term.isEmpty() ? "" : "as used in ").append(idLink(of, e == null ? of : e.path("title").asText())).append("</p>");
        }
        if (term.isEmpty()) b.append(ladder(of, rung));
        else b.append("<p class=\"k\">For: ").append(rungLinks("/explain?term=" + enc(term) + "&in=" + enc(of) + "&rung=", rung)).append("</p>");
        String grounding = r.path("grounding").asText();
        if ("none".equals(grounding)) {
            b.append("<p>").append(esc(r.path("text").asText())).append("</p>");
            JsonNode offer = r.path("offer");
            if (offer.isObject()) {
                String back = "/explain?term=" + enc(term) + "&in=" + enc(of) + "&rung=" + rung.name();
                b.append("<p>The library can look it up. A quick look takes a few minutes and goes first. Full research has no limits and waits its turn. Either way every source is checked and the answer is saved. Then this page can explain it.</p>");
                b.append(offerForm(offer, back, true)).append(offerForm(offer, back, false));
                b.append("<p class=\"k\">The question it will ask: ").append(esc(offer.path("question").asText())).append("</p>");
            }
        } else {
            b.append("<p class=\"k\">Based on the library: ").append("shelves".equals(grounding) ? "fully" : "thin".equals(grounding) ? "partly" : esc(grounding));
            if (r.path("checked").asInt() > 0) b.append(" · ").append(r.path("checked").asInt()).append(" paragraph(s) checked against their sources, ").append(r.path("unsupported").asInt()).append(" not backed up");
            b.append(r.path("cached").asBoolean() ? " · written " + when(r.path("generated_at").asText()) : " · just written");
            b.append(" · <a href=\"/explain?").append(term.isEmpty() ? "id=" + enc(of) : "term=" + enc(term) + "&in=" + enc(of)).append("&rung=").append(rung.name()).append("&fresh=1\">write it again</a></p>");
            if (term.isEmpty()) b.append(downloads(of, rung));
            b.append("<div class=\"body reading\">").append(readingHtml(r.path("text").asText())).append("</div>");
            if (!term.isEmpty() || r.path("terms").size() > 0) {
                b.append("<h2>Words you may meet next</h2>");
                if (r.path("terms").size() == 0) b.append("<p class=\"k\">None.</p>");
                else {
                    b.append("<ul>");
                    for (JsonNode t : r.path("terms")) b.append("<li><a href=\"/explain?term=").append(enc(t.path("term").asText())).append("&in=").append(enc(of)).append("&rung=").append(rung.name()).append("\">").append(esc(t.path("term").asText())).append("</a> <span class=\"k\">").append(esc(t.path("gloss").asText())).append("</span></li>");
                    b.append("</ul>");
                }
            }
        }
        b.append("<form class=\"big\" action=\"/explain\"><input type=\"hidden\" name=\"in\" value=\"").append(esc(of)).append("\"><input type=\"hidden\" name=\"rung\" value=\"").append(rung.name())
         .append("\"><input name=\"term\" placeholder=\"What I don't understand is…\"> <button>Explain</button></form>");
        b.append("<p class=\"k\">This is a plain-language version, not a record. Nothing here is saved as a claim, and each paragraph says where in the library it comes from.</p>");
        String title = term.isEmpty() ? "Reading level: " + rungLabel(rung) : esc(term);
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
        final long started = System.currentTimeMillis();
        volatile boolean done;
        volatile Throwable error;
    }
    static final ConcurrentHashMap<String, Working> WORKING = new ConcurrentHashMap<>();

    /** The page that comes back at once: what the library is doing, for how long, refreshing itself until the reading is there. */
    private static String workingPage(LibraryStore store, Patrons.Patron patron, Working w, String what, String of, Explain.Rung rung) throws IOException {
        long secs = (System.currentTimeMillis() - w.started) / 1000;
        StringBuilder b = new StringBuilder();
        b.append("<div class=\"working\"><span class=\"spin\"></span><div><p><b>The library is ").append(esc(w.stage)).append("…</b></p>");
        b.append("<p class=\"k\">").append(secs).append(" seconds so far. The library reads what it has, writes, then checks each paragraph against its sources. ")
         .append("A report takes about a minute the first time; a word, ten to twenty seconds.</p>");
        b.append("<p class=\"k\">This page updates itself. You can leave; the result is kept.</p></div></div>");
        if (!of.isEmpty()) b.append("<p class=\"k\">").append(idLink(of)).append("</p>");
        return page(store, patron, "Working on: " + what, b.toString(), 2, null);
    }

    private static String downloads(String id, Explain.Rung rung) {
        String q = "id=" + enc(id) + (rung == null || rung == Explain.Rung.written ? "" : "&rung=" + rung.name());
        return "<p class=\"k\">Download: <a href=\"/download?" + q + "&as=md\">Markdown</a> · <a href=\"/download?" + q + "&as=pdf\">PDF</a></p>";
    }

    private static void download(HttpExchange x, LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        String id = q.getOrDefault("id", "").strip();
        if (!LibraryStore.safeName(id)) throw ProtocolError.notFound("entry " + id);
        Explain.Rung rung = q.containsKey("rung") ? Explain.Rung.of(q.get("rung")) : Explain.Rung.written;
        Explain.Reading reading = rung == Explain.Rung.written ? null : Explain.entry(store, null, id, rung, false, Explain.Progress.NONE);   // a reading must already be written; the page writes it
        boolean pdf = "pdf".equalsIgnoreCase(q.getOrDefault("as", "md"));
        Export.File f = pdf ? Export.pdf(store, id, reading) : Export.markdown(store, id, reading);
        x.getResponseHeaders().set("Content-Type", f.contentType());
        x.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + f.name() + "\"");
        x.sendResponseHeaders(200, f.bytes().length);
        try (OutputStream os = x.getResponseBody()) { os.write(f.bytes()); }
        store.circulate("download", patron.label() + " :: " + f.name());
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
        return "<p class=\"k\">Reading level: " + rungLinks("/explain?id=" + enc(id) + "&rung=", current) + " <span class=\"k\">(the first time takes about a minute)</span></p>";
    }
    /** A reading level as a person sees it: Simple, Familiar, Original. */
    static String rungLabel(Explain.Rung r) {
        return switch (r) { case beginner -> "Simple"; case familiar -> "Familiar"; case written -> "Original"; };
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
        b.append("<input type=\"hidden\" name=\"back\" value=\"").append(esc(back)).append("\"><button>").append(now ? "Quick look (a few minutes)" : "Full research (no limits, waits its turn)").append("</button></form> ");
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
            if (c.endsWith(".md")) rep = "<a class=\"cite\" href=\"/entry/" + enc(c) + "\">[source]</a>";
            else if (c.startsWith("http")) rep = "<a class=\"cite\" href=\"" + c + "\" rel=\"noreferrer\">[source]</a>";
            else rep = "<a class=\"cite\" href=\"/entry/" + enc(c) + "\">[" + c.substring(0, Math.min(6, c.length())) + "]</a>";
            m.appendReplacement(b, Matcher.quoteReplacement(rep));
        }
        m.appendTail(b);
        return b.toString().replace(esc(Explain.UNCITED_MARK), "<span class=\"mark\">not from the library</span>")
                           .replace(esc(Explain.UNSUPPORTED_MARK), "<span class=\"mark bad\">the sources do not say this</span>");
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
        if (did.isEmpty()) return "Which reader? Go back and try again.";
        switch (op) {
            case "reader-remove" -> { Patrons.remove(store, did); Accounts.remove(store, did); return did + " removed."; }
            case "reader-password" -> {
                Patrons.Entry e = Patrons.byDid(store, did);
                if (e == null) return did + " is not on the readers list.";
                try {
                    if (Accounts.byDid(store, did) == null) Accounts.create(store, did, e.name().isEmpty() ? did : e.name(), form.getOrDefault("password", ""));
                    else Accounts.setPassword(store, did, form.getOrDefault("password", ""));
                } catch (IOException ex) { return ex.getMessage(); }
                return (e.name().isEmpty() ? did : e.name()) + " signs in with that password now.";
            }
            case "reader-read", "reader-write", "reader-deny" -> { Patrons.Level l = Patrons.Level.valueOf(op.substring(7)); Patrons.set(store, did, null, l); return did + (l == Patrons.Level.deny ? " is denied." : " may " + l + "."); }
            default -> { return "That is not a change the readers list knows."; }
        }
    }

    // ---- pieces ----

    private static String entryCard(JsonNode e) {
        StringBuilder b = new StringBuilder("<div class=\"card\"><h3>").append(idLink(e.path("id").asText(), e.path("title").asText())).append("</h3>");
        b.append("<p class=\"k\">").append(esc(e.path("kind").asText())).append(" · ").append(badge(e.path("state").asText()));
        if (!"raw".equals(e.path("kind").asText())) b.append(" · ").append(esc(e.path("confidence").asText()));
        int n = e.path("sources").size(), ind = e.path("independent_sources").asInt(n);
        b.append(" · ").append(n).append(n == 1 ? " source" : " sources").append(ind < n ? " (" + ind + " independent)" : "").append("</p>");
        b.append("<p>").append(esc(Acquisitions.compress(plain(e.path("body").asText()), 500))).append("</p></div>");
        return b.toString();
    }

    private static String hitLine(JsonNode h) {
        return idLink(h.path("id").asText(), h.path("title").asText()) + " <span class=\"k\">" + esc(h.path("kind").asText()) + " · " + badge(h.path("state").asText()) + "</span>"
                + (h.path("snippet").asText().isEmpty() ? "" : "<br><span class=\"snip\">" + esc(h.path("snippet").asText()) + "</span>");
    }

    private static String changeLine(Changes.Change c) {
        return "<span class=\"k\">" + esc(c.at().length() > 16 ? c.at().substring(0, 16).replace('T', ' ') : c.at()) + "</span> " + esc(c.event()) + " " + idLink(c.id())
                + (c.detail().isEmpty() ? "" : " <span class=\"k\">" + esc(Acquisitions.compress(c.detail(), 140)) + "</span>");
    }

    static String jobLine(JsonNode j) {
        String id = j.path("job_id").asText();
        return "<a href=\"/jobs/" + enc(id) + "\">" + esc(id) + "</a> " + badge(j.path("state").asText()) + (j.hasNonNull("waiting") ? " <span class=\"badge\">waiting for the model</span>" : "")
                + (j.path("declined").path("run").asBoolean(false) ? " <span class=\"badge\">declined by the model</span>" : j.path("declined").isObject() ? " <span class=\"badge\">parts declined by the model</span>" : "")
                + " <span class=\"k\">" + esc(j.path("kind").asText()) + " · " + j.path("elapsed_s").asLong() + " s</span>"
                + (j.path("state").asText().equals("running") ? runBar(j) : "")
                + (j.hasNonNull("question") ? "<br>" + esc(Acquisitions.compress(j.path("question").asText(), 160)) : "")
                + (j.hasNonNull("investigation") ? " → " + idLink(j.path("investigation").asText()) : "");
    }

    /** How long a run usually takes here, for list rows that have no protocol at hand; set by the pages that do. */
    static volatile LongSupplier TYPICAL = () -> 0;

    /** A running run's stage, time and percent with a bar, for a list row. */
    static String runBar(JsonNode j) {
        RunProgress.View v = RunProgress.of(j, System.currentTimeMillis(), TYPICAL.getAsLong());
        return "<br><span class=\"k\">" + esc(v.stage()) + " · " + esc(RunProgress.elapsed(v.elapsedSeconds())) + " elapsed · about " + v.percent() + "% done</span> <progress max=\"100\" value=\"" + v.percent() + "\"></progress>";
    }

    /** Markdown marks stripped, for a one-paragraph preview. */
    static String plain(String md) {
        return md.replaceAll("(?m)^#+\\s*", "").replaceAll("(?m)^\\s*[-*•]\\s+", "").replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
                 .replaceAll("\\[\\[([^\\]|]+)(?:\\|[^\\]]+)?\\]\\]", "$1").replaceAll("`", "").replace("\n", " ").replaceAll("\\s+", " ").strip();
    }
    /** An instant as a person reads it: the date and the minute. */
    static String when(String iso) {
        if (iso == null || iso.length() < 16 || iso.charAt(10) != 'T') return esc(iso);
        return esc(iso.substring(0, 16).replace('T', ' ') + " UTC");
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
    static String badge(String state) { return "<span class=\"badge " + esc(state) + "\">" + esc(state) + "</span>"; }
    /** "round 1 of 2 · workers 3 of 6 done · 41 of 200 turns · critic" — the runner's progress, for the page. */
    static String progressLine(JsonNode p) {
        StringBuilder sb = new StringBuilder();
        if (p.path("round").asInt() > 0) sb.append("round ").append(p.path("round").asInt()).append(" of ").append(p.path("rounds").asInt());
        if (p.path("workers_total").asInt() > 0) sb.append(sb.length() > 0 ? " · " : "").append("workers ").append(p.path("workers_done").asInt()).append(" of ").append(p.path("workers_total").asInt()).append(" done");
        if (p.path("turns_ceiling").asInt() > 0) sb.append(sb.length() > 0 ? " · " : "").append(p.path("turns_used").asInt()).append(" of ").append(p.path("turns_ceiling").asInt()).append(" turns");
        else if (p.path("turns_used").asInt() > 0) sb.append(sb.length() > 0 ? " · " : "").append(p.path("turns_used").asInt()).append(" turns, no turn limit");
        if (p.hasNonNull("deadline_at")) { try { long min = (Instant.parse(p.get("deadline_at").asText()).toEpochMilli() - System.currentTimeMillis()) / 60_000; sb.append(sb.length() > 0 ? " · " : "").append(min >= 0 ? min + " min left" : "past its time limit, finishing up"); } catch (Exception ignored) { } }
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

    /** Just enough Markdown: headings, lists, paragraphs, links, bold, code, and [[F-…]] shelf links. */
    static String md(String text) {
        text = unentity(text);
        StringBuilder out = new StringBuilder();
        boolean inList = false, inPre = false, inTable = false, headerDone = false;
        StringBuilder para = new StringBuilder();
        for (String raw : text.split("\n")) {
            String line = raw.stripTrailing();
            if (line.startsWith("```")) { flush(out, para); if (inList) { out.append("</ul>"); inList = false; } if (inTable) { out.append("</table></div>"); inTable = false; } inPre = !inPre; out.append(inPre ? "<pre>" : "</pre>"); continue; }
            if (inPre) { out.append(esc(line)).append('\n'); continue; }
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
        flush(out, para); if (inList) out.append("</ul>"); if (inPre) out.append("</pre>"); if (inTable) out.append("</table></div>");
        return out.toString();
    }
    private static void flush(StringBuilder out, StringBuilder para) { if (para.length() > 0) { out.append("<p>").append(inline(para.toString())).append("</p>"); para.setLength(0); } }

    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)|\\[\\[([A-Z]-[^\\]|]+)(?:\\|([^\\]]+))?\\]\\]|(?<![\"(>=])(https?://[^\\s<>\"')\\]]+)|\\*\\*([^*]+)\\*\\*|`([^`]+)`");
    static String inline(String s) {
        String e = esc(s);
        Matcher m = LINK.matcher(e);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            String rep;
            if (m.group(2) != null) rep = "<a href=\"" + m.group(2) + "\" rel=\"noreferrer\">" + m.group(1) + "</a>";
            else if (m.group(3) != null) rep = "<a href=\"/entry/" + enc(m.group(3)) + "\">" + (m.group(4) != null ? m.group(4) : m.group(3)) + "</a>";
            else if (m.group(5) != null) rep = "<a href=\"" + m.group(5) + "\" rel=\"noreferrer\">" + m.group(5) + "</a>";
            else if (m.group(6) != null) rep = "<b>" + m.group(6) + "</b>";
            else rep = "<code>" + m.group(7) + "</code>";
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
     * The latest conversation of this patron and a box to say the next thing; `?new=1` starts a fresh one, `?session=<id>` opens one.
     * A reader only ever opens their own conversations: a conversation belongs to whoever had it.
     */
    static String chat(LibraryStore store, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        Librarian.Session s;
        if ("1".equals(q.get("new"))) s = Librarian.Session.open(store, patron);
        else if (q.get("session") != null && !q.get("session").isBlank()) { s = Librarian.Session.resume(store, q.get("session"), patron); if (s == null) s = Librarian.Session.latest(store, patron); }
        else s = Librarian.Session.latest(store, patron);
        if (s == null) return page(store, patron, "Chat", "<p>" + esc(CHAT_SIGN_IN) + " <a href=\"/login\">Sign in</a></p>");
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\">The Librarian answers from what is in the library and says where each answer comes from. Say \"Find out …\" to start a research run; \"yes\" is enough to accept an offer. "
                + "Session <code>").append(esc(s.id)).append("</code> · <a href=\"/chat?new=1\">new conversation</a>");
        List<String> ids = Librarian.Session.list(store, patron);
        if (ids.size() > 1) { b.append(" · earlier: "); int n = 0; for (String id : ids) { if (id.equals(s.id)) continue; if (n++ >= 5) break; b.append("<a href=\"/chat?session=").append(enc(id)).append("\">").append(esc(id.substring(2, Math.min(id.length(), 18)))).append("</a> "); } }
        b.append("</p><div id=\"chat-view\" class=\"chat-view\">").append(chatView(store, new LibraryProtocol(store), patron, s, "")).append("</div>");
        b.append("<form id=\"chat-say\" class=\"big\" method=\"post\" action=\"/chat\"><input type=\"hidden\" name=\"session\" value=\"").append(esc(s.id)).append("\">")
         .append("<input name=\"say\" autofocus placeholder=\"Ask the Librarian…\" required autocomplete=\"off\"><button>Send</button></form>")
         .append("<p class=\"k\">The reply appears when the model has finished. This can take a while; you can type the next thing meanwhile. A research run you start here shows its progress above, and the chat says when it is done.</p>")
         .append("<script>").append(CHAT_PAGE_JS).append("</script>");
        return page(store, patron, "Chat", "<div class=\"chat-page\">" + b + "</div>");
    }

    /**
     * The Chat page's changing part: the conversation, the runs it follows, a waiting question's two buttons. The page's script puts a
     * reply here in place of what was there, so the box to say the next thing is never redrawn and what is typed in it stays (2026-10-12).
     */
    static String chatView(LibraryStore store, LibraryProtocol lp, Patrons.Patron patron, Librarian.Session s, String note) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        StringBuilder b = new StringBuilder();
        // the runs this conversation started: a finished one is announced once, in the conversation itself
        for (String jobId : s.watched()) {
            try { RunProgress.View v = RunProgress.of(lp.job(args(patron).put("job_id", jobId)).path("job"), System.currentTimeMillis(), RunProgress.typicalSeconds(lp, args(patron).path("patron"))); if (!v.active()) s.told(jobId, v); } catch (Exception ignored) { }
        }
        b.append("<div id=\"chat-scroll\" class=\"chat-scroll\">").append(transcript(s, Integer.MAX_VALUE)).append("</div>");
        if (!note.isEmpty()) b.append("<p class=\"k note\">").append(esc(note)).append("</p>");   // under the latest turn, where the eye is
        // the runs still going: their stage, time and percent; the page's script refreshes the strip alone, in place
        if (!s.watched().isEmpty()) b.append("<div id=\"runs-strip\" data-session=\"").append(esc(s.id)).append("\">").append(runsStrip(lp, patron, s)).append("</div>");
        if (!Librarian.stillWaiting(store, s).isEmpty()) {
            // the Librarian asked a question that ends in (y/N) and still waits: two buttons answer it; with no answer the run starts as it was filed
            b.append("<p>")
             .append("<form class=\"inline page-say\" method=\"post\" action=\"/chat\"><input type=\"hidden\" name=\"session\" value=\"").append(esc(s.id)).append("\"><input type=\"hidden\" name=\"say\" value=\"y\"><button>Yes</button></form> ")
             .append("<form class=\"inline page-say\" method=\"post\" action=\"/chat\"><input type=\"hidden\" name=\"session\" value=\"").append(esc(s.id)).append("\"><input type=\"hidden\" name=\"say\" value=\"n\"><button class=\"quiet\">No</button></form>")
             .append(" <span class=\"k\">No answer within ").append(Librarian.OFFER_MINUTES).append(" minutes counts as no.</span></p>");
        }
        return b.toString();
    }

    /** The conversation a form names, else the patron's latest; null when there is none (not signed in). */
    static Librarian.Session chatSession(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Librarian.Session s = form.get("session") == null ? null : Librarian.Session.resume(store, form.get("session"), patron);
        return s != null ? s : Librarian.Session.latest(store, patron);
    }

    /**
     * The Chat page's script. The box of turns opens at the latest one. The strip of runs refreshes itself in place. A turn is sent from
     * the page, and the reply replaces the changing part; the buttons are held while the model works, the box is not. The box to say the
     * next thing is never redrawn, so what is being typed stays; the draft is kept by the browser for this tab as well, so a reload, or
     * the panel's link to the whole conversation, brings it back. Before this a reply came as a whole new page and took the typing with it (2026-10-12).
     */
    static final String CHAT_PAGE_JS = "(function(){var view=document.getElementById('chat-view'),form=document.getElementById('chat-say');if(!view||!form)return;"
            + "var inp=form.querySelector('input[name=say]'),dkey='rz.chat.draft',busy=false,timer=null;"
            + "function box(){return document.getElementById('chat-scroll')}function end(){var s=box();if(s)s.scrollTop=s.scrollHeight}"
            + "try{var d0=sessionStorage.getItem(dkey);if(d0&&!inp.value)inp.value=d0}catch(e){}"
            + "inp.addEventListener('input',function(){try{sessionStorage.setItem(dkey,inp.value)}catch(e){}});"
            + "function poll(){if(timer)clearTimeout(timer);timer=null;if(!document.getElementById('runs-strip'))return;"
            + "timer=setTimeout(function(){var el=document.getElementById('runs-strip');if(!el)return;fetch('/chat/runs?session='+encodeURIComponent(el.dataset.session),{credentials:'same-origin'}).then(function(r){return r.text()}).then(function(t){var now=document.getElementById('runs-strip');if(now)now.innerHTML=t;poll()}).catch(function(){poll()})},15000)}"
            + "function hold(on){var bs=document.querySelectorAll('.chat-page button');for(var i=0;i<bs.length;i++){bs[i].disabled=on}}"
            + "function wire(){var fs=view.querySelectorAll('form.page-say');for(var i=0;i<fs.length;i++){fs[i].addEventListener('submit',send)}}"
            + "function send(ev){ev.preventDefault();if(busy)return;var f=ev.target,src=f.querySelector('input[name=say]'),words=(src.value||'').trim();if(!words)return;"
            + "var fd=new URLSearchParams(new FormData(f));fd.append('page','1');busy=true;hold(true);"
            + "if(src===inp){inp.value='';try{sessionStorage.removeItem(dkey)}catch(e){}}"
            + "var t=view.querySelector('.chat');if(t&&src.type!=='hidden'){var w=document.createElement('div');w.className='say me';w.innerHTML='<b>You</b><p></p>';w.querySelector('p').textContent=words;t.appendChild(w)}"
            + "var th=document.createElement('p');th.className='k thinking';th.textContent='The Librarian is thinking\u2026 this can take a while. You can type the next thing meanwhile.';(box()||view).appendChild(th);end();"
            + "fetch('/chat',{method:'POST',body:fd,credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'}}).then(function(r){if(!r.ok)throw new Error(String(r.status));return r.text()})"
            + ".then(function(h){var had=document.activeElement;view.innerHTML=h;busy=false;hold(false);wire();end();poll();if(had===document.body||(had&&had.form===form))inp.focus()})"
            + ".catch(function(){th.textContent='The reply did not arrive here; the conversation keeps it. Reload the page in a moment.';busy=false;hold(false)})}"
            + "form.addEventListener('submit',send);wire();end();window.addEventListener('load',end);poll()})();";

    /** The conversation's turns as HTML, the last {@code limit} messages: what was said, what the Librarian answered, what it looked up. */
    static String transcript(Librarian.Session s, int limit) {
        StringBuilder b = new StringBuilder("<div class=\"chat\">");
        List<JsonNode> all = new ArrayList<>(); for (JsonNode m : s.messages()) all.add(m);
        for (JsonNode m : all.size() > limit ? all.subList(all.size() - limit, all.size()) : all) {
            String role = m.path("role").asText();
            if (role.equals("user")) b.append("<div class=\"say me\"><b>You</b><p>").append(esc(m.path("content").asText(""))).append("</p></div>");
            else if (role.equals("assistant") && !m.path("content").asText("").isBlank() && !m.has("tool_calls")) b.append("<div class=\"say lib\"><b>The Librarian</b>").append(prose(m.path("content").asText(""))).append("</div>");
            else if (role.equals("tool")) b.append("<p class=\"k tool\">looked up: ").append(esc(m.path("name").asText("a tool"))).append("</p>");
        }
        return b.append("</div>").toString();
    }

    /**
     * The chat as a panel beside every other page (GET /chat/panel, and the answer to a turn the panel sent): the latest
     * conversation's last turns, the runs it follows, a waiting question's two buttons, and the box to say the next thing.
     */
    static String chatPanel(LibraryStore store, LibraryProtocol lp, Patrons.Patron patron, String note) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        Librarian.Session s = Librarian.Session.latest(store, patron);
        StringBuilder b = new StringBuilder();
        if (!note.isEmpty()) b.append("<p class=\"k note\">").append(esc(note)).append("</p>");
        if (s == null) return b.append("<p class=\"k\">").append(esc(CHAT_SIGN_IN)).append(" <a href=\"/login\">Sign in</a></p>").toString();
        for (String jobId : s.watched()) {
            try { RunProgress.View v = RunProgress.of(lp.job(args(patron).put("job_id", jobId)).path("job"), System.currentTimeMillis(), RunProgress.typicalSeconds(lp, args(patron).path("patron"))); if (!v.active()) s.told(jobId, v); } catch (Exception ignored) { }
        }
        b.append("<div class=\"panel-scroll\">").append(transcript(s, 16)).append("</div>");
        boolean watching = false;
        if (!s.watched().isEmpty()) { String strip = runsStrip(lp, patron, s); watching = strip.contains("elapsed"); b.append("<div class=\"runs\" data-watching=\"").append(watching ? "1" : "0").append("\">").append(strip).append("</div>"); }
        if (!Librarian.stillWaiting(store, s).isEmpty()) {
            b.append("<p class=\"k\">The Librarian asked a question and waits for an answer:</p><p>")
             .append("<form class=\"inline panel-say\" method=\"post\" action=\"/chat\"><input type=\"hidden\" name=\"session\" value=\"").append(esc(s.id)).append("\"><input type=\"hidden\" name=\"say\" value=\"y\"><button>Yes</button></form> ")
             .append("<form class=\"inline panel-say\" method=\"post\" action=\"/chat\"><input type=\"hidden\" name=\"session\" value=\"").append(esc(s.id)).append("\"><input type=\"hidden\" name=\"say\" value=\"n\"><button class=\"quiet\">No</button></form></p>");
        }
        b.append("<form id=\"panel-say\" class=\"panel-say\" method=\"post\" action=\"/chat\"><input type=\"hidden\" name=\"session\" value=\"").append(esc(s.id)).append("\">")
         .append("<input name=\"say\" placeholder=\"Ask the Librarian…\" required autocomplete=\"off\"><button>Send</button></form>")
         .append("<p class=\"k\">Session <code>").append(esc(s.id)).append("</code> · <a href=\"/chat?new=1\">new conversation</a> · <a href=\"/chat\">the whole conversation</a></p>");
        return b.toString();
    }

    /** The panel's frame on every page but the Chat page itself: a button, the panel, and the script that fills and drives it. */
    static String chatPanelMount(String title) {
        if ("Chat".equals(title)) return "";
        return "<button id=\"lib-chat-toggle\" class=\"chat-toggle\" type=\"button\" aria-controls=\"lib-chat\" title=\"Talk to the Librarian\">Chat</button>"
             + "<aside id=\"lib-chat\" class=\"chat-panel\" hidden><div id=\"lib-chat-resize\" class=\"chat-resize\" title=\"Drag to resize\"></div><div class=\"chat-panel-head\"><b>The Librarian</b><a href=\"/chat\" class=\"k\">the whole conversation</a><button type=\"button\" class=\"quiet\" id=\"lib-chat-close\" title=\"Close\">×</button></div>"
             + "<div id=\"lib-chat-body\" class=\"chat-panel-body\"><p class=\"k\">Loading…</p></div></aside>"
             + "<script>" + CHAT_PANEL_JS + "</script>";
    }

    /**
     * The panel's script. Its content comes from the server whole, but the box to say the next thing is kept: the content is rebuilt
     * around the box already there, so what is typed in it, its caret and its focus stay through a reply and through the refresh the
     * panel does every half minute while a run is going; only the conversation's id is taken from the new box. The buttons are held
     * while the model works, the box is not. The draft is kept by the browser for this tab, under the same key as the Chat page's (2026-10-12).
     */
    static final String CHAT_PANEL_JS = "(function(){var panel=document.getElementById('lib-chat'),body=document.getElementById('lib-chat-body'),tog=document.getElementById('lib-chat-toggle'),close=document.getElementById('lib-chat-close');if(!panel||!body||!tog)return;"
            + "var key='rz.chat.open',dkey='rz.chat.draft',mq=window.matchMedia('(min-width: 1100px)'),busy=false,timer=null;function wide(){return mq.matches}function down(){var p=body.querySelector('.panel-scroll')||body;p.scrollTop=p.scrollHeight}"
            + "function lay(){document.body.classList.toggle('chat-open',!panel.hidden&&wide())}"
            + "function setOpen(o){panel.hidden=!o;lay();try{localStorage.setItem(key,o?'1':'0')}catch(e){}if(o)load()}"
            + "function say(){return document.getElementById('panel-say')}"
            + "function show(h){var old=say(),t=document.createElement('div');t.innerHTML=h;var nw=t.querySelector('#panel-say');"
            + "if(!old||!nw){body.innerHTML=h}else{var ks=Array.prototype.slice.call(body.childNodes);for(var i=0;i<ks.length;i++){if(ks[i]!==old)body.removeChild(ks[i])}"
            + "var ns=Array.prototype.slice.call(t.childNodes),before=true;for(var j=0;j<ns.length;j++){if(ns[j]===nw){before=false;continue}if(before)body.insertBefore(ns[j],old);else body.appendChild(ns[j])}"
            + "var sid=nw.querySelector('input[name=session]'),osid=old.querySelector('input[name=session]');if(sid&&osid)osid.value=sid.value}"
            + "var inp=say()?say().querySelector('input[name=say]'):null;if(inp&&!inp.dataset.kept){inp.dataset.kept='1';try{var d0=sessionStorage.getItem(dkey);if(d0&&!inp.value)inp.value=d0}catch(e){}inp.addEventListener('input',function(){try{sessionStorage.setItem(dkey,inp.value)}catch(e){}})}"
            + "wire();down()}"
            + "function load(){fetch('/chat/panel',{credentials:'same-origin'}).then(function(r){return r.text()}).then(show).catch(function(){if(!say())body.innerHTML='<p class=\"k\">The library did not answer.</p>'})}"
            + "function wire(){var forms=body.querySelectorAll('form.panel-say');for(var i=0;i<forms.length;i++){forms[i].addEventListener('submit',send)}"
            + "if(timer)clearTimeout(timer);timer=null;if(body.querySelector('[data-watching=\"1\"]')){timer=setTimeout(function(){if(!panel.hidden&&!busy)load()},30000)}}"
            + "function hold(on){var bs=body.querySelectorAll('button');for(var i=0;i<bs.length;i++){bs[i].disabled=on}}"
            + "function send(ev){ev.preventDefault();if(busy)return;var f=ev.target,src=f.querySelector('input[name=say]'),words=(src.value||'').trim();if(!words)return;"
            + "var fd=new URLSearchParams(new FormData(f));fd.append('panel','1');busy=true;hold(true);"
            + "var main=say(),inp=main?main.querySelector('input[name=say]'):null;if(src===inp){inp.value='';try{sessionStorage.removeItem(dkey)}catch(e){}}"
            + "var t=body.querySelector('.chat');if(t&&src.type!=='hidden'){var w=document.createElement('div');w.className='say me';w.innerHTML='<b>You</b><p></p>';w.querySelector('p').textContent=words;t.appendChild(w)}"
            + "var th=document.createElement('p');th.className='k thinking';th.textContent='The Librarian is thinking\u2026 this can take a while; the page stays usable, and you can type the next thing.';(body.querySelector('.panel-scroll')||body).appendChild(th);down();"
            + "fetch('/chat',{method:'POST',body:fd,credentials:'same-origin',headers:{'Content-Type':'application/x-www-form-urlencoded'}}).then(function(r){return r.text()})"
            + ".then(function(h){var had=document.activeElement;busy=false;show(h);hold(false);var i2=say()?say().querySelector('input[name=say]'):null;if(i2&&(had===document.body||(had&&had.form===f)))i2.focus()})"
            + ".catch(function(){th.textContent='The reply did not arrive here; the conversation keeps it. Close and reopen the panel in a moment.';busy=false;hold(false)})}"
            + "tog.addEventListener('click',function(){setOpen(panel.hidden)});if(close)close.addEventListener('click',function(){setOpen(false)});"
            + "var wkey='rz.chat.width',grip=document.getElementById('lib-chat-resize');function setW(px){px=Math.max(260,Math.min(px,Math.round(window.innerWidth*0.6)));document.body.style.setProperty('--chatw',px+'px');return px}"
            + "var w0=null;try{w0=parseInt(localStorage.getItem(wkey),10)}catch(e){}if(w0>0)setW(w0);"
            + "if(grip){grip.addEventListener('pointerdown',function(ev){if(!wide())return;ev.preventDefault();grip.setPointerCapture(ev.pointerId);document.body.classList.add('chat-resizing');"
            + "function mv(e){var px=setW(window.innerWidth-e.clientX)}function up(e){grip.removeEventListener('pointermove',mv);grip.removeEventListener('pointerup',up);document.body.classList.remove('chat-resizing');try{localStorage.setItem(wkey,String(parseInt(getComputedStyle(document.body).getPropertyValue('--chatw'),10)||''))}catch(x){}}"
            + "grip.addEventListener('pointermove',mv);grip.addEventListener('pointerup',up)})}"
            + "var open=null;try{open=localStorage.getItem(key)}catch(e){}if(open===null?wide():open==='1')setOpen(true);"
            + "if(mq.addEventListener)mq.addEventListener('change',lay);else if(mq.addListener)mq.addListener(lay)})();";

    /** The research runs a conversation follows, one line each: stage, time, percent, a bar; a finished one links to its report. */
    static String runsStrip(LibraryProtocol lp, Patrons.Patron patron, Librarian.Session s) {
        StringBuilder b = new StringBuilder();
        for (String jobId : s.watched()) {
            try {
                RunProgress.View v = RunProgress.of(lp.job(args(patron).put("job_id", jobId)).path("job"), System.currentTimeMillis(), RunProgress.typicalSeconds(lp, args(patron).path("patron")));
                b.append("<p class=\"k run\"><a href=\"/jobs/").append(enc(jobId)).append("\">").append(esc(jobId)).append("</a> ");
                if (v.active()) b.append(esc(v.stage())).append(" · ").append(esc(RunProgress.elapsed(v.elapsedSeconds()))).append(" elapsed · about ").append(v.percent()).append("% done <progress max=\"100\" value=\"").append(v.percent()).append("\"></progress>");
                else b.append(esc(v.stage())).append(" after ").append(esc(RunProgress.elapsed(v.elapsedSeconds()))).append(v.report().isEmpty() ? "" : ". " + idLink(v.report(), "Read the report")).append(". <a href=\"/chat?session=").append(enc(s.id)).append("\">Show it in the chat</a>");
                b.append("</p>");
            } catch (Exception e) { b.append("<p class=\"k run\">").append(esc(jobId)).append(": ").append(esc(String.valueOf(e.getMessage()))).append("</p>"); }
        }
        return b.toString();
    }

    /** GET /chat/runs?session=…: the strip alone, for the page's own refresh. */
    static String chatRuns(LibraryStore store, LibraryProtocol lp, Patrons.Patron patron, String sessionId) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        Librarian.Session s = sessionId.isBlank() ? null : Librarian.Session.resume(store, sessionId, patron);
        return s == null ? "" : runsStrip(lp, patron, s);
    }

    /** One turn from the form: the words go to the Librarian, the reply lands in the session, the page shows it. */
    static void chatPost(LibrarianDaemon d, LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        String words = form.getOrDefault("say", "").strip();
        if (words.isEmpty()) return;
        Patrons.check(store, patron, Patrons.Level.read);
        Librarian.Session s = chatSession(store, patron, form);
        if (s == null) throw ProtocolError.forbidden(CHAT_SIGN_IN);
        // /model <name>: this session talks to that named model from now on; /model alone lists them. Answered by the library, not the model.
        if (words.equals("/model") || words.startsWith("/model ")) {
            String name = words.length() > 6 ? words.substring(6).strip() : "";
            String answer;
            if (name.isEmpty()) { StringBuilder b = new StringBuilder("The models this library can talk to" + (s.model().isEmpty() ? "" : " (this chat uses " + s.model() + ")") + ":"); for (ModelEntries.Entry e : ModelEntries.all().values()) b.append("\n").append(e.line()); answer = b.append("\n/model <name> switches this chat to one.").toString(); }
            else if (name.equalsIgnoreCase(Auto.NAME)) { s.model(Auto.NAME); answer = Auto.choose(store, Auto.Job.chat()).why() + " — this chat uses best judgement from now on."; }
            else { ModelEntries.Entry e = ModelEntries.get(name); if (e == null) answer = "No model is named " + name + ". /model lists them (or auto)."; else { s.model(e.name()); answer = "This chat now uses " + e.line() + "."; } }
            ObjectNode said = M.createObjectNode(); said.put("role", "user"); said.put("content", words); s.append(said);
            ObjectNode reply = M.createObjectNode(); reply.put("role", "assistant"); reply.put("content", answer); s.append(reply);
            return;
        }
        if (words.equals("/interview") || words.startsWith("/interview ")) {   // the library asks what it does not know of a subject; the answers go through the model's library_tell
            String answer = Interview.questions(store, words.length() > 10 ? words.substring(10) : "");
            ObjectNode said = M.createObjectNode(); said.put("role", "user"); said.put("content", words); s.append(said);
            ObjectNode reply = M.createObjectNode(); reply.put("role", "assistant"); reply.put("content", answer); s.append(reply);
            return;
        }
        if (words.equals("/suggest")) {   // what the library suggests next: answered by the library, not the model
            String answer = Suggestions.text(Suggestions.refresh(store, null), Suggestions.all(store));
            ObjectNode said = M.createObjectNode(); said.put("role", "user"); said.put("content", words); s.append(said);
            ObjectNode reply = M.createObjectNode(); reply.put("role", "assistant"); reply.put("content", answer); s.append(reply);
            return;
        }
        new Librarian(store, d.chatDrive(s), patron, s).say(words);
    }

    /** Paragraphs from the Librarian's plain text, ids linked. */
    static String prose(String text) {
        StringBuilder b = new StringBuilder();
        for (String para : text.split("\\n\\s*\\n")) {
            String e = esc(para.strip()).replaceAll("\\[((?:F|I|D)-\\d{4}-[a-z0-9-]+)\\]", "[<a href=\"/entry/$1\">$1</a>]").replace("\n", "<br>");
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
        StringBuilder b = new StringBuilder("<details open class=\"deeper\"><summary><b>Go deeper</b></summary>");
        b.append("<p class=\"k\">A new research run that starts from this report. The run is given this report first, so it builds on what is here instead of reading it all again.</p>");
        if (e.path("open_questions").size() > 0) {
            b.append("<p>Questions this report left open. Each button sends that question as an in-depth run:</p>");
            for (JsonNode q : e.path("open_questions")) {
                b.append("<form method=\"post\" action=\"/research\" class=\"inline\"><input type=\"hidden\" name=\"question\" value=\"").append(esc("Follow-up to " + id + " (" + title + "): " + q.asText())).append("\">")
                 .append("<input type=\"hidden\" name=\"mode\" value=\"depth\"><input type=\"hidden\" name=\"sources\" value=\"both\">").append(nonceField())
                 .append("<button class=\"quiet\">Send</button> ").append(esc(q.asText())).append("</form>");
            }
        }
        b.append("<form method=\"post\" action=\"/research\" class=\"stack\"><label>Your own follow-up question about this report<br><textarea name=\"question\" rows=\"3\" placeholder=\"for example: which of these practices have been tested in a small team, and what did the tests find?\"></textarea></label>")
         .append("<input type=\"hidden\" name=\"follow_up_to\" value=\"").append(esc(id + " (" + title + ")")).append("\"><input type=\"hidden\" name=\"mode\" value=\"depth\"><input type=\"hidden\" name=\"sources\" value=\"both\">").append(nonceField())
         .append("<button>Send as an in-depth run</button> <a class=\"k\" href=\"/research?q=").append(enc("Follow-up to " + id + " (" + title + "): ")).append("\">or open the full research form</a></form></details>");
        return b.toString();
    }

    /** {@code refresh} > 0 makes the page reload itself after that many seconds — at {@code to} when given, else in place. */
    static String page(LibraryStore store, Patrons.Patron patron, String title, String body, int refresh, String to) throws IOException { return page(store, patron, title, body, refresh, to, false); }

    /** {@code wide}: the page uses the whole window (the map), not the reading column. */
    static String page(LibraryStore store, Patrons.Patron patron, String title, String body, int refresh, String to, boolean wide) throws IOException {
        String name = store.identity().name();
        String meta = refresh > 0 ? "<meta http-equiv=\"refresh\" content=\"" + refresh + (to == null ? "" : ";url=" + esc(to)) + "\">" : "";
        String who = patron.web() && !WebAccess.signInRequired() ? "open to everyone · <a href=\"/login\">sign in</a>"
                : patron.web() || patron.anonymous() ? "<a href=\"/login\">sign in</a>"
                : esc(patron.person() && "the person".equals(patron.name()) ? "you" : patron.name().isEmpty() ? patron.did() : patron.name()) + " · <a href=\"/account\">account</a> · <a href=\"/logout\">sign out</a>";
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc((title == null ? name : title + " — " + name) + " · ResearchZosho") + "</title>" + meta + "<link rel=\"icon\" href=\"/favicon.ico\" type=\"image/png\"><script>(function(){var t=null;try{t=localStorage.getItem('rz.theme')}catch(e){}if(t==='light')document.documentElement.setAttribute('data-theme','light')})();</script><style>" + CSS + "</style></head><body" + ("Chat".equals(title) ? " class=\"fill\"" : "") + ">"
                + "<header><button id=\"nav-toggle\" class=\"nav-toggle\" type=\"button\" aria-controls=\"site-nav\" title=\"Menu\">&#9776;</button>"
                + "<a class=\"home\" href=\"/\"><img src=\"/favicon.ico\" alt=\"\"> <span class=\"brand\">ResearchZosho</span><span class=\"ver\">" + esc(Version.number() == null ? "dev" : Version.number()) + "</span><span class=\"lib\">" + esc(name) + "</span></a>"
                + "<span class=\"who\">" + who + "</span></header>"
                + "<nav id=\"site-nav\" class=\"site-nav\">" + siteNav(store, patron) + "</nav><script>" + NAV_JS + "</script><main" + (wide ? " class=\"wide\"" : "") + ">"
                + (title == null ? "" : "<h1>" + esc(title) + "</h1>") + body + "</main>" + chatPanelMount(title)
                + "<footer class=\"k\">ResearchZosho " + esc(Version.string()) + " · <a href=\"https://researchzosho.org\">researchzosho.org</a>"
                + (Version.updateNotice().isEmpty() ? "" : " · <b>" + esc(Version.latestCached()) + " is available</b>: update with the install command on <a href=\"https://researchzosho.org/#install\">researchzosho.org</a>; your library and settings stay")
                + "</footer></body></html>";
    }

    static final String CSS =
            ":root{--bg:#12161d;--ink:#f3ede1;--k:#9aa3b2;--accent:#e0654f;--line:#2a3140;--card:#1b2130}"   // dark by default; the light palette is behind the switch in the menu
            + ":root[data-theme=light]{--bg:#f7f3ea;--ink:#1e1b16;--k:#6b6459;--accent:#c3402f;--line:#e2dccd;--card:#fffdf7}"
            + "body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 Georgia,'Noto Serif',serif}"
            + "header{display:flex;flex-wrap:wrap;align-items:center;gap:.6em 1.2em;padding:.7em 1.2em;border-bottom:1px solid var(--line)}"
            + "header .home{text-decoration:none;color:var(--ink);display:flex;align-items:center;gap:.5em}header .home img{height:28px}header .brand{font-weight:bold}header .ver{color:var(--k);font-size:.8em;border:1px solid var(--line);border-radius:.4em;padding:0 .35em}header .lib{color:var(--k);font-size:.92em}header .lib::before{content:'·';margin:0 .5em}"
            + "nav a{margin-right:1em;color:var(--ink)}.who{margin-left:auto;color:var(--k);font-size:.9em}"
            + "main{max-width:52em;margin:0 auto;padding:1em 1.2em 3em}main.wide{max-width:none}footer{text-align:center;padding:2em;border-top:1px solid var(--line)}"
            + "a{color:var(--accent)}h1{font-size:1.6em;line-height:1.2}h2{font-size:1.15em;margin-top:1.6em;border-bottom:1px solid var(--line)}h2 a.k{font-weight:normal;font-size:.8em;margin-left:.8em}"
            + ".k{color:var(--k);font-size:.92em}.err{color:var(--accent)}"
            + ".chat .say{margin:.9em 0;padding:.6em .9em;border:1px solid var(--line);border-radius:6px;background:var(--card)}.chat .say.me{border-color:transparent;background:transparent;padding-left:0}.chat .say b{display:block;font-size:.85em;color:var(--k);margin-bottom:.2em}.chat .tool{margin:.2em 0 .2em .9em;font-size:.85em}"
            + "form.big{display:flex;gap:.5em;align-items:center;margin:1em 0}form.big input{flex:1;font:inherit;padding:.5em .7em;border:1px solid var(--line);background:var(--card);color:var(--ink);min-width:12em}"
            + "button{font:inherit;padding:.5em 1em;background:var(--accent);color:#fff;border:0;cursor:pointer}button.quiet{background:var(--card);color:var(--ink);border:1px solid var(--line)}"
            + "form.stack label{display:block;margin:.8em 0}form.stack textarea,form.stack input,form.stack select{font:inherit;padding:.4em;border:1px solid var(--line);background:var(--card);color:var(--ink);width:100%;max-width:40em;box-sizing:border-box}"
            + "form.pick ul{list-style:none;padding-left:0}form.pick ul ul{padding-left:1.6em}form.pick h3.group{margin:1em 0 .2em;font-size:1.02em}form.pick details{margin:.2em 0 .2em 1.6em}form.pick summary{cursor:pointer}form.pick li{margin:.35em 0}form.pick label{display:block}form.pick .bar{display:flex;gap:.7em;align-items:center;flex-wrap:wrap;margin:.8em 0;position:sticky;top:0;background:var(--bg);padding:.4em 0}"
            + "form.stack input[size],form.stack input[type=checkbox]{width:auto}form.stack label.choice{display:block}ul.tight{padding-left:1.2em}ul.tight li{margin:.2em 0}ol.hits li{margin:.7em 0}.snip{color:var(--k)}"
            + ".card{background:var(--card);border:1px solid var(--line);padding:.6em 1em;margin:.8em 0}.card h3{margin:.2em 0;font-size:1.05em}.card p{margin:.3em 0}"
            + ".badge{display:inline-block;padding:0 .5em;border-radius:.6em;font-size:.8em;background:var(--line)}.badge.accepted{background:#3f8a4f;color:#fff}.badge.disputed,.badge.failed{background:var(--accent);color:#fff}.badge.running{background:#2e6fb0;color:#fff}"
            + "pre{white-space:pre-wrap;word-break:break-word;background:var(--card);border:1px solid var(--line);padding:.8em;font-size:.9em}pre.raw{max-height:70vh;overflow:auto}"
            + ".body{margin:1em 0}.triple{font-style:italic}code{font-size:.92em}"
            + ".notice{background:var(--card);border:1px solid var(--line);border-left:4px solid var(--accent);padding:.6em 1em}"
            + ".working{display:flex;gap:1em;align-items:flex-start;margin:1.5em 0}.spin{flex:none;width:22px;height:22px;margin-top:1em;border:3px solid var(--line);border-top-color:var(--accent);border-radius:50%;animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}"
            + ".body{word-break:break-word}p.src{margin:.3em 0}progress{width:10em;height:.7em;vertical-align:middle;accent-color:var(--accent)}p.run{margin:.3em 0}"
            + "p.ref{margin:.3em 0;padding-left:2.2em;text-indent:-2.2em;word-break:break-word}p.ref b{color:var(--k)}"
            + ".chat-toggle{position:fixed;right:1.2em;bottom:1.2em;z-index:20;border-radius:2em;padding:.55em 1.2em;background:var(--accent);color:#fff;border:0;font:inherit;font-weight:bold;box-shadow:0 2px 8px rgba(0,0,0,.25);cursor:pointer}"
            + ".chat-panel{position:fixed;top:0;right:0;bottom:0;width:min(28em,100vw);z-index:19;background:var(--bg);border-left:1px solid var(--line);display:flex;flex-direction:column;box-shadow:-4px 0 16px rgba(0,0,0,.15)}.chat-panel[hidden]{display:none}"
            + ".chat-panel-head{display:flex;align-items:center;gap:.8em;padding:.6em .9em;border-bottom:1px solid var(--line)}.chat-panel-head b{flex:1}.chat-panel-head button{font-size:1.1em;line-height:1;padding:.1em .5em}"
            + ".chat-page{flex:1;min-height:0;display:flex;flex-direction:column}.chat-scroll{flex:1;min-height:6em;overflow:auto;border:1px solid var(--line);border-radius:.4em;padding:.4em .8em;background:var(--bg)}.chat-page form.big{background:var(--bg);padding:.6em 0;margin:0}.chat-view{flex:1;min-height:0;display:flex;flex-direction:column}.chat-page p.thinking{font-style:italic}body.fill{height:100vh;height:100dvh;display:flex;flex-direction:column}body.fill>header,body.fill>footer{flex:none}body.fill>main{flex:1;min-height:0;display:flex;flex-direction:column;width:100%;box-sizing:border-box;padding-bottom:1em}"
            + ".chat-panel-body{flex:1;display:flex;flex-direction:column;min-height:0;padding:.6em .9em;font-size:.95em}.chat-panel-body .panel-scroll{flex:1;min-height:0;overflow:auto}.chat-panel-body .chat{margin:0}.chat-panel-body form.panel-say{display:flex;gap:.4em;margin:.6em 0}.chat-panel-body form.panel-say.inline{display:inline-flex;margin:0 .3em 0 0}.chat-panel-body form.panel-say input[name=say]{flex:1;min-width:0}.chat-panel-body p.thinking{font-style:italic}"
            + ".nav-toggle{font:inherit;font-size:1.25em;line-height:1;padding:.15em .5em;background:none;border:1px solid var(--line);border-radius:.3em;color:var(--ink);cursor:pointer}"
            + ".site-nav{display:none;padding:.8em 1.2em;border-bottom:1px solid var(--line)}body.nav-open .site-nav{display:block}"
            + ".nav-group{margin:0 0 .9em}.nav-group b{display:block;font-size:.78em;color:var(--k);text-transform:uppercase;letter-spacing:.06em;margin:0 0 .25em}.nav-group a{display:flex;align-items:center;gap:.5em;padding:.15em 0;color:var(--ink);text-decoration:none}.nav-group a:hover{text-decoration:underline}"
            + ".nav-group .ico{display:inline-block;width:1.5em;text-align:center;flex:none;color:var(--k)}.theme-toggle{display:flex;align-items:center;gap:.5em;padding:.15em 0;background:none;border:0;color:var(--ink);font:inherit;cursor:pointer}.theme-toggle:hover .lbl{text-decoration:underline}body.nav-rail .theme-toggle{justify-content:center;padding:.3em 0;font-size:1.15em;width:100%}.nav-look{margin-top:1.4em}"
            + "body.nav-rail .site-nav{display:block;padding:.8em .4em}body.nav-rail .nav-group b,body.nav-rail .nav-group .lbl{display:none}body.nav-rail .nav-group a{justify-content:center;padding:.3em 0;font-size:1.15em}body.nav-rail .nav-group .ico{width:auto;overflow:hidden;max-width:2em;white-space:nowrap}"
            + ".chat-resize{display:none;position:absolute;left:0;top:0;bottom:0;width:7px;cursor:col-resize;z-index:2}.chat-resize:hover{background:var(--line)}body.chat-resizing{user-select:none;cursor:col-resize}"
            + "@media(max-width:1099px){body.nav-open .site-nav{position:fixed;top:0;left:0;bottom:0;width:min(18em,85vw);z-index:21;background:var(--bg);overflow:auto;box-shadow:4px 0 16px rgba(0,0,0,.15)}}"
            + "@media(min-width:1100px){body{--navw:0px;--chatw:28em;display:grid;grid-template-columns:var(--navw) minmax(0,1fr) 0;grid-template-rows:auto 1fr auto;min-height:100vh}body.nav-open{--navw:15em}body.nav-rail{--navw:3.4em}body.chat-open{grid-template-columns:var(--navw) minmax(0,1fr) var(--chatw)}body.chat-open .chat-panel{position:relative}body.chat-open .chat-resize{display:block}"
            + "body>header,body>footer{grid-column:1/-1}.site-nav{grid-column:1;grid-row:2;position:sticky;top:0;align-self:start;max-height:100vh;overflow:auto;border-bottom:0;border-right:1px solid var(--line)}body>main{grid-column:2;grid-row:2;min-width:0}"
            + "body.chat-open .chat-panel{position:sticky;top:0;grid-column:3;grid-row:2;height:100vh;width:auto;box-shadow:none}body.chat-open .chat-toggle{display:none}body.fill{display:grid;grid-template-rows:auto minmax(0,1fr) auto;height:100vh;height:100dvh}body.fill .site-nav{align-self:stretch;max-height:none}}"
            + ".tablewrap{overflow-x:auto;margin:1em 0}table{border-collapse:collapse;font-size:.92em;min-width:100%}th,td{border:1px solid var(--line);padding:.4em .6em;vertical-align:top;text-align:left}th{background:var(--card)}"
            + ".reading p{margin:.8em 0}a.cite{font-size:.8em;text-decoration:none;color:var(--k)}.mark{font-size:.8em;padding:0 .4em;border-radius:.5em;background:var(--line)}.mark.bad{background:var(--accent);color:#fff}form.inline{display:inline}";

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

    /**
     * The sign-in cookie: Secure on the shared door, Lax everywhere — with Strict, a join link clicked in a message signed the browser in
     * and then showed the home page as a visitor, because the cookie is withheld on the redirect that follows a cross-site click.
     */
    static void setCookie(HttpExchange x, String token) { x.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; Path=/; HttpOnly; SameSite=Lax" + (onDoor(x) ? "; Secure" : "") + "; Max-Age=31536000"); }
    static void clearCookie(HttpExchange x) { x.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Lax" + (onDoor(x) ? "; Secure" : "") + "; Max-Age=0"); }
    static void clearAsk(HttpExchange x) { x.getResponseHeaders().add("Set-Cookie", ASK + "=; Path=/; HttpOnly; SameSite=Lax" + (onDoor(x) ? "; Secure" : "") + "; Max-Age=0"); }
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
        // no scripts, except: the map (its own script, its own data), the runs strip, the chat panel beside a page, and a pick form (the "all" and group boxes tick the others
        // with an inline handler; with scripts blocked they ticked nothing, 2026-09-09)
        x.getResponseHeaders().set("Content-Security-Policy", html.contains("id=\"c\"></canvas>") || html.contains("id=\"runs-strip\"") || html.contains("id=\"lib-chat\"") || html.contains("id=\"chat-scroll\"") || html.contains("id=\"site-nav\"") || html.contains("id=\"signin\"") || html.contains("id=\"passkey-add\"")
                ? "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'"
                : html.contains("class=\"pick\"")
                ? "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'"
                : "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; form-action 'self'");
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

    /** The menu's links to the pages of the fields whose work the library holds. */
    /** The site's menu, grouped: the library, the work, what is yours, and each field's own pages. */
    static String siteNav(LibraryStore store, Patrons.Patron patron) {
        boolean yours = patron.person() || (patron.web() && !WebAccess.signInRequired());
        StringBuilder b = new StringBuilder();
        b.append(navGroup("Library", navLink("/", "Home", "⌂") + navLink("/ask", "Ask", "?") + navLink("/search", "Search", "⌕") + navLink("/subjects", "Subjects", "☷") + navLink("/map", "Map", "⊞") + navLink("/dossier", "Dossiers", "▤")));   // the dossiers are the library's own front door to its people and organizations, not a field's page
        b.append(navGroup("Work", navLink("/inbox", "Inbox", "✉") + navLink("/questions", "Open questions", "⁇") + navLink("/jobs", "Runs", "▶") + navLink("/research", "Research", "⚗") + navLink("/suggest", "Suggested", "✦") + navLink("/changes", "Changes", "↻")));
        b.append(navGroup("Yours", navLink("/chat", "Chat", "❝") + (yours ? navLink("/owned", "Owned", "◆") + navLink("/settings", "Settings", "⚙") : "") + (SignIn.hasAccountPage(patron) ? navLink("/account", "Account", "☺") : "")));
        String fields = fieldMenu(store, patron);
        b.append("<div class=\"nav-group nav-look\"><b>Look</b><button type=\"button\" id=\"theme-toggle\" class=\"theme-toggle\" title=\"Light or dark\"><span class=\"ico\">☾</span><span class=\"lbl\">Dark</span></button></div>");
        if (!fields.isEmpty()) b.append(navGroup("Fields", fields));
        return b.toString();
    }

    static String navLink(String href, String label, String symbol) { return "<a href=\"" + href + "\" title=\"" + esc(label) + "\"><span class=\"ico\">" + symbol + "</span><span class=\"lbl\">" + esc(label) + "</span></a>"; }

    static String navGroup(String name, String links) { return "<div class=\"nav-group\"><b>" + name + "</b>" + links + "</div>"; }

    /** The menu opens and closes from its button; open by default on a wide window, closed on a narrow one, remembered per browser. */
    static final String NAV_JS = "(function(){var n=document.getElementById('site-nav'),t=document.getElementById('nav-toggle');if(!n||!t)return;var key='rz.nav',mq=window.matchMedia('(min-width: 1100px)');"
            + "function apply(st){document.body.classList.toggle('nav-open',st==='open');document.body.classList.toggle('nav-rail',st==='rail');t.title=st==='open'?'Shrink the menu to symbols':st==='rail'?'Show the menu':'Open the menu';try{localStorage.setItem(key,st)}catch(e){}}"
            + "var st=null;try{st=localStorage.getItem(key)}catch(e){}if(st!=='open'&&st!=='rail'&&st!=='hidden')st=mq.matches?'open':'hidden';if(!mq.matches&&st==='rail')st='hidden';apply(st);"
            + "t.addEventListener('click',function(){var cur=document.body.classList.contains('nav-open')?'open':document.body.classList.contains('nav-rail')?'rail':'hidden';"
            + "apply(mq.matches?(cur==='open'?'rail':'open'):(cur==='hidden'?'open':'hidden'))});"
            + "var th=document.getElementById('theme-toggle');function look(){var light=document.documentElement.getAttribute('data-theme')==='light';if(th){th.querySelector('.ico').textContent=light?'\\u2600':'\\u263E';th.querySelector('.lbl').textContent=light?'Light':'Dark'}}look();"
            + "if(th)th.addEventListener('click',function(){var light=document.documentElement.getAttribute('data-theme')==='light';if(light)document.documentElement.removeAttribute('data-theme');else document.documentElement.setAttribute('data-theme','light');try{localStorage.setItem('rz.theme',light?'dark':'light')}catch(e){}look()})})();";

    static String fieldMenu(LibraryStore store, Patrons.Patron patron) {
        StringBuilder b = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();   // two fields may lead to one page: once
        for (Profile f : Librarian.chatFields(store)) {
            for (Profile.Link l : f.menu()) if ((!l.writersOnly() || mayWrite(store, patron)) && seen.add(l.href())) b.append(navLink(l.href(), l.label(), l.symbol()));   // a glyph of its own, like the core's entries
        }
        return b.toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20"); }
}
