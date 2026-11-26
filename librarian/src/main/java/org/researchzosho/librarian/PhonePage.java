package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The phone page (2026-11-25): one small document with three parts, Ask, Runs and Read, and the calls its script makes through the
 * phone door ({@link PhoneDoor}). The document is the same bytes for a paired browser and an unpaired one; what it shows is asked by its
 * script, with the page's own same-origin calls, so nothing depends on the browser sending the cookie with the first load (a start from
 * the home screen, the camera or another app). It loads its own script, its icons and its manifest, nothing else. A person pairs in the
 * browser first and then puts the page on the home screen; the page's button for that only shows the taps. The chat behind Ask is the library's
 * own ({@link Pages#liveTurn}), in conversations of the phone's own, under {@link PhoneRules}.
 */
final class PhonePage {
    private PhonePage() { }

    private static final ObjectMapper M = new ObjectMapper();

    private static String t(String en, Object... args) { return Chrome.now().t(en, args); }

    // ---------------------------------------------------------------- the words

    static final String NOT_PAIRED = "This phone is not paired with a library.";
    static final String HOW_TO_PAIR = "To pair it, run researchzosho phone pair on the library's machine and scan the code it shows.";
    static final String PAIR_LINK = "This link pairs this phone with a library, once.";
    static final String PAIR_FIRST = "Pair here, in the browser, first. Then you can put the page on the Home Screen.";
    static final String PAIR = "Pair this phone";
    static final String BAD_CODE = "This pairing link is not good any more. Make a new one on the library's machine: researchzosho phone pair.";
    static final String WRONG_CODES = "Too many wrong pairing links from here. Try again in {0}.";
    static final String HOME = "Put it on the Home Screen";
    static final String HOME_ANDROID = "Android: tap the menu (⋮), then Add to Home screen, then Install.";
    static final String HOME_IPHONE = "iPhone: tap Share (on iOS 26 it is under ⋯), then Add to Home Screen.";
    static final String GOING = "Going";
    static final String SINCE = "Finished since you last looked";
    static final String RECENT = "Recent";
    static final String NOTHING_GOING = "Nothing is running.";
    static final String OPENING = "Opening…";
    static final String LOADING = "Loading…";
    static final String SENDING = "Sending…";
    static final String NOT_BACK = "Nothing came back yet. The connection may be slow or down.";
    static final String NO_REPORTS = "No reports yet.";
    static final String TURN_GOING = "A reply is still being written. Wait for it, or stop it.";
    static final String PICTURES_ONLY = "Only pictures go with a message from the phone, up to {0}.";
    static final String TOO_MANY_ASKS = "Too many messages from this phone. Try again in {0}.";
    static final String NOT_OURS = "That run was started elsewhere: stop it on the library's own network.";
    static final String PICTURE = "Picture";
    static final String UNDO = "To undo: stop {0} (its stop button under Runs on this page).";

    // ---------------------------------------------------------------- one route

    static void serve(PhoneDoor door, LibrarianDaemon d, LibraryStore store, HttpExchange x, String route, Phones.Phone phone, Patrons.Patron patron, String sender) throws IOException {
        Map<String, String> q = PhoneDoor.form(x.getRequestURI().getRawQuery());
        switch (route) {
            case "page" -> PhoneDoor.answer(x, 200, "text/html; charset=utf-8", "no-store", html().getBytes(StandardCharsets.UTF_8));
            case "script" -> PhoneDoor.bytes(x, "text/javascript; charset=utf-8", JS.getBytes(StandardCharsets.UTF_8), false);
            case "icon" -> PhoneDoor.bytes(x, "image/png", icon(x.getRequestURI().getRawPath()), true);
            case "manifest" -> PhoneDoor.bytes(x, "application/manifest+json", manifest().getBytes(StandardCharsets.UTF_8), false);
            case "state" -> state(store, x, phone);
            case "pair" -> pair(door, store, x, sender);
            case "conv" -> PhoneDoor.json(x, 200, conv(store, patron, Pages.chatSession(store, patron, q), ""));
            case "live" -> PhoneDoor.json(x, 200, Pages.chatLive(store, patron, q.getOrDefault("session", "")));
            case "runs" -> PhoneDoor.json(x, 200, runs(store, phone));
            case "reads" -> PhoneDoor.json(x, 200, reads(store));
            case "read" -> PhoneDoor.json(x, 200, M.createObjectNode().put("html", read(store, patron, x.getRequestURI().getRawPath().substring("/m/read/".length()))));
            case "image" -> Pages.imageFile(x, store, patron, x.getRequestURI().getRawPath().substring("/m/images/".length()));
            case "ask" -> ask(door, d, store, x, phone, patron);
            case "stop" -> { Map<String, String> f = PhoneDoor.form(door.small(x, PhoneDoor.SMALL_BYTES)); Librarian.Session s = Pages.chatSession(store, patron, f); Pages.Live live = s == null ? null : Pages.LIVE.get(s.id); if (live != null && !live.done) live.stop = true; PhoneDoor.json(x, 200, M.createObjectNode().put("stopping", live != null && !live.done)); }
            case "new" -> { door.small(x, PhoneDoor.SMALL_BYTES); Librarian.Session s = Librarian.Session.open(store, patron); PhoneDoor.json(x, 200, M.createObjectNode().put("session", s == null ? "" : s.id)); }
            case "seen" -> { door.small(x, PhoneDoor.SMALL_BYTES); Phones.seen(phone.id()); PhoneDoor.json(x, 200, M.createObjectNode().put("seen", true)); }
            case "runstop" -> {
                String id = PhoneDoor.form(door.small(x, PhoneDoor.SMALL_BYTES)).getOrDefault("job_id", "").strip();
                if (!PhoneRules.ownRun(store, id, phone.id())) { PhoneDoor.json(x, 403, M.createObjectNode().put("error", t(NOT_OURS))); return; }
                ObjectNode a = M.createObjectNode(); a.put("op", "stop"); a.put("job_id", id); a.set("patron", LibrarianDaemon.patronNode(patron));
                new LibraryProtocol(store).job(a);
                if (d != null) d.jobs().pickUp();
                PhoneDoor.json(x, 200, M.createObjectNode().put("stopped", id));
            }
            default -> PhoneDoor.plain(x, 404, "Not found.");
        }
    }

    // ---------------------------------------------------------------- pairing

    static void state(LibraryStore store, HttpExchange x, Phones.Phone phone) throws IOException {
        if (phone == null) { PhoneDoor.json(x, 200, M.createObjectNode().put("paired", false)); return; }
        PhoneDoor.cookie(x, Pages.cookie(x, PhoneDoor.COOKIE));   // set again: 400 days from now, at every open
        ObjectNode o = M.createObjectNode().put("paired", true).put("phone", phone.name()).put("library", store.identity().name());
        ObjectNode r = runs(store, phone);
        o.put("going", r.path("going").size()).put("new", r.path("since").size());
        o.put("month", PhoneRules.monthSaid(store, phone));   // what this phone spent on paid models this month, against its own cap
        PhoneDoor.json(x, 200, o);
    }

    static void pair(PhoneDoor door, LibraryStore store, HttpExchange x, String sender) throws IOException {
        String code = PhoneDoor.form(door.small(x, PhoneDoor.PAIR_BYTES)).getOrDefault("c", "").strip();
        PhoneDoor.Buckets b = door.buckets();
        long wait = Math.max(b.peek("wrong|" + sender, PhoneDoor.WRONG), b.peek("wrong|all", PhoneDoor.WRONG_ALL));
        if (wait > 0) { x.getResponseHeaders().set("Retry-After", String.valueOf(wait)); PhoneDoor.json(x, 429, M.createObjectNode().put("error", t(WRONG_CODES, RateLimits.in(wait)))); return; }
        Phones.Paired p = Phones.redeem(code);
        if (p == null) {
            b.take("wrong|" + sender, PhoneDoor.WRONG);
            b.take("wrong|all", PhoneDoor.WRONG_ALL);
            if (b.peek("wrong|all", PhoneDoor.WRONG_ALL) > 0) Crews.log(store, "phone", "pairing is closed for a while: " + PhoneDoor.WRONG_ALL + " wrong pairing codes were tried", 0);
            PhoneDoor.json(x, 403, M.createObjectNode().put("error", t(BAD_CODE)));
            return;
        }
        PhoneDoor.cookie(x, p.secret());
        Crews.log(store, "phone", "paired: " + p.phone().name(), 0);
        PhoneDoor.json(x, 200, M.createObjectNode().put("paired", true).put("phone", p.phone().name()));
    }

    // ---------------------------------------------------------------- Ask

    /** For tests: told whether an ask found the phone's turn free (true) or a reply still being written (false). */
    static volatile Consumer<Boolean> TURN_TAKEN = taken -> { };
    /** For tests: run once a turn's answer is written, on the turn's own thread. */
    static volatile Runnable ANSWERED = () -> { };

    static void ask(PhoneDoor door, LibrarianDaemon d, LibraryStore store, HttpExchange x, Phones.Phone phone, Patrons.Patron patron) throws IOException {
        String id = phone.id();
        boolean taken = door.turning.add(id);
        TURN_TAKEN.accept(taken);
        if (!taken) { if (door.dropUpload(x)) PhoneDoor.json(x, 409, M.createObjectNode().put("error", t(TURN_GOING))); return; }
        Map<String, String> form = Map.of();
        try {
            long wait = door.buckets().take("asks|" + id, PhoneDoor.ASKS);
            if (wait > 0) { if (door.dropUpload(x)) { x.getResponseHeaders().set("Retry-After", String.valueOf(wait)); reply(door, id, x, 429, M.createObjectNode().put("error", t(TOO_MANY_ASKS, RateLimits.in(wait)))); } return; }
            String ct = x.getRequestHeaders().getFirst("Content-Type");
            boolean multipart = ct != null && ct.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
            String over = t(PICTURES_ONLY, (PhoneDoor.ASK_BYTES >> 20) + " MB");
            if (multipart) {
                if (Uploads.declared(x.getRequestHeaders().getFirst("Content-Length")) > PhoneDoor.ASK_BYTES) { x.close(); return; }   // closed unanswered: the page says it in its own words before it sends one
                try (InputStream in = door.body(x, PhoneDoor.ASK_BYTES, true)) { form = Uploads.posted(in, ct, PhoneDoor.ASK_BYTES, over); }
            } else { try (InputStream in = door.body(x, 64 * 1024, false)) { form = PhoneDoor.form(new String(in.readAllBytes(), StandardCharsets.UTF_8)); } }
            Map<String, String> f = new LinkedHashMap<>(form);
            f.remove(Uploads.REFUSED); f.remove("page");
            List<Uploads.Uploaded> files = Uploads.files(f, "file");
            boolean pictures = files.size() <= PhoneDoor.ASK_FILES;
            for (Uploads.Uploaded u : files) if (!Images.isPictureFile(u.path())) pictures = false;
            if (!pictures) { reply(door, id, x, 400, M.createObjectNode().put("error", over)); return; }
            // the chat's own model, against this phone's month: a paid one with no prices, or with the cap reached, is said in the
            // conversation as the library's own line, and nothing is asked of the model
            Librarian.Session chosen = Pages.chatSession(store, patron, f);
            String spend = chosen == null ? null : PhoneRules.spend(store, patron, chatEntry(store, chosen));
            if (spend != null && !f.getOrDefault("say", "").isBlank()) {
                Pages.chatReplied(chosen, f.getOrDefault("say", "").strip(), PhoneRules.said(store, patron, spend));
                reply(door, id, x, 200, conv(store, patron, chosen, ""));
                return;
            }
            Pages.Turn turn = Pages.liveTurn(d, store, patron, f, false, true);
            if (d != null) d.jobs().pickUp();   // a run the turn filed starts now, not at the runner's next look
            Librarian.Session s = turn.show() != null ? turn.show() : Pages.chatSession(store, patron, f);
            reply(door, id, x, 200, conv(store, patron, s, turn.note()));
        } finally { door.turning.remove(id); Uploads.drop(form); }   // the turn's end for a failure, or a body closed unanswered
    }

    /**
     * A turn's answer: the phone's turn is let go first, then the answer is written, so a page that sends its next message the moment
     * it has this answer never finds its own turn still held (2026-11-25: with the release after the answer, a busy machine answered
     * such a message "A reply is still being written").
     */
    private static void reply(PhoneDoor door, String phoneId, HttpExchange x, int status, JsonNode body) throws IOException {
        door.turning.remove(phoneId);
        PhoneDoor.json(x, status, body);
        ANSWERED.run();
    }

    /** The model entry a conversation's turns go to: the one it chose with /model, best judgement's pick, else the default. */
    static ModelEntries.Entry chatEntry(LibraryStore store, Librarian.Session s) {
        String named = s == null ? "" : s.model();
        ModelEntries.Entry e = named.isEmpty() ? null : named.equalsIgnoreCase(Auto.NAME) ? Auto.choose(store, Auto.Job.chat()).entry() : ModelEntries.get(named);
        return e != null ? e : ModelEntries.defaultEntry();
    }

    /** The phone's conversation as the page shows it: the turns, a note under the latest, a waiting question's buttons; its id. */
    static ObjectNode conv(LibraryStore store, Patrons.Patron patron, Librarian.Session s, String note) throws IOException {
        ObjectNode o = M.createObjectNode();
        if (s == null) return o.put("html", "").put("session", "");
        return o.put("html", phoneLinks(Pages.chatView(store, new LibraryProtocol(store), patron, s, note == null ? "" : note))).put("session", s.id);
    }

    // ---------------------------------------------------------------- Runs

    /** The library's runs: going, finished since this phone last looked (or since it was paired), and the recent ones. */
    static ObjectNode runs(LibraryStore store, Phones.Phone phone) throws IOException {
        ObjectNode o = M.createObjectNode();
        ArrayNode going = o.putArray("going"), since = o.putArray("since"), recent = o.putArray("recent");
        Jobs jobs = new Jobs(store, j -> "");
        long now = System.currentTimeMillis(), seen = millis(phone.seen().isBlank() ? phone.paired() : phone.seen());
        for (ObjectNode raw : jobs.active()) going.add(run(store, raw, now, phone.id()));
        int shown = 0;
        for (ObjectNode raw : jobs.recent(20, null)) {
            long ended = RunProgress.millis(raw.path("ended_at").asText(""));
            ObjectNode r = run(store, raw, now, phone.id());
            if (ended > seen) since.add(r); else if (shown++ < 8) recent.add(r);
        }
        o.put("seen", phone.seen());
        o.put("month", PhoneRules.monthSaid(store, phone));
        return o;
    }

    private static ObjectNode run(LibraryStore store, ObjectNode raw, long now, String phoneId) {
        RunProgress.View v = RunProgress.of(Jobs.view(raw), now);
        ObjectNode r = M.createObjectNode().put("id", v.id()).put("name", Pages.runName(store, raw)).put("state", v.state()).put("stage", v.stage())
                .put("percent", Math.max(0, Math.min(100, v.percent()))).put("elapsed", RunProgress.elapsed(v.elapsedSeconds())).put("report", v.report())
                .put("mine", !PhoneRules.phoneOfJob(raw.path("args")).isEmpty() && PhoneRules.phoneOfJob(raw.path("args")).equals(phoneId)).put("active", v.active());
        if (!v.active() && !v.result().isBlank() && v.report().isEmpty()) r.put("said", Acquisitions.compress(v.result(), 300));   // a run that ended without a report says why (a cap, a stop, a failure)
        if (!v.report().isEmpty()) { try { Investigation inv = store.investigation(v.report()); if (inv != null) r.put("title", inv.title()); } catch (IOException | RuntimeException ignored) { } }
        return r;
    }

    private static long millis(String iso) { try { return iso == null || iso.isBlank() ? 0 : Instant.parse(iso).toEpochMilli(); } catch (RuntimeException e) { return 0; } }

    // ---------------------------------------------------------------- Read

    /** The recent reports, newest first. */
    static ObjectNode reads(LibraryStore store) throws IOException {
        ObjectNode o = M.createObjectNode();
        ArrayNode list = o.putArray("reports");
        Path dir = store.investigationsDir();
        if (!Files.isDirectory(dir)) return o;
        List<Path> files;
        try (var l = Files.list(dir)) { files = l.filter(p -> p.getFileName().toString().matches("I-\\d{4,}-.+\\.md")).sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString())).limit(30).toList(); }
        for (Path p : files) {
            String id = p.getFileName().toString().replaceAll("\\.md$", "");
            try { Investigation inv = store.investigation(id); if (inv != null) list.addObject().put("id", id).put("title", inv.title()).put("day", inv.recordedAt().length() >= 10 ? Chrome.now().day(inv.recordedAt().substring(0, 10)) : "").put("state", t(inv.state().name())); }
            catch (IOException | RuntimeException unreadable) { /* left off the list */ }
        }
        return o;
    }

    /**
     * One report or claim laid out for a phone: its title, its line of state, its words, its sources and its claims. Not found: the plain
     * refusal. Read from the record itself, not from the protocol's full entry, which looks every reference up among the captured pages
     * and builds each claim's own entry, none of which the page shows: measured 2026-11-25, that was 90 ms to 1.7 s of a report's open,
     * and everything the page does show 1 to 18 ms.
     */
    static String read(LibraryStore store, Patrons.Patron patron, String id) throws IOException {
        if (!LibraryStore.safeName(id) || !id.matches("[IF]-\\d{4,}-[A-Za-z0-9_-]+")) throw ProtocolError.notFound("Not found.");
        boolean report = id.startsWith("I-");
        Investigation inv = report ? store.investigation(id) : null;
        Finding claim = report ? null : store.finding(id);
        if (inv == null && claim == null) throw ProtocolError.notFound("Not found.");
        String mine = inv == null ? "" : ReadIn.of(store, patron), written = inv == null ? "" : Translate.writtenIn(inv);
        Translate.Translated tr = inv != null && !mine.isEmpty() && !mine.equals(written) ? Translate.currentReport(store, inv, mine) : null;
        String title = tr != null ? tr.words().getOrDefault(inv.title(), inv.title()) : report ? inv.title() : claim.title();
        String body = tr != null ? tr.markdown() : report ? inv.body() : claim.body().strip();
        String state = report ? inv.state().name() : claim.state().name(), recorded = report ? inv.recordedAt() : claim.recordedAt();
        List<String> sources = new ArrayList<>();
        if (report) for (LibraryProtocol.Reference r : LibraryProtocol.references(inv.body())) sources.add(r.locator());
        else for (var s : claim.sources()) sources.add(s.locator());
        StringBuilder b = new StringBuilder("<article class=\"entry\"><h1>").append(Pages.esc(title)).append("</h1><p class=\"k\">")
                .append(Pages.esc(report ? t("report") : t("finding"))).append(" · ").append(Pages.badge(state)).append(" · ").append(Pages.when(recorded)).append("</p>");
        b.append("<div class=\"body\">").append(Pages.withRefs(Pages.md(body), body)).append("</div>");
        if (!sources.isEmpty()) {
            b.append("<h2>").append(t("Sources")).append("</h2><ul>");
            for (String s : sources) b.append("<li>").append(Pages.locatorLink(s)).append("</li>");
            b.append("</ul>");
        }
        if (report && !inv.findings().isEmpty()) {
            b.append("<h2>").append(t("Claims that came out of it")).append("</h2><ul>");
            for (String f : inv.findings()) b.append("<li>").append(Pages.idLink(f)).append(Pages.claimTitle(store, f)).append("</li>");
            b.append("</ul>");
        }
        return phoneLinks(b.append("</article>").toString());
    }

    // ---------------------------------------------------------------- links that stay on the phone page

    private static final Pattern ANCHOR = Pattern.compile("<a\\s+([^>]*?)href=\"(/[^\"]*)\"([^>]*)>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern IMG = Pattern.compile("<img\\s[^>]*?src=\"([^\"]*)\"[^>]*>");
    private static final Pattern ALT = Pattern.compile("alt=\"([^\"]*)\"");
    private static final Pattern ENTRY_HREF = Pattern.compile("/entry/([IF]-\\d{4,}-[A-Za-z0-9_-]+)(?:[?#].*)?");
    private static final Pattern IMAGE_SRC = Pattern.compile("/images/([A-Za-z0-9_-][A-Za-z0-9._-]{0,150})");

    /**
     * The web app's HTML made for the phone door: a link to an entry opens it on the phone page, a picture the library keeps comes through
     * the door, a form of the chat posts to the phone's own call; a link to any other page of the web app is its words (that page does not
     * exist at this address), and any other picture its alternative words. Links to the web stay as the web app writes them.
     */
    static String phoneLinks(String html) {
        if (html == null || html.isEmpty()) return "";
        Matcher m = ANCHOR.matcher(html);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            Matcher e = ENTRY_HREF.matcher(m.group(2));
            String keep = e.matches() ? "<a " + m.group(1) + "href=\"/m/read/" + e.group(1) + "\"" + m.group(3) + ">" + m.group(4) + "</a>" : "<span class=\"was-link\">" + m.group(4) + "</span>";
            m.appendReplacement(b, Matcher.quoteReplacement(keep));
        }
        m.appendTail(b);
        Matcher i = IMG.matcher(b.toString());
        StringBuilder c = new StringBuilder();
        while (i.find()) {
            Matcher src = IMAGE_SRC.matcher(i.group(1));
            String keep;
            if (src.matches() && !src.group(1).contains("..")) keep = i.group().replace("src=\"" + i.group(1) + "\"", "src=\"/m/images/" + src.group(1) + "\"");
            else { Matcher alt = ALT.matcher(i.group()); keep = alt.find() ? "<span class=\"was-link\">" + alt.group(1) + "</span>" : ""; }
            i.appendReplacement(c, Matcher.quoteReplacement(keep));
        }
        i.appendTail(c);
        return c.toString().replace("action=\"/chat\"", "action=\"/m/ask\"");
    }

    // ---------------------------------------------------------------- the document, its script, its icons, its manifest

    /** The icons by address, each a file of the program's own (made by imgs/make-phone-icon.py): the home-screen one and the manifest's. */
    static final Map<String, String> ICONS = Map.of("/m/icon.png", "phone-icon.png", "/m/icon-192.png", "phone-icon-192.png",
            "/m/icon-512.png", "phone-icon-512.png", "/m/icon-512-maskable.png", "phone-icon-512-maskable.png");
    private static final Map<String, byte[]> ICON_BYTES = new ConcurrentHashMap<>();

    static byte[] icon(String path) {
        return ICON_BYTES.computeIfAbsent(ICONS.getOrDefault(path, "phone-icon.png"), name -> {
            try (InputStream in = PhonePage.class.getResourceAsStream(name)) { return in == null ? LibrarianDaemon.favicon() : in.readAllBytes(); }
            catch (IOException e) { return LibrarianDaemon.favicon(); }
        });
    }

    /**
     * The web-app manifest: what a phone installs. Its name is the program's, not the library's: an unpaired sender may read it, and
     * the door tells an unpaired sender nothing about the library. Its colours are the night of the palette the page wears by default.
     */
    static String manifest() {
        String night = Pages.colour(Pages.NIGHT, "--paper");
        ObjectNode m = M.createObjectNode();
        m.put("id", "/m").put("start_url", "/m").put("scope", "/m").put("display", "standalone").put("name", "ResearchZosho").put("short_name", "ResearchZosho")
         .put("background_color", night).put("theme_color", night);
        ArrayNode icons = m.putArray("icons");
        icons.addObject().put("src", "/m/icon-192.png").put("sizes", "192x192").put("type", "image/png").put("purpose", "any");
        icons.addObject().put("src", "/m/icon-512.png").put("sizes", "512x512").put("type", "image/png").put("purpose", "any");
        icons.addObject().put("src", "/m/icon-512-maskable.png").put("sizes", "512x512").put("type", "image/png").put("purpose", "maskable");
        return m.toString();
    }

    /** The words the script needs, in the language of the request. */
    static String words() {
        ObjectNode w = M.createObjectNode();
        w.put("ask", t("Ask")).put("runs", t("Runs")).put("read", t("Read")).put("send", t("Send")).put("picture", t(PICTURE)).put("placeholder", t("Ask the Librarian…"))
         .put("newConv", t("New conversation")).put("notPaired", t(NOT_PAIRED)).put("howToPair", t(HOW_TO_PAIR)).put("pairLink", t(PAIR_LINK)).put("pairFirst", t(PAIR_FIRST)).put("pair", t(PAIR))
         .put("home", t(HOME)).put("homeAndroid", t(HOME_ANDROID)).put("homeIphone", t(HOME_IPHONE)).put("going", t(GOING)).put("since", t(SINCE)).put("recent", t(RECENT)).put("nothing", t(NOTHING_GOING)).put("noReports", t(NO_REPORTS)).put("stop", t("stop"))
         .put("report", t("the report")).put("back", t("Back")).put("thinking", t("The Librarian is thinking… this can take a while. You can type the next thing meanwhile."))
         .put("writing", t("Being written")).put("lost", t("The reply did not arrive here; the conversation keeps it. Reload the page in a moment."))
         .put("opening", t(OPENING)).put("loading", t(LOADING)).put("sending", t(SENDING)).put("stopping", t("stopping…")).put("again", t("Try again")).put("notBack", t(NOT_BACK));
        w.put("tooBig", t(PICTURES_ONLY, (PhoneDoor.ASK_BYTES >> 20) + " MB")).put("maxBytes", PhoneDoor.ASK_BYTES).put("maxFiles", PhoneDoor.ASK_FILES);   // the page says the size before it sends
        return w.toString();
    }

    static String html() {
        return "<!doctype html><html lang=\"" + Pages.esc(Chrome.now().code) + "\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"
                + "<meta name=\"apple-mobile-web-app-capable\" content=\"yes\"><meta name=\"mobile-web-app-capable\" content=\"yes\">"
                + "<meta name=\"apple-mobile-web-app-title\" content=\"ResearchZosho\">"
                + "<meta name=\"color-scheme\" content=\"dark\"><meta name=\"theme-color\" content=\"" + Pages.colour(Pages.NIGHT, "--paper") + "\">"
                + "<link rel=\"apple-touch-icon\" href=\"/m/icon.png\"><link rel=\"icon\" href=\"/m/icon.png\"><link rel=\"manifest\" href=\"/m/app.webmanifest\"><title>ResearchZosho</title>"
                + "<script src=\"/m/phone.js\"></script><style>" + CSS + "</style></head>"   // the script first: it sets the look before the page is drawn
                + "<body data-words=\"" + Pages.esc(words()) + "\">"
                + "<header><b id=\"lib\">ResearchZosho</b><span class=\"look\"><button type=\"button\" data-look=\"dark\">" + Pages.esc(t("dark")) + "</button>·<button type=\"button\" data-look=\"light\">"
                + Pages.esc(t("light")) + "</button></span><button id=\"new\" class=\"quiet\" hidden></button></header>"
                + "<main><section id=\"out\" hidden><h1></h1><p id=\"out-how\"></p><p id=\"out-first\" class=\"k\" hidden></p><button id=\"pair\" class=\"primary\" hidden></button><p id=\"pair-said\" class=\"err\"></p></section>"
                + "<section id=\"t-ask\" class=\"tab\" hidden><div id=\"conv\"></div><p id=\"ask-said\" class=\"err\"></p>"
                + "<form id=\"say\"><textarea name=\"say\" rows=\"2\"></textarea><div class=\"row\"><label class=\"btn quiet\"><span id=\"pic-word\"></span><input type=\"file\" name=\"file\" accept=\"image/*\" multiple hidden></label>"
                + "<span id=\"pics\" class=\"k\"></span><button class=\"primary\" id=\"send\"></button></div></form>"
                + "<div id=\"homebox\" hidden><button id=\"home\" class=\"quiet\"></button><p id=\"home-said\" class=\"k\"></p></div></section>"
                + "<section id=\"t-runs\" class=\"tab\" hidden></section><section id=\"t-read\" class=\"tab\" hidden></section></main>"
                + "<nav id=\"tabs\" hidden><button data-tab=\"ask\"></button><button data-tab=\"runs\"><span></span> <b id=\"badge\" hidden></b></button><button data-tab=\"read\"></button></nav>"
                + "</body></html>";
    }

    /**
     * The web app's palette ({@link Pages#NIGHT}, {@link Pages#DAY}) under the page's own names; dark unless the person chose light
     * with the switch in the header, whatever the phone's own setting. Control edges are the palette's third ink, so a button or the
     * box stands out from the page by at least 3:1 in both looks; the rules between things stay the palette's faint ones.
     */
    static final String CSS = ":root{color-scheme:dark;" + Pages.NIGHT + ";--bg:var(--paper);--fg:var(--ink);--k:var(--ink-2);--line:var(--rule);--edge:var(--ink-3);"
            + "--acc:var(--accent);--on-acc:var(--on-accent);--box:var(--paper-2);--err:var(--dispute)}"
            + ":root[data-theme=light]{color-scheme:light;" + Pages.DAY + "}"
            + "[hidden]{display:none!important}*{box-sizing:border-box}html,body{margin:0;background:var(--bg);color:var(--fg);font:17px/1.45 system-ui,-apple-system,\"Segoe UI\",sans-serif;-webkit-text-size-adjust:100%}"
            + "body{padding:env(safe-area-inset-top) 0 calc(64px + env(safe-area-inset-bottom));overflow-x:hidden}"
            + "header{display:flex;gap:8px;align-items:center;padding:10px 16px;border-bottom:1px solid var(--line)}#lib{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
            + ".look{font-size:.85em;color:var(--k);white-space:nowrap}.look button{border:0;background:none;min-height:44px;padding:0 4px;color:var(--k);text-decoration:underline;text-decoration-color:var(--line);text-underline-offset:.2em}"
            + ":root:not([data-theme=light]) .look [data-look=dark],:root[data-theme=light] .look [data-look=light]{color:var(--fg);text-decoration-color:var(--acc)}"
            + "main{padding:12px 16px;max-width:720px;margin:0 auto}h1{font-size:1.3em;margin:.4em 0}h2{font-size:1.1em;margin:1.2em 0 .4em}"
            + "a{color:var(--acc)}p,li{overflow-wrap:anywhere}pre{white-space:pre-wrap;overflow-wrap:anywhere}img{max-width:100%;height:auto}table{display:block;overflow-x:auto;max-width:100%}"
            + ".k,.k3,.small{color:var(--k);font-size:.9em}.err{color:var(--err)}button,.btn{font:inherit;border-radius:10px;border:1px solid var(--edge);background:var(--box);color:var(--fg);padding:8px 14px;min-height:44px}"
            + "button.primary{background:var(--acc);color:var(--on-acc);border-color:var(--acc)}button.quiet,.btn.quiet{background:transparent}"
            + "textarea{width:100%;font:inherit;padding:10px;border-radius:10px;border:1px solid var(--edge);background:var(--box);color:var(--fg)}textarea::placeholder{color:var(--ink-3)}"
            + ".row{display:flex;gap:8px;align-items:center;margin-top:8px}.row #send{margin-left:auto}"
            + ".turn{margin:10px 0;padding:10px 12px;border-radius:12px;background:var(--box);border:1px solid var(--line)}.turn.you{background:transparent}"
            + ".who,.mark{font-size:.8em;color:var(--k)}.mark .d{margin-left:.5em}.trace,.cites,.att{font-size:.85em;color:var(--k)}.ask .opts{display:flex;flex-wrap:wrap;gap:8px}.inline{display:inline}"
            + "nav{position:fixed;left:0;right:0;bottom:0;display:flex;background:var(--bg);border-top:1px solid var(--line);padding-bottom:env(safe-area-inset-bottom)}"
            + "nav button{flex:1;border:0;border-radius:0;background:transparent}nav button.on{color:var(--acc);font-weight:600}"
            + "#badge{background:var(--acc);color:var(--on-acc);border-radius:9px;padding:0 6px;font-size:.8em}"
            + ".run{padding:10px 0;border-bottom:1px solid var(--line)}.bar{height:6px;background:var(--line);border-radius:3px;overflow:hidden;margin:6px 0}.bar i{display:block;height:100%;background:var(--acc)}"
            + ".rep{display:block;padding:10px 0;border-bottom:1px solid var(--line);text-decoration:none;color:var(--fg)}.badge{font-size:.85em}"
            + ".live .say p{white-space:pre-wrap}"
            // the waiting line: the palette's quiet words with a small wheel in the accent, at most 20 s of turning; a failure in the error colour
            + ".wait{display:flex;flex-wrap:wrap;align-items:center;gap:8px;color:var(--k);font-size:.9em;margin:8px 0}.wait.failed{color:var(--err)}"
            + ".spin{flex:none;width:14px;height:14px;border:2px solid var(--line);border-top-color:var(--acc);border-radius:50%;animation:rz-turn .8s linear 25}"
            + "@keyframes rz-turn{to{transform:rotate(360deg)}}@media (prefers-reduced-motion:reduce){.spin{animation:none}}"
            + ".busy{opacity:.6;pointer-events:none}";

    /**
     * The page's script: what the page shows comes from /m/state; a pairing code in the address (/m/pair?c=…) is spent only by this
     * script's own POST, and only when the browser is not paired; every call that changes something carries the page's header. The
     * page's words come in its body's data, never from a request. Nothing it shows is HTML from a call but the conversation and a report,
     * which the library writes and escapes ({@link Pages#chatView}, {@link #read}). It is loaded in the head, so its first lines set the
     * look the browser remembered before anything is drawn (the web app's key, rz.theme, in the phone page's own storage: dark unless it
     * says light) and the rest waits for the page.
     */
    static final String JS = "(function(){'use strict';"
            + "function look(v,keep){var r=document.documentElement;if(v==='light')r.setAttribute('data-theme','light');else r.removeAttribute('data-theme');"
            + "var m=document.querySelector('meta[name=theme-color]'),c=document.querySelector('meta[name=color-scheme]');"
            + "if(m)m.setAttribute('content',v==='light'?'" + Pages.colour(Pages.DAY, "--paper") + "':'" + Pages.colour(Pages.NIGHT, "--paper") + "');if(c)c.setAttribute('content',v);"
            + "if(keep)try{localStorage.setItem('rz.theme',v)}catch(e){}"
            + "var bs=document.querySelectorAll('[data-look]');for(var i=0;i<bs.length;i++)bs[i].setAttribute('aria-pressed',bs[i].getAttribute('data-look')===v?'true':'false')}"
            + "var v=null;try{v=localStorage.getItem('rz.theme')}catch(e){}v=v==='light'?'light':'dark';look(v,false);"
            + "document.addEventListener('DOMContentLoaded',function(){var lb=document.querySelectorAll('[data-look]');for(var i=0;i<lb.length;i++)lb[i].addEventListener('click',function(){look(this.getAttribute('data-look'),true)});look(v,false)})})();"
            + "document.addEventListener('DOMContentLoaded',function(){'use strict';var W=JSON.parse(document.body.getAttribute('data-words')||'{}');function $(i){return document.getElementById(i)}"
            + "var H={'X-RZ-Phone':'1'},session='',tab='ask',busy=false,runsTimer=null;"
            + "function get(u){return fetch(u,{credentials:'same-origin'}).then(function(r){return r.json().then(function(j){if(!r.ok){var e=new Error(j.error||String(r.status));e.status=r.status;throw e}return j},function(){var e=new Error(String(r.status));e.status=r.status;throw e})})}"
            // the waiting line: shown at once on the tap, the tapped thing held until the answer, and after a failure or 20 seconds a
            // plain sentence with Try again; its little wheel turns 25 times (20 s) at most and not at all for a phone that asks for no motion
            + "function after(x){return function(n){x.parentNode.insertBefore(n,x.nextSibling)}}function top(c){return function(n){c.insertBefore(n,c.firstChild)}}"
            + "function waitOn(t,word,put,run){if(t&&t.getAttribute('aria-busy')==='true')return;var n=el('p','wait');n.setAttribute('role','status');"
            + "function mark(on){if(!t)return;t.classList.toggle('busy',on);if(on)t.setAttribute('aria-busy','true');else t.removeAttribute('aria-busy');if(t.tagName==='BUTTON')t.disabled=on}"
            + "function failed(m){n.className='wait failed';n.textContent=(m||W.notBack)+' ';var b=el('button','quiet',W.again);b.type='button';b.addEventListener('click',go);n.appendChild(b);mark(false)}"
            + "function go(){n.className='wait';n.textContent='';n.appendChild(el('i','spin'));n.appendChild(document.createTextNode(word));mark(true);if(!n.parentNode)put(n);var end=false,tm=setTimeout(function(){if(!end)failed()},20000);"
            + "run().then(function(){end=true;clearTimeout(tm);mark(false);if(n.parentNode)n.remove()},function(e){end=true;clearTimeout(tm);failed(e&&e.status&&e.message?e.message:null)})}go()}"
            // a message with pictures goes by XMLHttpRequest, which says how much of it has gone; a send that moves nothing for 20 seconds is
            // given up before the library has it, so sending it again is safe
            + "function xpost(u,fd,sent){return new Promise(function(ok,no){var x=new XMLHttpRequest(),up=false,last=Date.now(),tm=setInterval(function(){if(!up&&Date.now()-last>20000){clearInterval(tm);x.abort();var e=new Error(W.notBack);e.stalled=true;no(e)}},1000);"
            + "x.open('POST',u);x.setRequestHeader('X-RZ-Phone','1');x.upload.onprogress=function(ev){last=Date.now();if(ev.lengthComputable)sent(Math.round(100*ev.loaded/ev.total))};x.upload.onload=function(){up=true;clearInterval(tm);sent(-1)};"
            + "x.onload=function(){clearInterval(tm);var j=null;try{j=JSON.parse(x.responseText)}catch(err){}if(x.status>=200&&x.status<300&&j)ok(j);else{var e=new Error(j&&j.error||String(x.status));e.status=x.status;no(e)}};"
            + "x.onerror=function(){clearInterval(tm);no(new Error(W.lost))};x.send(fd)})}"
            + "function post(u,body){var o={method:'POST',credentials:'same-origin',headers:Object.assign({},H)};if(body instanceof FormData)o.body=body;else{o.headers['Content-Type']='application/x-www-form-urlencoded';o.body=new URLSearchParams(body||{}).toString()}"
            + "return fetch(u,o).then(function(r){return r.json().then(function(j){if(!r.ok){var e=new Error(j.error||String(r.status));e.status=r.status;throw e}return j},function(){var e=new Error(String(r.status));e.status=r.status;throw e})})}"
            + "function codeHere(){var c=new URLSearchParams(location.search).get('c');return location.pathname==='/m/pair'&&c?{code:c}:null}"
            + "function words(){$('pair').textContent=W.pair;$('send').textContent=W.send;$('pic-word').textContent=W.picture;$('new').textContent=W.newConv;$('home').textContent=W.home;"
            + "document.querySelector('[name=say]').placeholder=W.placeholder;var b=document.querySelectorAll('nav button');b[0].textContent=W.ask;b[1].firstChild.textContent=W.runs;b[2].textContent=W.read}"
            + "function out(){$('out').hidden=false;$('out').querySelector('h1').textContent=W.notPaired;var c=codeHere();if(c){$('out-how').textContent=W.pairLink;$('out-first').textContent=W.pairFirst;$('out-first').hidden=false;$('pair').hidden=false}else $('out-how').textContent=W.howToPair}"
            + "function pair(c){$('pair').disabled=true;post('/m/pair',{c:c.code}).then(function(){if(location.pathname==='/m/pair'){location.replace('/m');return}start()}).catch(function(e){$('pair-said').textContent=e.message;$('pair').disabled=false;out()})}"
            + "function start(){get('/m/state').then(function(s){if(!s.paired){out();return}"
            + "$('out').hidden=true;$('lib').textContent=s.library||'ResearchZosho';$('tabs').hidden=false;$('new').hidden=false;if(location.pathname==='/m/pair'){location.replace('/m');return}"
            + "var standalone=window.navigator.standalone||window.matchMedia('(display-mode: standalone)').matches;$('homebox').hidden=standalone;badge(s['new']);show(tab);conv('')}).catch(function(){out()})}"
            + "function show(t){tab=t;['ask','runs','read'].forEach(function(n){$('t-'+n).hidden=n!==t});var b=document.querySelectorAll('nav button');for(var i=0;i<b.length;i++)b[i].classList.toggle('on',b[i].getAttribute('data-tab')===t);"
            + "if(t==='runs')runs(true,true);if(t==='read')reads(true)}"
            + "function badge(n){$('badge').hidden=!n;$('badge').textContent=n||'';document.title=(n?'('+n+') ':'')+'ResearchZosho'}"
            // Ask
            + "function conv(sid){return get('/m/conv'+(sid?'?session='+encodeURIComponent(sid):'')).then(function(j){put(j)})}"
            + "function put(j){session=j.session||session;var c=$('conv');c.innerHTML=j.html||'';wire(c);var s=c.querySelector('#chat-scroll');window.scrollTo(0,document.body.scrollHeight)}"
            + "function wire(root){var fs=root.querySelectorAll('form.page-say');for(var i=0;i<fs.length;i++)fs[i].addEventListener('submit',function(ev){ev.preventDefault();var f=ev.target;send(new FormData(f),f.querySelector('[name=say]').value)});links(root)}"
            + "function links(root){var as=root.querySelectorAll('a[href^=\"/m/read/\"]');for(var i=0;i<as.length;i++)as[i].addEventListener('click',function(ev){ev.preventDefault();var id=this.getAttribute('href').slice(8);if(tab!=='read')show('read');open(id,this)})}"
            + "var fileIn=document.querySelector('[name=file]');fileIn.addEventListener('change',function(){$('pics').textContent=fileIn.files.length?fileIn.files.length+' \\u00d7 '+W.picture:''});"
            + "$('say').addEventListener('submit',function(ev){ev.preventDefault();var fd=new FormData($('say'));if(!(fd.get('say')||'').trim()&&!fileIn.files.length)return;var tb=0;for(var i=0;i<fileIn.files.length;i++)tb+=fileIn.files[i].size;if(tb>W.maxBytes||fileIn.files.length>W.maxFiles){$('ask-said').textContent=W.tooBig;return}fd.set('session',session);send(fd,fd.get('say'))});"
            + "function send(fd,shown){if(busy)return;busy=true;$('ask-said').textContent='';if(!fd.get('session'))fd.set('session',session);var c=$('conv'),me=document.createElement('div');me.className='turn you';me.textContent=shown||'';var th=document.createElement('p');th.className='k';th.textContent=W.thinking;c.appendChild(me);c.appendChild(th);"
            + "var pic=fd.getAll('file').some(function(f){return f&&f.size>0}),st=null;if(pic){th.className='wait';th.textContent='';th.appendChild(el('i','spin'));st=document.createTextNode(W.sending);th.appendChild(st)}"
            + "var live=null,lt=setInterval(function(){get('/m/live?session='+encodeURIComponent(session)).then(function(j){if(!busy||!j.text)return;if(!live){live=document.createElement('div');live.className='turn live';live.innerHTML='<div class=\"mark\"></div><div class=\"say\"><p></p></div><button type=\"button\">'+'</button>';live.querySelector('.mark').textContent=W.writing;var sb=live.querySelector('button');sb.textContent=W.stop;sb.addEventListener('click',function(){waitOn(sb,W.stopping,after(sb),function(){return post('/m/stop',{session:session}).then(function(){sb.remove()})})});th.replaceWith(live)}live.querySelector('p').textContent=j.text})},900);"
            + "(pic?xpost('/m/ask',fd,function(pc){if(pc<0){th.className='k';th.textContent=W.thinking}else st.textContent=W.sending+' '+pc+'%'}):post('/m/ask',fd)).then(function(j){clearInterval(lt);busy=false;$('say').reset();$('pics').textContent='';put(j);runs(false)}).catch(function(e){clearInterval(lt);busy=false;"
            + "if(e.stalled){th.className='wait failed';th.textContent=W.notBack+' ';var b=el('button','quiet',W.again);b.type='button';b.addEventListener('click',function(){me.remove();th.remove();send(fd,shown)});th.appendChild(b);return}"
            + "th.className='k';th.textContent=e.status===409||e.status===429||e.status===400||e.status===413?e.message:W.lost})}"
            + "$('new').addEventListener('click',function(){var nb=$('new');waitOn(nb,W.loading,top($('t-ask')),function(){return post('/m/new',{}).then(function(j){session=j.session;show('ask');return conv(session)})})});"
            // the two taps for the phone the browser says it is on; both when it says neither or both (an iPad may call itself a Mac)
            + "$('home').addEventListener('click',function(){var u=navigator.userAgent||'',a=/Android/i.test(u),i=/iPhone|iPad|iPod/i.test(u)||(/Macintosh/.test(u)&&navigator.maxTouchPoints>1);"
            + "$('home-said').textContent=a&&!i?W.homeAndroid:i&&!a?W.homeIphone:W.homeIphone+' '+W.homeAndroid});"
            // Runs
            + "function el(t,c,x){var e=document.createElement(t);if(c)e.className=c;if(x!=null)e.textContent=x;return e}"
            + "function runRow(r){var d=el('div','run');d.appendChild(el('div','',r.name));var k=el('div','k',r.stage+' \\u00b7 '+r.elapsed+(r.active?' \\u00b7 '+r.percent+'%':''));d.appendChild(k);"
            + "if(r.active){var b=el('div','bar'),i=el('i');i.style.width=r.percent+'%';b.appendChild(i);d.appendChild(b);if(r.mine){var s=el('button','quiet',W.stop);s.addEventListener('click',function(){waitOn(s,W.stopping,after(s),function(){return post('/m/runs/stop',{job_id:r.id}).then(function(){runs(true)})})});d.appendChild(s)}}"
            + "if(r.said)d.appendChild(el('p','k',r.said));if(r.report){var a=el('a','',r.title||W.report);a.href='/m/read/'+r.report;a.addEventListener('click',function(ev){ev.preventDefault();show('read');open(r.report,a)});d.appendChild(a)}return d}"
            + "function runs(looked,shown){if(runsTimer)clearTimeout(runsTimer);var go=function(){return get('/m/runs').then(function(j){var t=$('t-runs');t.innerHTML='';if(j.month)t.appendChild(el('p','k',j.month));"
            + "[['going',W.going],['since',W.since],['recent',W.recent]].forEach(function(g){var rs=j[g[0]]||[];if(!rs.length&&g[0]!=='going')return;t.appendChild(el('h2','',g[1]));if(!rs.length)t.appendChild(el('p','k',W.nothing));rs.forEach(function(r){t.appendChild(runRow(r))})});"
            + "badge(tab==='runs'?0:(j.since||[]).length);if(looked&&tab==='runs'&&(j.since||[]).length)post('/m/seen',{});runsTimer=setTimeout(function(){runs(tab==='runs')},document.hidden?60000:15000)},function(e){runsTimer=setTimeout(function(){runs(false)},30000);throw e})};"
            + "if(shown)waitOn(null,W.loading,top($('t-runs')),go);else go().catch(function(){})}"
            + "document.addEventListener('visibilitychange',function(){if(!document.hidden&&!$('tabs').hidden)runs(tab==='runs')});"
            // Read
            // the Read tab shows the newest of what was asked: a list asked before a report, or a report before another, is not drawn over it
            + "var readSeq=0;"
            + "function reads(shown){var my=++readSeq,go=function(){return get('/m/read').then(function(j){if(my!==readSeq)return;var t=$('t-read');t.innerHTML='';if(!j.reports.length)t.appendChild(el('p','k',W.noReports));j.reports.forEach(function(r){var a=el('a','rep');a.href='/m/read/'+r.id;a.appendChild(el('div','',r.title));a.appendChild(el('div','k',r.day+' \\u00b7 '+r.state));"
            + "a.addEventListener('click',function(ev){ev.preventDefault();open(r.id,a)});t.appendChild(a)})})};if(shown)waitOn(null,W.loading,top($('t-read')),go);else go().catch(function(){})}"
            + "function open(id,from){if(from&&from.getAttribute&&from.getAttribute('aria-busy')==='true')return;var my=++readSeq,inRead=from&&from.closest&&from.closest('#t-read');waitOn(inRead?from:null,W.opening,inRead?after(from):top($('t-read')),function(){return get('/m/read/'+id).then(function(j){if(my!==readSeq)return;"
            + "var t=$('t-read');t.innerHTML='';var b=el('button','quiet',W.back);b.addEventListener('click',function(){reads(true)});t.appendChild(b);var d=el('div');d.innerHTML=j.html;t.appendChild(d);links(d);window.scrollTo(0,0)})})}"
            + "var nb=document.querySelectorAll('nav button');for(var i=0;i<nb.length;i++)nb[i].addEventListener('click',function(){show(this.getAttribute('data-tab'))});"
            + "$('pair').addEventListener('click',function(){var c=codeHere();if(c)pair(c)});"
            + "words();start()});";
}
