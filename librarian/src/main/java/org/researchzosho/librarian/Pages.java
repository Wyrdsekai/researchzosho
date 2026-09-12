package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private Pages() { }

    static boolean isPage(String path) {
        return path.equals("/") || path.equals("/search") || path.equals("/ask") || path.startsWith("/entry/") || path.equals("/read")
                || path.equals("/subjects") || path.equals("/changes") || path.equals("/questions") || path.equals("/inbox") || path.equals("/jobs") || path.startsWith("/jobs/")
                || path.equals("/research") || path.equals("/login") || path.equals("/logout") || path.equals("/explain") || path.equals("/download") || path.equals("/map");
    }

    /** One page. {@code form} holds the POSTed fields (empty on GET); {@code patron} is who the cookie or header proved. */
    static void handle(HttpExchange x, LibrarianDaemon d, LibraryStore store, Patrons.Patron patron, Map<String, String> q, Map<String, String> form) throws IOException {
        String path = x.getRequestURI().getPath();
        String method = x.getRequestMethod();
        LibraryProtocol p = new LibraryProtocol(store);
        try {
            switch (path) {
                case "/" -> send(x, 200, home(d, store, p, patron));
                case "/search" -> send(x, 200, search(store, p, patron, q));
                case "/ask" -> send(x, 200, ask(store, p, patron, q));
                case "/read" -> send(x, 200, read(store, p, patron, q));
                case "/subjects" -> send(x, 200, subjects(store, p, patron));
                case "/changes" -> send(x, 200, changes(store, p, patron, q));
                case "/questions" -> { if ("POST".equals(method)) { String to = questionsPost(d, store, p, patron, form); redirect(x, to); } else send(x, 200, questions(store, p, patron, q)); }
                case "/inbox" -> { if ("POST".equals(method)) { inboxPost(store, patron, form); redirect(x, "/inbox"); } else send(x, 200, inbox(store, p, patron, q)); }
                case "/jobs" -> { if ("POST".equals(method)) { jobsPost(p, patron, form); redirect(x, "/jobs"); } else send(x, 200, jobs(store, p, patron)); }
                case "/explain" -> send(x, 200, explain(store, p, patron, q));
                case "/download" -> download(x, store, patron, q);
                case "/map" -> { Patrons.check(store, patron, Patrons.Level.read); send(x, 200, page(store, patron, "Map", MapPage.body(q.getOrDefault("focus", "")), 0, null, true)); }
                case "/research" -> {
                    if ("POST".equals(method) && "1".equals(form.get("sharpen"))) send(x, 200, sharpenStart(store, patron, form.getOrDefault("question", "")));
                    else if ("POST".equals(method)) send(x, 200, researchPost(d, store, patron, form));
                    else if (q.containsKey("sharpen")) send(x, 200, sharpenPoll(store, patron, q.get("sharpen")));
                    else send(x, 200, researchForm(store, patron, q.getOrDefault("q", ""), null));
                }
                case "/login" -> {
                    if ("POST".equals(method)) {
                        Patrons.Entry e = Patrons.resolve(store, form.getOrDefault("token", ""));
                        if (e == null) { send(x, 403, loginPage(store, "That token does not match anyone. Make one with `researchzosho reader token <did>` on the computer where the library lives.")); return; }
                        x.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + form.get("token").strip() + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000");
                        redirect(x, "/");
                    } else send(x, 200, loginPage(store, null));
                }
                case "/logout" -> {
                    x.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
                    redirect(x, "/");
                }
                default -> {
                    if (path.startsWith("/entry/")) send(x, 200, entry(store, p, patron, path.substring("/entry/".length())));
                    else if (path.startsWith("/jobs/")) send(x, 200, job(store, p, patron, path.substring("/jobs/".length()), q));
                    else send(x, 404, page(store, patron, "Not here", "<p>No such page.</p>"));
                }
            }
        } catch (ProtocolError e) {
            int status = LibrarianDaemon.status(e.code);
            String hint = "forbidden".equals(e.code) && patron.anonymous() ? " <a href=\"/login\">Sign in with a token</a> to do this." : "";
            send(x, status, page(store, patron, "forbidden".equals(e.code) ? "Not allowed" : "not_found".equals(e.code) ? "Not found" : "Something went wrong", "<p class=\"err\">" + esc(e.getMessage()) + hint + "</p>"));
        }
    }

    // ---- pages ----

    private static String home(LibrarianDaemon d, LibraryStore store, LibraryProtocol p, Patrons.Patron patron) throws IOException {
        ObjectNode status = p.status(args(patron));
        JsonNode c = status.path("counts");
        StringBuilder b = new StringBuilder();
        b.append("<form class=\"big\" action=\"/ask\"><input name=\"q\" placeholder=\"Ask the library a question…\" autofocus> <button>Ask</button> <a class=\"k\" href=\"/search\">or search the library</a></form>");
        b.append("<p class=\"k\">This library has ").append(c.path("finding").path("total").asInt()).append(" claims (")
         .append(c.path("finding").path("accepted").asInt()).append(" accepted, ").append(c.path("finding").path("disputed").asInt()).append(" disputed, ")
         .append(c.path("finding").path("draft").asInt()).append(" waiting for review), ").append(c.path("investigation").asInt()).append(" write-ups, ")
         .append(c.path("article").asInt()).append(" summaries and ").append(c.path("raw").asInt()).append(" saved documents, on ")
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
        var tail = Changes.tail(store, 8);
        if (!tail.isEmpty()) {
            b.append("<h2>What changed lately <a class=\"k\" href=\"/changes\">all changes</a></h2><ul class=\"tight\">");
            for (int i = tail.size() - 1; i >= 0; i--) b.append("<li>").append(changeLine(tail.get(i))).append("</li>");
            b.append("</ul>");
        }
        // what is open
        List<ObjectNode> open = p.frontierList();
        if (!open.isEmpty()) {
            b.append("<h2>Open questions <a class=\"k\" href=\"/questions\">all ").append(open.size()).append("</a></h2><ul class=\"tight\">");
            for (int i = open.size() - 1; i >= Math.max(0, open.size() - 6); i--) {
                ObjectNode o = open.get(i);
                b.append("<li>").append(withIdLinks(o.path("text").asText())).append(" <a class=\"k\" href=\"/research?q=").append(enc(o.path("text").asText())).append("\">look into this</a></li>");
            }
            b.append("</ul>");
        }
        b.append("<h2>Also</h2><p><a href=\"/map\">The map</a> shows how the people, places and things in this library connect. To open the library in Obsidian or SoloMD, run <code>researchzosho vault</code> and open the folder <code>")
         .append(esc(store.root().getFileName().toString())).append("-vault</code> next to the library.</p>");
        return page(store, patron, null, b.toString());
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
        if (r.path("hits").size() == 0) b.append("<p>Nothing in the library matches. <a href=\"/research?q=").append(enc(query)).append("\">Have it looked into</a>.</p>");
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
            if (r.path("filed_as_demand").asBoolean()) b.append(" The question is now on the open list.");
            b.append(" <a href=\"/research?q=").append(enc(question)).append("\">Have it looked into</a>.</p>");
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

    private static String entry(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, String id) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        if (!LibraryStore.safeName(id)) throw ProtocolError.notFound("entry " + id);
        ObjectNode e = p.entry(id, p.kindOf(id), true);
        if (e == null) throw ProtocolError.notFound("entry " + id);
        String kind = e.path("kind").asText();
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\">").append(esc(kind)).append(" · ").append(badge(e.path("state").asText()));
        if (!"raw".equals(kind)) b.append(" · confidence ").append(esc(e.path("confidence").asText())).append(" · ").append(esc(e.path("claim_type").asText()));
        b.append(" · by ").append(esc(e.path("writer").asText())).append(" · ").append(when(e.path("recorded_at").asText()));
        if (e.hasNonNull("valid_as_of") && !e.path("valid_as_of").asText().isEmpty()) b.append(" · held as of ").append(esc(e.path("valid_as_of").asText()));
        if (e.hasNonNull("review_by")) b.append(" · review by ").append(esc(e.path("review_by").asText()));
        b.append("</p>");
        if (!"raw".equals(kind)) b.append(ladder(id, Explain.Rung.written)).append(downloads(id, null));
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
             .append("<a href=\"/read?locator=").append(enc(e.path("locator").asText())).append("\">Read the whole document</a>.</p>");
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
             .append("supported".equals(v) ? "the source still says it" : "unsupported".equals(v) ? "<span class=\"mark bad\">the source does not say it</span>" : "no-capture".equals(v) ? "no captured copy of the source to read" : "could not tell from the source").append("</p>");
        }
        if (e.hasNonNull("review")) {
            JsonNode rv = e.get("review");
            b.append("<p class=\"k\">Reviewed: ").append(esc(rv.path("decision").asText())).append(" by ").append(esc(rv.path("reviewer").asText())).append(" on ").append(when(rv.path("at").asText()))
             .append(rv.path("stale").asBoolean() ? " (it has changed since then)" : "").append("</p>");
        }
        StringBuilder links = new StringBuilder();
        for (JsonNode s : e.path("supersedes")) links.append("<li>supersedes ").append(idLink(s.asText())).append("</li>");
        for (JsonNode s : e.path("superseded_by")) links.append("<li>superseded by ").append(idLink(s.asText())).append("</li>");
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
        if (locator.isEmpty()) return page(store, patron, "Read", "<p>Say which saved document to read.</p>");
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

    private static String changes(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        var tail = Changes.tail(store, 200);
        StringBuilder b = new StringBuilder("<p class=\"k\">What changed in the library, newest first.</p>");
        if (tail.isEmpty()) b.append("<p>Nothing has changed yet.</p>");
        else { b.append("<ul class=\"tight\">"); for (int i = tail.size() - 1; i >= 0; i--) b.append("<li>").append(changeLine(tail.get(i))).append("</li>"); b.append("</ul>"); }
        return page(store, patron, "Changes", b.toString());
    }

    private static String questions(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        StringBuilder b = new StringBuilder();
        // the searches the housekeeping keeps
        b.append("<h2>Searches kept up to date</h2><p class=\"k\">Each is re-run by the housekeeping on its cadence, and whatever is new is listed on the Changes page.</p>");
        List<Serials.Shelf> kept = Serials.shelves(store);
        if (kept.isEmpty()) b.append("<p>None yet.</p>");
        else {
            b.append("<ul class=\"tight\">");
            for (Serials.Shelf s : kept) b.append("<li>").append(s.parked() ? "<span class=\"badge\">parked</span> " : "").append("<b>").append(esc(s.slug())).append("</b>: ").append(esc(s.query())).append(" <span class=\"k\">(last ").append(esc(s.lastChecked())).append(")</span> ")
                    .append("<form method=\"post\" action=\"/questions\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"every\"><input type=\"hidden\" name=\"name\" value=\"").append(esc(s.slug())).append("\">every <input name=\"every\" size=\"3\" value=\"").append(s.everyDays()).append("\"> days <button>Change</button></form> ")
                    .append("<form method=\"post\" action=\"/questions\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"").append(s.parked() ? "unpark-search" : "park-search").append("\"><input type=\"hidden\" name=\"name\" value=\"").append(esc(s.slug())).append("\"><button>").append(s.parked() ? "Back in the rotation" : "Park").append("</button></form> ")
                    .append("<form method=\"post\" action=\"/questions\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"unkeep\"><input type=\"hidden\" name=\"name\" value=\"").append(esc(s.slug())).append("\"><button>Stop keeping it</button></form></li>");
            b.append("</ul>");
        }
        b.append("<form method=\"post\" action=\"/questions\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"keep\">")
         .append("<label>Keep a search<br><input name=\"query\" size=\"60\" placeholder=\"what to search for, every so often\"></label>")
         .append("<label>Name <input name=\"name\" size=\"16\" placeholder=\"short-name\"></label> <label>Every <input name=\"every\" size=\"3\" value=\"7\"> days</label> <button>Keep it</button></form>");
        // the open questions: the queue, with the filters a person sorts a long list by; tick any number, then one button for all of them
        Map<String, String> f = new java.util.LinkedHashMap<>();
        for (String k : List.of("type", "show", "report", "fate", "who", "subject", "language", "q", "view", "similar")) if (q.containsKey(k) && !q.get(k).isBlank()) f.put(k, q.get(k).strip());
        f.putIfAbsent("show", "queued");
        String show = f.get("show");
        b.append("<h2>Open questions</h2><p class=\"k\">The queue the housekeeping's explorer draws from: ").append(Crews.explorerBudget()).append(" run(s) a night, related questions sharing a run; the Runs page shows tonight's. ")
         .append("A report's leftover questions are filed parked: they wait here until you put them in the queue. Tick any number, then send them as runs now, move them, park them, or drop them.</p>");
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
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (ObjectNode o : open) { String id = o.path("report").asText(""); if (!id.isEmpty() && seen.add(id)) reports.add(new String[]{id, shortReport(id, o.path("report_title").asText(""))}); }
        if (reports.size() > 1) b.append(facetRow(open, f, "report", "Left by", reports, true));
        List<String[]> fates = new ArrayList<>();
        for (String[] x : new String[][]{{"kept", "a report you kept"}, {"waiting", "a report still in the inbox"}, {"disputed", "a disputed report"}, {"retired", "a retired report"}, {"none", "a report with no claims"}}) for (ObjectNode o : open) if (o.path("report_fate").asText("").equals(x[0])) { fates.add(x); break; }
        if (fates.size() > 1) b.append(facetRow(open, f, "fate", "What became of it", fates, true));
        java.util.Map<String, Integer> whos = new java.util.TreeMap<>();
        for (ObjectNode o : open) if (o.hasNonNull("perspective")) whos.merge(o.path("perspective").asText(), 1, Integer::sum);
        if (whos.size() > 1) { List<String[]> ws = new ArrayList<>(); for (String w : whos.keySet()) ws.add(new String[]{w, w}); b.append(facetRow(open, f, "who", "Asked from", ws, true)); }
        java.util.Map<String, Integer> subjects = new java.util.TreeMap<>();
        for (ObjectNode o : open) for (var x : o.path("subjects")) subjects.merge(x.asText(), 1, Integer::sum);
        if (!subjects.isEmpty()) { List<String[]> ss = new ArrayList<>(); for (String x : subjects.keySet()) ss.add(new String[]{x, x}); b.append(facetRow(open, f, "subject", "Subject", ss, true)); }
        java.util.Map<String, Integer> langs = new java.util.TreeMap<>();
        for (ObjectNode o : open) langs.merge(o.path("language").asText("english"), 1, Integer::sum);
        if (langs.size() > 1) { List<String[]> ls = new ArrayList<>(); for (String x : langs.keySet()) ls.add(new String[]{x, x}); b.append(facetRow(open, f, "language", "Language", ls, true)); }
        String view = f.getOrDefault("view", reports.isEmpty() ? "list" : "report");
        String similar = f.getOrDefault("similar", "folded");
        b.append("<p class=\"k\">View: ").append(view.equals("report") ? "<b>by report</b>" : "<a href=\"" + href(f, "view", "report") + "\">by report</a>").append(" · ")
         .append(view.equals("list") ? "<b>one list</b>" : "<a href=\"" + href(f, "view", "list") + "\">one list</a>")
         .append(" &nbsp; Questions that read alike: ").append(similar.equals("folded") ? "<b>folded</b>" : "<a href=\"" + href(f, "similar", "folded") + "\">folded</a>").append(" · ")
         .append(similar.equals("shown") ? "<b>shown</b>" : "<a href=\"" + href(f, "similar", "shown") + "\">shown</a>").append("</p>");
        if (f.getOrDefault("type", "").equals("check")) b.append("<p class=\"k\">Source checks are chores, not research questions: a re-read found the source does not support the claim. The claim is in the <a href=\"/inbox\">Inbox</a>; decide it there. The explorer never takes these.</p>");
        List<ObjectNode> rows = new ArrayList<>();
        for (ObjectNode o : open) if (LibraryProtocol.matches(o, f)) rows.add(o);
        if (rows.isEmpty()) b.append("<p>Nothing here.</p>");
        else {
            b.append("<form method=\"post\" action=\"/questions\" class=\"pick\"><input type=\"hidden\" name=\"op\" value=\"selected\">")
             .append("<div class=\"bar\"><label><input type=\"checkbox\" onclick=\"for(const c of this.form.querySelectorAll('input[name^=q]'))c.checked=this.checked\"> all</label> ")
             .append("<button name=\"do\" value=\"run\">Send as runs now</button> <button name=\"do\" value=\"next\">Run next</button> <button name=\"do\" value=\"later\">Later</button> ");
            if (!show.equals("queued")) b.append("<button name=\"do\" value=\"unpark\">Back in the queue</button> ");
            if (!show.equals("parked")) b.append("<button name=\"do\" value=\"park\">Park</button> ");
            b.append("<button name=\"do\" value=\"drop\">Drop</button></div>");
            // grouped by the report that left them (a group's box ticks the group), or one list; alike questions fold under their first
            java.util.Map<String, List<ObjectNode>> groups = new java.util.LinkedHashMap<>();
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
                java.util.Set<String> inGroup = new java.util.HashSet<>();
                for (ObjectNode o : e.getValue()) inGroup.add(o.path("text").asText());
                java.util.Set<String> folded = new java.util.HashSet<>();
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
        b.append("<form method=\"post\" action=\"/questions\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"budget\">The explorer takes <input name=\"standing\" size=\"2\" value=\"").append(Crews.explorerPerNight()).append("\"> run(s) a night; tonight only: <input name=\"tonight\" size=\"2\" value=\"").append(Crews.explorerTonight() > 0 ? String.valueOf(Crews.explorerTonight()) : "").append("\" placeholder=\"same\"> <button>Set</button></form>");
        b.append("<form method=\"post\" action=\"/questions\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"add\">")
         .append("<label>Add an open question<br><input name=\"question\" size=\"70\" placeholder=\"a question for the explorer to take on a coming night\"></label> <button>Add it</button></form>");
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
        b.append(" <span class=\"k\">").append(esc(t)).append(t.equals("asked") ? " ×" + o.path("asked").asInt() : "").append(" · ").append(esc(o.path("date").asText()));
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

    private static String facetRow(String base, java.util.function.BiPredicate<ObjectNode, Map<String, String>> matches, List<ObjectNode> open, Map<String, String> f, String key, String label, List<String[]> values, boolean withAll) {
        Map<String, String> others = new java.util.LinkedHashMap<>(f); others.remove(key);
        if (key.equals("show")) others.put("show", "all");
        StringBuilder b = new StringBuilder("<p class=\"k\">").append(esc(label)).append(": ");
        String chosen = f.getOrDefault(key, "");
        if (withAll) b.append(chosen.isEmpty() ? "<b>all</b>" : "<a href=\"" + href(base, f, key, "") + "\">all</a>");
        boolean first = !withAll;
        for (String[] v : values) {
            int count = 0;
            Map<String, String> with = new java.util.LinkedHashMap<>(others); with.put(key, v[0]);
            for (ObjectNode o : open) if (matches.test(o, with)) count++;
            if (!first) b.append(" · "); first = false;
            b.append(chosen.equals(v[0]) ? "<b>" + esc(v[1]) + "</b>" : "<a href=\"" + href(base, f, key, v[0]) + "\">" + esc(v[1]) + "</a>").append(" (").append(count).append(")");
        }
        return b.append("</p>").toString();
    }

    /** The questions page with one filter changed; an empty value removes it. */
    private static String href(Map<String, String> f, String key, String value) { return href("/questions", f, key, value); }

    private static String href(String base, Map<String, String> f, String key, String value) {
        Map<String, String> m = new java.util.LinkedHashMap<>(f);
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
                org.researchzosho.Config.set("RESEARCHZOSHO_EXPLORER_PER_NIGHT", String.valueOf(standing));
                org.researchzosho.Config.set("explorer.tonight", String.valueOf(tonight));
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
                    for (String q : picked) {
                        ObjectNode body = M.createObjectNode(); body.put("question", q); body.put("mode", "broad"); body.put("sources", "both");
                        lastId = d.research(body, patron).path("job_id").asText(null);
                        ObjectNode x = args(patron); x.put("op", "drop"); x.put("question", q); try { p.frontier(x); } catch (ProtocolError ignored) { }   // sent: no longer open
                    }
                    if (lastId != null) return "/jobs";
                }
            }
            default -> throw ProtocolError.invalidArgs("unknown form");
        }
        return "/questions";
    }

    /** The inbox: every claim waiting for a decision, sorted by the same facets as the open questions, ticked in any number, then one decision for all of them. */
    private static String inbox(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        Patrons.check(store, patron, Patrons.Level.read);
        Map<String, String> f = new java.util.LinkedHashMap<>();
        for (String k : List.of("report", "subject", "kind", "tier", "confidence", "writer", "state", "language", "q", "view")) if (q.containsKey(k) && !q.get(k).isBlank()) f.put(k, q.get(k).strip());
        List<ObjectNode> all = p.inboxList();
        StringBuilder b = new StringBuilder("<p class=\"k\">Claims waiting for your decision: drafts, and accepted claims whose review went stale. Accepting puts a claim into every answer from now on; disputing files the reason; retiring keeps it on disk and out of every answer. Tick any number, then choose.</p>");
        if (all.isEmpty()) { b.append("<p>Nothing awaits you.</p>"); return page(store, patron, "Inbox", b.toString()); }
        b.append("<form method=\"get\" action=\"/inbox\" class=\"inline\">");
        for (var e : f.entrySet()) if (!e.getKey().equals("q")) b.append("<input type=\"hidden\" name=\"").append(esc(e.getKey())).append("\" value=\"").append(esc(e.getValue())).append("\">");
        b.append("<input name=\"q\" size=\"32\" value=\"").append(esc(f.getOrDefault("q", ""))).append("\" placeholder=\"words in the claim\"> <button>Find</button>")
         .append(f.isEmpty() ? "" : " <a class=\"k\" href=\"/inbox\">clear the filters</a>").append("</form>");
        java.util.function.BiPredicate<ObjectNode, Map<String, String>> m = LibraryProtocol::inboxMatches;
        List<String[]> reports = new ArrayList<>(); java.util.Set<String> seen = new java.util.HashSet<>();
        for (ObjectNode o : all) { String id = o.path("report").asText(""); if (!id.isEmpty() && seen.add(id)) reports.add(new String[]{id, shortReport(id, o.path("report_title").asText(""))}); }
        if (reports.size() > 1 || (reports.size() == 1 && all.size() > reports.size())) b.append(facetRow("/inbox", m, all, f, "report", "From the report", reports, true));
        b.append(facetRow("/inbox", m, all, f, "state", "Waiting because", List.of(new String[]{"draft", "new claim"}, new String[]{"stale", "review went stale"}), true));
        b.append(facetRow("/inbox", m, all, f, "kind", "Kind", List.of(new String[]{"extraction", "extraction"}, new String[]{"synthesis", "synthesis"}, new String[]{"interpretation", "interpretation"}, new String[]{"speculation", "speculation"}), true));
        List<String[]> tiers = new ArrayList<>(); for (SourceTier t : SourceTier.values()) for (ObjectNode o : all) if (o.path("tier").asText("").equals(t.name())) { tiers.add(new String[]{t.name(), t.name()}); break; }
        if (tiers.size() > 1) b.append(facetRow("/inbox", m, all, f, "tier", "Strongest source", tiers, true));
        b.append(facetRow("/inbox", m, all, f, "confidence", "Confidence", List.of(new String[]{"high", "high"}, new String[]{"medium", "medium"}, new String[]{"low", "low"}), true));
        java.util.Map<String, Integer> writers = new java.util.TreeMap<>(); for (ObjectNode o : all) writers.merge(o.path("writer").asText(""), 1, Integer::sum);
        if (writers.size() > 1) { List<String[]> ws = new ArrayList<>(); for (String w : writers.keySet()) ws.add(new String[]{w, w}); b.append(facetRow("/inbox", m, all, f, "writer", "Written by", ws, true)); }
        java.util.Map<String, Integer> subjects = new java.util.TreeMap<>(); for (ObjectNode o : all) for (var x : o.path("subjects")) subjects.merge(x.asText(), 1, Integer::sum);
        if (!subjects.isEmpty()) { List<String[]> ss = new ArrayList<>(); for (String x : subjects.keySet()) ss.add(new String[]{x, x}); b.append(facetRow("/inbox", m, all, f, "subject", "Subject", ss, true)); }
        java.util.Map<String, Integer> langs = new java.util.TreeMap<>(); for (ObjectNode o : all) langs.merge(o.path("language").asText("english"), 1, Integer::sum);
        if (langs.size() > 1) { List<String[]> ls = new ArrayList<>(); for (String x : langs.keySet()) ls.add(new String[]{x, x}); b.append(facetRow("/inbox", m, all, f, "language", "Language", ls, true)); }
        String view = f.getOrDefault("view", reports.isEmpty() ? "list" : "report");
        b.append("<p class=\"k\">View: ").append(view.equals("report") ? "<b>by report</b>" : "<a href=\"" + href("/inbox", f, "view", "report") + "\">by report</a>").append(" · ")
         .append(view.equals("list") ? "<b>one list</b>" : "<a href=\"" + href("/inbox", f, "view", "list") + "\">one list</a>").append("</p>");
        List<ObjectNode> rows = new ArrayList<>();
        for (ObjectNode o : all) if (m.test(o, f)) rows.add(o);
        if (rows.isEmpty()) { b.append("<p>Nothing here.</p>"); return page(store, patron, "Inbox", b.toString()); }
        b.append("<form method=\"post\" action=\"/inbox\" class=\"pick\">")
         .append("<div class=\"bar\"><label><input type=\"checkbox\" onclick=\"for(const c of this.form.querySelectorAll('input[name^=f]'))c.checked=this.checked\"> all</label> ")
         .append("<button name=\"do\" value=\"accept\">Accept the ticked ones</button> <button name=\"do\" value=\"retire\">Retire the ticked ones</button> ")
         .append("<span>Dispute the ticked ones: <input name=\"why\" size=\"36\" placeholder=\"why\"> <button name=\"do\" value=\"dispute\">Dispute</button></span></div>");
        java.util.Map<String, List<ObjectNode>> groups = new java.util.LinkedHashMap<>();
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
                 .append(o.path("stale").asBoolean() ? "stale review" : esc(o.path("state").asText())).append(" · ").append(esc(o.path("kind").asText())).append(" · ").append(esc(o.path("tier").asText()))
                 .append(" · ").append(esc(o.path("confidence").asText())).append(" · ").append(esc(o.path("date").asText()));
                for (var x : o.path("subjects")) b.append(" · ").append(esc(x.asText()));
                b.append("</span></label></li>");
            }
            b.append("</ul>");
        }
        b.append("</form>");
        return page(store, patron, "Inbox", b.toString());
    }

    private static void inboxPost(LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        Patrons.check(store, patron, Patrons.Level.write);
        Council c = new Council(store);
        List<String> ids = new ArrayList<>();
        for (var e : form.entrySet()) if (e.getKey().startsWith("f") && e.getKey().substring(1).matches("\\d+") && !e.getValue().isBlank()) ids.add(e.getValue());
        String why = form.getOrDefault("why", "").strip();
        for (String id : ids) {
            switch (form.getOrDefault("do", "")) {
                case "accept" -> c.accept(id);
                case "retire" -> c.retire(id);
                case "dispute" -> c.dispute(id, why.isEmpty() ? "disputed from the inbox page" : why);
                default -> throw ProtocolError.invalidArgs("unknown decision");
            }
        }
    }

    private static String slugOf(String query) {
        String s = query.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.length() > 24 ? s.substring(0, 24).replaceAll("-+$", "") : s.isEmpty() ? "search" : s;
    }

    private static String jobs(LibraryStore store, LibraryProtocol p, Patrons.Patron patron) throws IOException {
        ObjectNode a = args(patron); a.put("limit", 40);
        ObjectNode r = p.job(a);
        StringBuilder b = new StringBuilder();
        b.append(tonight(store));
        b.append("<h2>Running and waiting</h2>");
        boolean paused = r.path("paused").asBoolean();
        b.append("<form method=\"post\" action=\"/jobs\" class=\"inline\"><input type=\"hidden\" name=\"op\" value=\"").append(paused ? "resume" : "pause").append("\">")
         .append(paused ? "<span class=\"badge\">paused</span> The runner is paused: queued runs wait, a running one holds at its next turn. <button>Resume</button>" : "<button>Pause the runner</button> <span class=\"k\">queued runs wait, a running one holds at its next turn; the housekeeping still runs</span>")
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
        if (!op.equals("pause") && !op.equals("resume") && !op.equals("stop")) throw ProtocolError.invalidArgs("unknown form");
        a.put("op", op);
        if (op.equals("stop")) a.put("job_id", form.getOrDefault("job_id", ""));
        p.job(a);
    }

    /** What the housekeeping will do at its next run, from its own plan. */
    static String tonight(LibraryStore store) throws IOException {
        Tonight.Plan t = Tonight.plan(store);
        StringBuilder b = new StringBuilder();
        b.append("<h2>Tonight</h2><p>The housekeeping runs at ").append(String.format("%02d:00", t.hour())).append(" on ").append(t.date()).append(".</p>");
        b.append("<p><b>Searches you keep.</b> ");
        if (t.due().isEmpty() && t.later().isEmpty()) b.append("None yet. <code>researchzosho shelf add &lt;name&gt; &lt;query&gt; [days]</code> keeps one.</p>");
        else {
            b.append("</p><ul class=\"tight\">");
            for (Serials.Shelf s : t.due()) b.append("<li><b>runs tonight</b> ").append(esc(s.slug())).append(": ").append(esc(s.query())).append(" <span class=\"k\">(every ").append(s.everyDays()).append(" days, last ").append(esc(s.lastChecked())).append(")</span></li>");
            for (Serials.Shelf s : t.later()) b.append("<li><span class=\"k\">").append(s.parked() ? "parked" : "not yet due").append("</span> ").append(esc(s.slug())).append(": ").append(esc(s.query())).append(" <span class=\"k\">(every ").append(s.everyDays()).append(" days, last ").append(esc(s.lastChecked())).append(")</span></li>");
            b.append("</ul>");
        }
        b.append("<p><b>Open questions it will research:</b> ").append(t.budget()).append(" run(s) tonight")
         .append(t.override() > 0 ? " (tonight's override; the standing number is " + t.standing() + ")" : "").append(". Related questions share a run. ")
         .append("<a href=\"/questions\">The queue</a> is where to reorder, park, or drop them.</p>");
        if (t.bundles().isEmpty()) b.append("<p class=\"k\">None: nothing queued that the explorer takes.</p>");
        else {
            b.append("<ol class=\"tight\">");
            for (Crews.Bundle bd : t.bundles()) {
                b.append("<li>").append(esc(bd.question())).append(" <span class=\"k\">").append(esc(bd.head().type())).append("</span>");
                if (!bd.more().isEmpty()) { b.append("<ul class=\"tight\">"); for (Frontier.Line l : bd.more()) b.append("<li class=\"k\">+ ").append(esc(Frontier.strip(l.text()))).append("</li>"); b.append("</ul>"); }
                b.append("</li>");
            }
            b.append("</ol>");
            if (t.stillOpen() > 0) b.append("<p class=\"k\">… ").append(t.stillOpen()).append(" more queued for later nights").append(t.parked() > 0 ? "; " + t.parked() + " parked" : "").append(".</p>");
        }
        b.append("<p class=\"k\">Also: ").append(t.inventoryPerNight()).append(" accepted claims re-read against their sources; new write-ups reviewed and catalogued; preprints and retractions checked; the index refreshed; a backup kept.")
         .append(t.weekly() ? " Weekly extras tonight: the duplicate-claims list and the unreferenced-documents list." : "")
         .append(t.monthly() ? " Monthly extra tonight: the search index is rebuilt." : "").append("</p>");
        return b.toString();
    }

    private static String job(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, String id, Map<String, String> q) throws IOException {
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
        if (live && j.has("progress")) b.append("<p class=\"k\">").append(esc(progressLine(j.get("progress")))).append("</p>");
        if (j.hasNonNull("question")) b.append("<p><b>").append(esc(j.path("question").asText())).append("</b></p>");
        if (j.hasNonNull("investigation")) b.append("<p>The answer is saved as ").append(idLink(j.path("investigation").asText())).append(".</p>");
        if (j.hasNonNull("result") && !j.path("result").asText().isEmpty()) b.append("<h2>").append(j.path("is_error").asBoolean() ? "What went wrong" : "Result").append("</h2><div class=\"body\">").append(md(j.path("result").asText())).append("</div>");
        if (live) return page(store, patron, "Job " + id, b.toString(), 5, null);
        if (!back.isEmpty() && "done".equals(state)) return page(store, patron, "Job " + id, b.toString(), 3, back);
        return page(store, patron, "Job " + id, b.toString());
    }

    private static String researchForm(LibraryStore store, Patrons.Patron patron, String q, String note) throws IOException {
        StringBuilder b = new StringBuilder();
        if (note != null) b.append("<p class=\"err\">").append(esc(note)).append("</p>");
        if (patron.web() && WebAccess.signInRequired()) {
            b.append("<p>To ask for research you need to <a href=\"/login\">sign in</a> first.</p>");
        }
        b.append("<p class=\"k\">A question that takes real reading. The library reads, checks every source it uses, and saves the answer as a write-up you can read here.</p>");
        b.append("<form method=\"post\" action=\"/research\" class=\"stack\">");
        b.append("<label>The question<br><textarea name=\"question\" rows=\"3\" required>").append(esc(q)).append("</textarea></label>");
        b.append("<label>How<br><select name=\"mode\"><option value=\"broad\">broad — cover the whole topic</option><option value=\"depth\">deep — go into detail on the best sources</option></select></label>");
        b.append("<label>Where to read<br><select name=\"sources\"><option value=\"both\">this library and the web</option><option value=\"shelves\">this library only</option><option value=\"web\">the web only</option></select></label>");
        b.append("<label>Limits, if you want any (0 = no limit)<br><input name=\"max_turns\" value=\"0\" size=\"6\"> model turns &nbsp; <input name=\"max_minutes\" value=\"0\" size=\"6\"> minutes</label>");
        b.append(nonceField()).append("<button>Send it</button> <button name=\"sharpen\" value=\"1\" class=\"quiet\">Sharpen it first</button></form>");
        b.append("<p class=\"k\">Sharpen it first: the library reads what it already holds, asks who studies this, and hands the question back tighter, with what it assumed and a plan. You edit, then send. It runs nothing.</p>");
        return page(store, patron, "Research", b.toString());
    }

    /** Start sharpening in the background (or join the one already running for this question) and show the working page. */
    private static String sharpenStart(LibraryStore store, Patrons.Patron patron, String question) throws IOException {
        String q = question.strip();
        if (q.length() < 8) return researchForm(store, patron, q, "The question is too short to sharpen.");
        Patrons.check(store, patron, Patrons.Level.read);
        Researcher.Drive drive = Explain.drive();
        if (drive == null) throw ProtocolError.unavailable("No model drive answers; sharpening a question needs one.");
        String key = sharpenKey(q);
        Sharpening cur = SHARPENING.get(key);
        if (cur == null || (cur.done && cur.error != null) || (cur.done && System.currentTimeMillis() - cur.started > 10 * 60_000L)) {
            Sharpening nw = new Sharpening();
            if (SHARPENING.putIfAbsent(key, nw) == null || cur != null && SHARPENING.replace(key, cur, nw)) {
                Thread t = new Thread(() -> {
                    try { nw.result = Sharpen.run(store, drive, Researcher.webTools(), q); } catch (Throwable e) { nw.error = e; }
                    nw.done = true;
                }, "sharpen-" + key);
                t.setDaemon(true); t.start();
            }
        }
        return sharpenPoll(store, patron, key);
    }

    /** The working page while a sharpening runs; the result once it is there; the error if it failed. */
    private static String sharpenPoll(LibraryStore store, Patrons.Patron patron, String key) throws IOException {
        Sharpening w = SHARPENING.get(key);
        if (w == null) return researchForm(store, patron, "", "That sharpening is gone; type the question again.");
        if (!w.done) {
            long secs = (System.currentTimeMillis() - w.started) / 1000;
            String body = "<div class=\"working\"><span class=\"spin\"></span><div><p><b>Sharpening the question…</b></p>"
                    + "<p class=\"k\">" + secs + " seconds so far. The library reads what it already holds, asks who studies this, and writes the plan. About half a minute.</p>"
                    + "<p class=\"k\">This page updates itself.</p></div></div>";
            return page(store, patron, "Sharpening", body, 2, "/research?sharpen=" + key);
        }
        if (w.error != null) throw w.error instanceof ProtocolError pe ? pe : ProtocolError.unavailable("The question could not be sharpened: " + w.error.getMessage());
        return sharpened(store, patron, w.result);
    }

    /** The sharpened question: what it assumed, the plan, what you hold, and the form filled back in with it. */
    private static String sharpened(LibraryStore store, Patrons.Patron patron, Sharpen.Sharpened s) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\">You asked: ").append(esc(s.original())).append("</p>");
        b.append("<h2>Sharpened</h2><p><b>").append(esc(s.question())).append("</b></p>");
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
            b.append("<h2>Already on the shelves</h2><ul class=\"tight\">");
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
        b.append("<label>Where to read<br><select name=\"sources\"><option value=\"both\">this library and the web</option><option value=\"shelves\">this library only</option><option value=\"web\">the web only</option></select></label>");
        boolean quick = "quick".equals(s.size());
        b.append("<label>Limits, if you want any (0 = no limit)<br><input name=\"max_turns\" value=\"").append(quick ? Explain.QUICK_TURNS : 0).append("\" size=\"6\"> model turns &nbsp; <input name=\"max_minutes\" value=\"").append(quick ? Explain.QUICK_MINUTES : 0).append("\" size=\"6\"> minutes</label>");
        if (quick) b.append("<input type=\"hidden\" name=\"quick\" value=\"1\">");
        b.append(nonceField()).append("<button>Send it</button> <button name=\"sharpen\" value=\"1\" class=\"quiet\">Sharpen again</button></form>");
        return page(store, patron, "Sharpened", b.toString());
    }

    private static String researchPost(LibrarianDaemon d, LibraryStore store, Patrons.Patron patron, Map<String, String> form) throws IOException {
        String once = form.getOrDefault("once", "");
        if (!once.isEmpty() && SENT.containsKey(once)) {
            String id = SENT.get(once);
            return page(store, patron, "Already sent", "<p>This question was already sent, as <a href=\"/jobs/" + enc(id) + "\">" + esc(id) + "</a>. A second click or a refresh does not send it again.</p>"
                    + "<p class=\"k\"><a href=\"/research\">Send a different question</a> · <a href=\"/jobs\">the runs</a></p>");
        }
        ObjectNode body = M.createObjectNode();
        body.put("question", form.getOrDefault("question", "").strip());
        body.put("mode", form.getOrDefault("mode", "broad"));
        body.put("sources", form.getOrDefault("sources", "both"));
        body.put("max_turns", num(form.get("max_turns"))); body.put("max_minutes", num(form.get("max_minutes")));
        if ("1".equals(form.get("quick"))) body.put("quick", true);
        String back = form.getOrDefault("back", "");
        if (!back.startsWith("/explain?")) back = "";
        body.set("patron", LibrarianDaemon.patronNode(patron));
        ObjectNode r;
        try { r = d.research(body, patron); }
        catch (ProtocolError e) { if ("forbidden".equals(e.code)) throw e; return researchForm(store, patron, form.getOrDefault("question", ""), e.getMessage()); }
        String id = r.path("job_id").asText();
        if (!once.isEmpty()) SENT.put(once, id);
        boolean quick = r.path("quick").asBoolean(false);
        String jobUrl = "/jobs/" + enc(id) + (back.isEmpty() ? "" : "?back=" + enc(back));
        return page(store, patron, "Sent", "<p>Sent as <a href=\"" + jobUrl + "\">" + esc(id) + "</a>" + (quick ? ", first in line" : "") + ". " + r.path("queued_ahead").asInt() + " before it, " + r.path("workers").asInt()
                + " worker(s). The <a href=\"/jobs\">runs</a> page shows how it is going. The answer appears there, and in the library, when it is done.</p>"
                + (back.isEmpty() ? "" : "<p>When it is done, <a href=\"" + esc(back) + "\">read the explanation</a>" + (quick ? " — a few minutes" : "") + ".</p>"),
                quick ? 2 : 0, quick ? jobUrl : null);
    }

    private static String explain(LibraryStore store, LibraryProtocol p, Patrons.Patron patron, Map<String, String> q) throws IOException {
        String id = q.getOrDefault("id", "").strip(), term = q.getOrDefault("term", "").strip(), in = q.getOrDefault("in", "").strip();
        Explain.Rung rung = Explain.Rung.of(q.getOrDefault("rung", "beginner"));
        boolean fresh = "1".equals(q.get("fresh"));
        if (id.isEmpty() && term.isEmpty()) return page(store, patron, "Explain", "<p>Open an entry and pick a level, or type a word to explain.</p>");
        Patrons.check(store, patron, Patrons.Level.read);
        String key = (term.isEmpty() ? "id=" + id : "term=" + term.toLowerCase(java.util.Locale.ROOT) + "&in=" + in) + "&rung=" + rung.name();
        Working w = WORKING.get(key);
        if (w != null && !w.done) return workingPage(store, patron, w, term.isEmpty() ? id : term, in.isEmpty() ? id : in, rung);
        if (w != null && w.error != null) { WORKING.remove(key); throw w.error instanceof ProtocolError pe ? pe : ProtocolError.unavailable("The explanation could not be written: " + w.error.getMessage()); }
        if (w != null) WORKING.remove(key);
        ObjectNode r;
        // the cache answers at once; anything that needs the model runs in the background and the page comes back now
        Explain.Reading cachedOnly = null;
        if (!fresh) {
            try {
                cachedOnly = term.isEmpty() ? Explain.entry(store, null, id, rung, false) : Explain.term(store, null, term, in, rung, false);
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
                b.append("<p>The library can look it up: a quick look is a few minutes at the front of the line; full research has no limits and waits its turn. Either way every source is checked and the answer is saved. Then this page can explain it.</p>");
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
        String title = term.isEmpty() ? "Read it: " + rung.name() : esc(term);
        return page(store, patron, title, b.toString());
    }

    /** Sends already made: token → the job id, so a second click or a refresh shows the first send instead of filing again. */
    static final java.util.Map<String, String> SENT = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, String> e) { return size() > 500; }
    });
    static String nonce() { return java.util.UUID.randomUUID().toString().replace("-", ""); }
    static String nonceField() { return "<input type=\"hidden\" name=\"once\" value=\"" + nonce() + "\">"; }

    /** A sharpening in the background, keyed by the question: the result when done, the error when it failed. */
    static final class Sharpening {
        final long started = System.currentTimeMillis();
        volatile Sharpen.Sharpened result; volatile Throwable error; volatile boolean done;
    }
    static final java.util.concurrent.ConcurrentHashMap<String, Sharpening> SHARPENING = new java.util.concurrent.ConcurrentHashMap<>();
    static String sharpenKey(String q) { return Integer.toHexString(q.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").hashCode()); }

    /** A reading being written in the background: what it is doing, since when, and how it ended. */
    static final class Working {
        volatile String stage = "starting";
        final long started = System.currentTimeMillis();
        volatile boolean done;
        volatile Throwable error;
    }
    static final java.util.concurrent.ConcurrentHashMap<String, Working> WORKING = new java.util.concurrent.ConcurrentHashMap<>();

    /** The page that comes back at once: what the library is doing, for how long, refreshing itself until the reading is there. */
    private static String workingPage(LibraryStore store, Patrons.Patron patron, Working w, String what, String of, Explain.Rung rung) throws IOException {
        long secs = (System.currentTimeMillis() - w.started) / 1000;
        StringBuilder b = new StringBuilder();
        b.append("<div class=\"working\"><span class=\"spin\"></span><div><p><b>The library is ").append(esc(w.stage)).append("…</b></p>");
        b.append("<p class=\"k\">").append(secs).append(" seconds so far. The library reads what it has, writes, then checks each paragraph against its sources. ")
         .append("A write-up takes about a minute the first time; a word, ten to twenty seconds.</p>");
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
        Explain.Reading reading = rung == Explain.Rung.written ? null : Explain.entry(store, null, id, rung, false);   // a reading must already be written; the page writes it
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
        Map<String, String> label = new java.util.HashMap<>();
        Matcher m = REF_LINE.matcher(unentity(markdown));
        while (m.find()) {
            String rest = m.group(2);
            String title = rest.contains(" — ") ? rest.substring(0, rest.indexOf(" — ")).strip() : "";
            Matcher u = Pattern.compile("https?://[^\\s]+").matcher(rest);
            String host = "";
            if (u.find()) { try { host = java.net.URI.create(u.group().replaceAll("[),.;]+$", "")).getHost(); } catch (Exception ignored) { } if (host == null) host = ""; }
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
        return "<p class=\"k\">Read it: " + rungLinks("/explain?id=" + enc(id) + "&rung=", current) + " <span class=\"k\">(the first time takes about a minute)</span></p>";
    }
    private static String rungLinks(String hrefPrefix, Explain.Rung current) {
        StringBuilder b = new StringBuilder();
        for (Explain.Rung r : Explain.Rung.values()) {
            if (b.length() > 0) b.append(" · ");
            String label = r == Explain.Rung.written ? "as written" : r.name();
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

    private static String loginPage(LibraryStore store, String note) throws IOException {
        StringBuilder b = new StringBuilder();
        if (note != null) b.append("<p class=\"err\">").append(esc(note)).append("</p>");
        if (!WebAccess.signInRequired()) b.append("<p>").append(esc(WebAccess.OPEN_NOTICE)).append(" Signing in only puts your name on what you send. ")
                .append(esc(WebAccess.OPEN_HOWTO).replace("run: ", "Run <code>")).append("</code> on the computer where the library lives to change that.</p>");
        else b.append("<p class=\"k\">You can read without signing in, if the library allows it. To ask for research you need to sign in. Paste your token; it stays in this browser only.</p>");
        b.append("<form method=\"post\" action=\"/login\" class=\"stack\"><label>Token<br><input name=\"token\" size=\"60\" autofocus></label><button>Sign in</button></form>");
        b.append("<p class=\"k\">To make a token, on the computer where the library lives:</p><pre>researchzosho reader add did:key:me \"Me\" write\nresearchzosho reader token did:key:me</pre>");
        return page(store, Patrons.Patron.ANONYMOUS, "Sign in", b.toString());
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

    private static String jobLine(JsonNode j) {
        String id = j.path("job_id").asText();
        return "<a href=\"/jobs/" + enc(id) + "\">" + esc(id) + "</a> " + badge(j.path("state").asText()) + " <span class=\"k\">" + esc(j.path("kind").asText()) + " · " + j.path("elapsed_s").asLong() + " s</span>"
                + (j.hasNonNull("question") ? "<br>" + esc(Acquisitions.compress(j.path("question").asText(), 160)) : "")
                + (j.hasNonNull("investigation") ? " → " + idLink(j.path("investigation").asText()) : "");
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
        else if (p.path("turns_used").asInt() > 0) sb.append(sb.length() > 0 ? " · " : "").append(p.path("turns_used").asInt()).append(" turns");
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

    static String page(LibraryStore store, Patrons.Patron patron, String title, String body) throws IOException { return page(store, patron, title, body, 0, null); }

    /** {@code refresh} > 0 makes the page reload itself after that many seconds — at {@code to} when given, else in place. */
    static String page(LibraryStore store, Patrons.Patron patron, String title, String body, int refresh, String to) throws IOException { return page(store, patron, title, body, refresh, to, false); }

    /** {@code wide}: the page uses the whole window (the map), not the reading column. */
    static String page(LibraryStore store, Patrons.Patron patron, String title, String body, int refresh, String to, boolean wide) throws IOException {
        String name = store.identity().name();
        String meta = refresh > 0 ? "<meta http-equiv=\"refresh\" content=\"" + refresh + (to == null ? "" : ";url=" + esc(to)) + "\">" : "";
        String who = patron.web() && !WebAccess.signInRequired() ? "open to everyone · <a href=\"/login\">sign in</a>"
                : patron.web() || patron.anonymous() ? "<a href=\"/login\">sign in</a>"
                : esc(patron.name().isEmpty() ? patron.did() : patron.name()) + " · <a href=\"/logout\">sign out</a>";
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc((title == null ? name : title + " — " + name) + " · ResearchZosho") + "</title>" + meta + "<link rel=\"icon\" href=\"/favicon.ico\" type=\"image/png\"><style>" + CSS + "</style></head><body>"
                + "<header><a class=\"home\" href=\"/\"><img src=\"/favicon.ico\" alt=\"\"> <span class=\"brand\">ResearchZosho</span><span class=\"lib\">" + esc(name) + "</span></a><nav>"
                + "<a href=\"/ask\">Ask</a><a href=\"/search\">Search</a><a href=\"/inbox\">Inbox</a><a href=\"/subjects\">Subjects</a><a href=\"/questions\">Open</a><a href=\"/changes\">Changes</a><a href=\"/jobs\">Runs</a><a href=\"/research\">Research</a><a href=\"/map\">Map</a>"
                + "</nav><span class=\"who\">" + who + "</span></header><main" + (wide ? " class=\"wide\"" : "") + ">"
                + (title == null ? "" : "<h1>" + esc(title) + "</h1>") + body + "</main>"
                + "<footer class=\"k\">ResearchZosho " + esc(org.researchzosho.Version.string()) + " · <a href=\"https://researchzosho.org\">researchzosho.org</a>"
                + (org.researchzosho.Version.updateNotice().isEmpty() ? "" : " · <b>" + esc(org.researchzosho.Version.latestCached()) + " is available</b>: update with the one-liner on <a href=\"https://researchzosho.org/#install\">researchzosho.org</a>; the library and settings stay")
                + "</footer></body></html>";
    }

    static final String CSS =
            ":root{--bg:#f7f3ea;--ink:#1e1b16;--k:#6b6459;--accent:#c3402f;--line:#e2dccd;--card:#fffdf7}"
            + "@media(prefers-color-scheme:dark){:root{--bg:#12161d;--ink:#f3ede1;--k:#9aa3b2;--accent:#e0654f;--line:#2a3140;--card:#1b2130}}"
            + "body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 Georgia,'Noto Serif',serif}"
            + "header{display:flex;flex-wrap:wrap;align-items:center;gap:.6em 1.2em;padding:.7em 1.2em;border-bottom:1px solid var(--line)}"
            + "header .home{text-decoration:none;color:var(--ink);display:flex;align-items:center;gap:.5em}header .home img{height:28px}header .brand{font-weight:bold}header .lib{color:var(--k);font-size:.92em}header .lib::before{content:'·';margin:0 .5em}"
            + "nav a{margin-right:1em;color:var(--ink)}.who{margin-left:auto;color:var(--k);font-size:.9em}"
            + "main{max-width:52em;margin:0 auto;padding:1em 1.2em 3em}main.wide{max-width:none}footer{text-align:center;padding:2em;border-top:1px solid var(--line)}"
            + "a{color:var(--accent)}h1{font-size:1.6em;line-height:1.2}h2{font-size:1.15em;margin-top:1.6em;border-bottom:1px solid var(--line)}h2 a.k{font-weight:normal;font-size:.8em;margin-left:.8em}"
            + ".k{color:var(--k);font-size:.92em}.err{color:var(--accent)}"
            + "form.big{display:flex;gap:.5em;align-items:center;margin:1em 0}form.big input{flex:1;font:inherit;padding:.5em .7em;border:1px solid var(--line);background:var(--card);color:var(--ink);min-width:12em}"
            + "button{font:inherit;padding:.5em 1em;background:var(--accent);color:#fff;border:0;cursor:pointer}button.quiet{background:var(--card);color:var(--ink);border:1px solid var(--line)}"
            + "form.stack label{display:block;margin:.8em 0}form.stack textarea,form.stack input,form.stack select{font:inherit;padding:.4em;border:1px solid var(--line);background:var(--card);color:var(--ink);width:100%;max-width:40em;box-sizing:border-box}"
            + "form.pick ul{list-style:none;padding-left:0}form.pick ul ul{padding-left:1.6em}form.pick h3.group{margin:1em 0 .2em;font-size:1.02em}form.pick details{margin:.2em 0 .2em 1.6em}form.pick summary{cursor:pointer}form.pick li{margin:.35em 0}form.pick label{display:block}form.pick .bar{display:flex;gap:.7em;align-items:center;flex-wrap:wrap;margin:.8em 0;position:sticky;top:0;background:var(--bg);padding:.4em 0}"
            + "form.stack input[size]{width:auto}ul.tight{padding-left:1.2em}ul.tight li{margin:.2em 0}ol.hits li{margin:.7em 0}.snip{color:var(--k)}"
            + ".card{background:var(--card);border:1px solid var(--line);padding:.6em 1em;margin:.8em 0}.card h3{margin:.2em 0;font-size:1.05em}.card p{margin:.3em 0}"
            + ".badge{display:inline-block;padding:0 .5em;border-radius:.6em;font-size:.8em;background:var(--line)}.badge.accepted{background:#3f8a4f;color:#fff}.badge.disputed,.badge.failed{background:var(--accent);color:#fff}.badge.running{background:#2e6fb0;color:#fff}"
            + "pre{white-space:pre-wrap;word-break:break-word;background:var(--card);border:1px solid var(--line);padding:.8em;font-size:.9em}pre.raw{max-height:70vh;overflow:auto}"
            + ".body{margin:1em 0}.triple{font-style:italic}code{font-size:.92em}"
            + ".notice{background:var(--card);border:1px solid var(--line);border-left:4px solid var(--accent);padding:.6em 1em}"
            + ".working{display:flex;gap:1em;align-items:flex-start;margin:1.5em 0}.spin{flex:none;width:22px;height:22px;margin-top:1em;border:3px solid var(--line);border-top-color:var(--accent);border-radius:50%;animation:spin 1s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}"
            + ".body{word-break:break-word}p.src{margin:.3em 0}"
            + "p.ref{margin:.3em 0;padding-left:2.2em;text-indent:-2.2em;word-break:break-word}p.ref b{color:var(--k)}"
            + ".tablewrap{overflow-x:auto;margin:1em 0}table{border-collapse:collapse;font-size:.92em;min-width:100%}th,td{border:1px solid var(--line);padding:.4em .6em;vertical-align:top;text-align:left}th{background:var(--card)}"
            + ".reading p{margin:.8em 0}a.cite{font-size:.8em;text-decoration:none;color:var(--k)}.mark{font-size:.8em;padding:0 .4em;border-radius:.5em;background:var(--line)}.mark.bad{background:var(--accent);color:#fff}form.inline{display:inline}";

    // ---- http ----

    static Map<String, String> form(String body, String contentType) {
        Map<String, String> m = new LinkedHashMap<>();
        if (contentType == null || !contentType.startsWith("application/x-www-form-urlencoded")) return m;
        for (String kv : body.split("&")) {
            if (kv.isEmpty()) continue;
            int eq = kv.indexOf('=');
            m.put(java.net.URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8), eq < 0 ? "" : java.net.URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    static String cookie(HttpExchange x, String name) {
        List<String> all = x.getRequestHeaders().get("Cookie");
        if (all == null) return null;
        for (String h : all) for (String c : h.split(";")) {
            String s = c.strip();
            if (s.startsWith(name + "=")) return s.substring(name.length() + 1);
        }
        return null;
    }

    private static void send(HttpExchange x, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        // no scripts, except: the map (its own script, its own data) and a pick form (the "all" and group boxes tick the others
        // with an inline handler; with scripts blocked they ticked nothing, 2026-09-09)
        x.getResponseHeaders().set("Content-Security-Policy", html.contains("id=\"c\"></canvas>")
                ? "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'"
                : html.contains("class=\"pick\"")
                ? "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'"
                : "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; form-action 'self'");
        x.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = x.getResponseBody()) { os.write(bytes); }
    }

    private static void redirect(HttpExchange x, String to) throws IOException {
        x.getResponseHeaders().set("Location", to);
        x.sendResponseHeaders(303, -1);
        x.close();
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20"); }
}
