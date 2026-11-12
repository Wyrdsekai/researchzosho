package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The chat as the home (0.7.0): the conversation with a mark on each answer saying where it came from, the claims it cites with their
 * standing, the composer that stays, the runs strip beside it, the conversations behind a button; and Past, where the library's counts,
 * each field's section, what changed and the open questions went.
 */
class ChatHomeTest {

    static final ObjectMapper M = new ObjectMapper();

    static ObjectNode msg(String role, String content) { ObjectNode m = M.createObjectNode(); m.put("role", role); m.put("content", content); return m; }

    static HttpResponse<String> get(HttpClient c, String url) throws Exception {
        return c.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theHomeIsTheChatWithItsMarksCitesStripAndConversations(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Finding accepted = new Finding("F-0001-saros", "The saros is 223 synodic months", List.of("eclipses"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", "2026-10-14T10:00:00Z", "2026-10-14", Finding.Volatility.stable, "", List.of(new Finding.Source("https://eclipse.gsfc.nasa.gov/SEsaros/SEsaros.html", "", "the catalogue"), new Finding.Source("https://en.wikipedia.org/wiki/Saros_(astronomy)", "", "the article")), List.of(), null, "The saros is 223 synodic months.");
        Finding draft = new Finding("F-0002-exeligmos", "The exeligmos is three saros cycles", List.of("eclipses"), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium,
                "model:test", "2026-10-14T10:00:00Z", "2026-10-14", Finding.Volatility.stable, "", List.of(new Finding.Source("https://en.wikipedia.org/wiki/Exeligmos", "", "the article")), List.of(), null, "Three saros cycles.");
        store.write(accepted); store.write(draft);
        Librarian.Session earlier = Librarian.Session.open(store);
        earlier.append(msg("user", "Which spectrometer makers raised money this year?"));
        earlier.append(msg("assistant", "None that the shelves know of."));
        Librarian.Session s = Librarian.Session.open(store);
        ObjectNode asked = msg("user", "What is the saros?"); asked.put("at", 1_000L); s.append(asked);
        ObjectNode calling = msg("assistant", ""); calling.putArray("tool_calls").addObject().put("id", "c1"); s.append(calling);
        ObjectNode tool = msg("tool", "…"); tool.put("name", "library_search"); tool.put("tool_call_id", "c1"); s.append(tool);
        ObjectNode answer = msg("assistant", "The saros is 223 synodic months [F-0001-saros]; the exeligmos is three of them [F-0002-exeligmos]."); answer.put("at", 1_600L); s.append(answer);
        s.append(msg("user", "/model"));
        ObjectNode lib = msg("assistant", "The models this library can talk to: none yet."); lib.put("by", "library"); s.append(lib);
        s.append(msg("user", "Find out who makes benchtop spectrometers"));
        ObjectNode calling2 = msg("assistant", ""); calling2.putArray("tool_calls").addObject().put("id", "c2"); s.append(calling2);
        ObjectNode tool2 = msg("tool", "{\"job_id\":\"J-001\"}"); tool2.put("name", "library_research"); tool2.put("tool_call_id", "c2"); s.append(tool2);
        s.append(msg("assistant", "Filed as a run."));
        ObjectNode nudge = msg("user", "Say it in words now, briefly, from what you have looked up."); nudge.put("by", "library"); s.append(nudge);
        s.append(msg("user", "And in your own words?"));
        s.append(msg("assistant", "A cycle of eclipses, roughly eighteen years."));
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            HttpResponse<String> home = get(c, base + "/");
            assertEquals(200, home.statusCode(), home.body());
            String h = home.body();
            assertTrue(h.contains("<body class=\"fill\">") && h.contains("<main class=\"chat\">") && !h.contains("<h1>"), "the home is the chat, filling the window, with no heading over it: " + h);
            assertTrue(h.contains("<a href=\"/\" aria-current=\"page\">Chat</a>"), "the rail marks it");
            for (String g : new String[]{"id=\"sessions-toggle\"", "<span class=\"title\">What is the saros?</span>", "href=\"/chat?new=1\"", "id=\"chat-view\"", "id=\"chat-scroll\" class=\"turns\"", "<form id=\"chat-say\" class=\"composer\"", "<textarea name=\"say\"",
                    "id=\"runs-strip\" class=\"strip side\"", "<aside class=\"sessions\" id=\"sessions\">", "id=\"scrim\"", "2 claims, 1 accepted · 0 reports", "href=\"/questions\""})
                assertTrue(h.contains(g), "the chat page lacks " + g + ": " + h);
            assertTrue(h.indexOf("id=\"chat-say\"") > h.indexOf("id=\"chat-view\""), "the composer comes after the changing part, outside it");
            // the turns: the person's words, and each answer's mark, trace and cites
            assertTrue(h.contains("<div class=\"turn you\"><div class=\"who\">You</div><div class=\"say\"><p>What is the saros?</p></div></div>"), h);
            assertTrue(h.contains("<div class=\"mark shelves\"><b>From the shelves</b><span class=\"d\">0.6 s</span></div><div class=\"trace\">searched the shelves</div>"), "an answer after a look-up is from the shelves, with the time it took and what was looked up: " + h);
            assertTrue(h.contains("<div class=\"mark library\"><b>From the library</b></div>"), "the library's own reply says so: " + h);
            assertTrue(h.contains("<div class=\"mark research\"><b>Research started</b></div><div class=\"trace\">filed a research run</div>"), "a run filed says so: " + h);
            assertTrue(h.contains("<div class=\"mark model\"><b>The model's own words</b></div>"), "an answer that looked nothing up says so: " + h);
            assertFalse(h.contains("Say it in words now"), "the library's nudge to the model is not shown as the person's words: " + h);
            assertTrue(h.contains("<a class=\"est\" href=\"/entry/F-0001-saros\" title=\"established: accepted\">●</a> <span class=\"src\">· eclipse.gsfc.nasa.gov · en.wikipedia.org</span>"), "an accepted claim is established, with its sources: " + h);
            assertTrue(h.contains("<a class=\"est no\" href=\"/entry/F-0002-exeligmos\" title=\"not yet: draft\">○</a> <span class=\"src\">· en.wikipedia.org</span>"), "a draft is not yet: " + h);
            // the strip is quiet for a conversation that follows nothing; the conversations drawer lists both, the open one marked, by day
            assertTrue(h.contains("data-active=\"0\"") && h.contains("Nothing running. The housekeeping runs at ") && !h.contains("class=\"rline"), h);
            assertTrue(h.contains("<div class=\"g\">Today</div>") && h.contains("\">What is the saros?<span class=\"w\">") && h.contains("Which spectrometer makers raised money this year?<span class=\"w\">") && h.contains("<a class=\"s on\" href=\"/chat?session=" + s.id + "\">"), h);
            assertEquals(200, get(c, base + "/chat").statusCode(), "the chat answers at its old address too");
            // Past: what the library holds and what changed
            HttpResponse<String> past = get(c, base + "/past");
            assertEquals(200, past.statusCode(), past.body());
            assertTrue(past.body().contains("<h1>Past</h1>") && past.body().contains("Claims: 2 (1 accepted") && past.body().contains("<a href=\"/past\" aria-current=\"page\">Past</a>"), past.body());
            String fragment = c.send(HttpRequest.newBuilder(URI.create(base + "/chat/runs?session=" + s.id)).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(fragment.startsWith("<div class=\"striphead\" data-active=\"0\">") && !fragment.contains("<html"), "the strip alone, for the page's own refresh: " + fragment);
            // a fresh conversation has no file until its first message: its strip must still come back whole (the section emptied on the page, 2026-11-08)
            String fresh = get(c, base + "/chat?new=1").body();
            int at = fresh.indexOf("data-session=\""); String freshId = fresh.substring(at + 14, fresh.indexOf('"', at + 14));
            assertNull(Librarian.Session.resume(store, freshId), "nothing on disk yet for " + freshId);
            String freshStrip = c.send(HttpRequest.newBuilder(URI.create(base + "/chat/runs?session=" + freshId)).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(freshStrip.startsWith("<div class=\"striphead\"") && freshStrip.contains(">Runs<"), "the strip for a conversation not yet on disk: " + freshStrip);
            String noneStrip = c.send(HttpRequest.newBuilder(URI.create(base + "/chat/runs?session=C-no-such")).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(noneStrip.startsWith("<div class=\"striphead\""), "never an empty answer: " + noneStrip);
        } finally { d.stop(); }
    }
}
