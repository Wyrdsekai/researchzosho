package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conversations stay short (the keeper, 2026-11-08: the chat is a way to set up a research run and ask a thing or two). Through the real chat
 * post: a conversation quiet for a day is not continued, the next message opens a new one whose reply begins by saying so, and the earlier
 * one stays on the list; one quiet for two hours continues. The model sees the last twelve turns and a summary of what came before, from
 * the session's own records. A New conversation button sits beside the box, in Japanese too. The notice for a report on the shelves ends
 * by suggesting a fresh conversation.
 */
class ChatConversationsTest {
    static final ObjectMapper M = new ObjectMapper();
    String home;

    @BeforeEach void isolate(@TempDir Path tmp) { home = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate(); }
    @AfterEach void restore() { System.setProperty("user.home", home); Config.invalidate(); }

    static ObjectNode msg(String role, String content, long at) { ObjectNode m = M.createObjectNode(); m.put("role", role); m.put("content", content); m.put("at", at); return m; }

    /** A conversation of one exchange whose last turn was {@code hoursAgo} hours ago. */
    static Librarian.Session aged(LibraryStore store, String words, double hoursAgo) throws Exception {
        Librarian.Session s = Librarian.Session.open(store);
        long at = System.currentTimeMillis() - (long) (hoursAgo * 3_600_000L);
        s.append(msg("user", words, at)); s.append(msg("assistant", "The shelves hold nothing on it yet.", at + 5_000));
        return s;
    }

    static String openSession(String body) { Matcher m = Pattern.compile("data-open-session=\"([^\"]+)\"").matcher(body); return m.find() ? m.group(1) : null; }

    @Test
    void aDaysQuietOpensANewConversationAndTwoHoursContinuesTheOld(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Librarian.Session old = aged(store, "What is the saros?", 25);
        Librarian.Session recent = aged(store, "Which gears were cut by hand?", 2);
        assertTrue(old.quietForADay(System.currentTimeMillis())); assertFalse(recent.quietForADay(System.currentTimeMillis())); assertFalse(Librarian.Session.open(store).quietForADay(System.currentTimeMillis()), "an empty conversation is not quiet, it is new");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            // the page's script posts to the day-old conversation: a new one is opened, the page is sent to it, and its first reply begins with the line
            HttpResponse<String> r = ChatStreamTest.post(c, base + "/chat", "page=1&session=" + old.id + "&say=which+models+do+we+have%3F");
            assertEquals(200, r.statusCode(), r.body());
            String fresh = openSession(r.body());
            assertNotNull(fresh, "the page is told which conversation to open: " + r.body());
            assertNotEquals(old.id, fresh);
            Librarian.Session opened = Librarian.Session.resume(store, fresh);
            List<ObjectNode> ms = opened.messages();
            assertEquals("user", ms.get(0).path("role").asText()); assertEquals("which models do we have?", ms.get(0).path("content").asText());
            String reply = ms.get(1).path("content").asText();
            System.out.println("[conversations] opening reply:\n" + reply);
            assertTrue(reply.startsWith("This is a new conversation; the earlier one, What is the saros? (" + old.day() + "), is on the list.\n\nThe default is the entry named default: "), reply);
            assertEquals(2, Librarian.Session.resume(store, old.id).messages().size(), "the earlier conversation is untouched");
            // both are on the list, the drawer shows both
            List<String> ids = Librarian.Session.list(store);
            assertTrue(ids.contains(old.id) && ids.contains(fresh) && ids.contains(recent.id), ids.toString());
            String page = c.send(HttpRequest.newBuilder(URI.create(base + "/chat?session=" + fresh)).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(page.contains("href=\"/chat?session=" + old.id + "\">What is the saros?") && page.contains("href=\"/chat?session=" + fresh + "\">which models do we have?"), "the drawer lists the earlier one and the new one: " + page);
            // a plain form post (no script) to the day-old conversation: sent on to the new one
            HttpResponse<String> plain = ChatStreamTest.post(c, base + "/chat", "session=" + old.id + "&say=which+models+do+we+have%3F");
            assertTrue(plain.statusCode() / 100 == 3 && plain.headers().firstValue("Location").orElse("").startsWith("/chat?session=C-"), plain.statusCode() + " " + plain.headers().map());
            assertFalse(plain.headers().firstValue("Location").orElse("").endsWith(old.id), "not the old one");
            // two hours of quiet: the same conversation goes on, and nothing says otherwise
            r = ChatStreamTest.post(c, base + "/chat", "page=1&session=" + recent.id + "&say=which+models+do+we+have%3F");
            assertEquals(200, r.statusCode(), r.body());
            assertNull(openSession(r.body()), "no other conversation is opened: " + r.body());
            Librarian.Session same = Librarian.Session.resume(store, recent.id);
            assertEquals(4, same.messages().size(), same.messages().toString());
            assertFalse(same.messages().get(3).path("content").asText().contains("This is a new conversation"), same.messages().get(3).toString());
        } finally { d.stop(); }
    }

    @Test
    void theModelSeesTwelveTurnsAndASummaryOfWhatCameBefore(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        long now = System.currentTimeMillis();
        store.write(new Investigation("I-0001-the-saros", "The saros, in short", Finding.State.accepted, "model:test", Instant.ofEpochMilli(now - 3_600_000).toString(), List.of(), List.of(), "## Answer\n\n223 months.\n"));
        PastFieldsTest.done(new Jobs(store, j -> ""), "J-0001", "", "What is the saros?", "I-0001-the-saros", now);
        Librarian.Session s = Librarian.Session.open(store);
        s.watch("J-0001");
        for (int i = 0; i < 162; i++) { s.append(msg("user", "ask " + i + " about the dial", now - 400_000L + i * 2_000L)); s.append(msg("assistant", "answer " + i, now - 399_000L + i * 2_000L)); }
        ObjectNode withFile = msg("user", "ask 162, with my notes", now - 50_000L); withFile.putObject("att").put("name", "notes.md").put("id", "2026-11-08-abcdef123456.md"); s.append(withFile);
        assertEquals(325, s.messages().size(), "a conversation of 325 rows");
        List<ArrayNode> seen = new ArrayList<>();
        Researcher.Drive drive = LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_status", "{}"), LibrarianChatTest.say("A short answer.")), seen);
        Librarian lib = new Librarian(store, drive, Librarian.person(), s);
        lib.say("and the last ask?");
        assertEquals(2, seen.size());
        ArrayNode first = seen.get(0);
        int users = 0, assistants = 0; for (var m : first) { if ("user".equals(m.path("role").asText())) users++; if ("assistant".equals(m.path("role").asText())) assistants++; }
        assertEquals(12, users, "twelve of the person's turns: " + first.size() + " rows");
        assertEquals(10, assistants, "the Librarian's replies among them; the last two asks are unanswered");
        assertEquals("system", first.get(0).path("role").asText());
        assertEquals("ask 152 about the dial", first.get(1).path("content").asText(), "the twelfth-last turn opens the history");
        String system = first.get(0).path("content").asText();
        int at = system.indexOf("Earlier in this conversation, before the turns shown, 152 turns were asked: \"ask 0 about the dial\"; \"ask 1 about the dial\"");
        assertTrue(at > 0, system);
        String summary = system.substring(at);
        System.out.println("[conversations] summary:\n" + summary);
        assertTrue(summary.contains("\"ask 9 about the dial\"; and 142 more.") && summary.contains(" Research runs this conversation filed: J-0001 (done; report [I-0001-the-saros] \"The saros, in short\").") && summary.endsWith(" What the shelves hold now, the tools say."), summary);
        assertFalse(summary.contains("notes.md"), "the attached file is in the turns shown, not the summary: " + summary);
        assertFalse(system.contains(Librarian.TOOL_RESULTS_RULE), "no tool result yet");
        String second = seen.get(1).get(0).path("content").asText();
        assertTrue(second.contains("Earlier in this conversation, before the turns shown, 152 turns were asked") && second.endsWith("\n\n" + Librarian.TOOL_RESULTS_RULE), "with a tool result in the turn, the rule ends the prompt, after the summary: " + second);
        // a short conversation has no summary
        Librarian.Session shortOne = Librarian.Session.open(store);
        shortOne.append(msg("user", "one ask", now)); shortOne.append(msg("assistant", "one answer", now));
        assertEquals("", new Librarian(store, drive, Librarian.person(), shortOne).earlierSummary(new Librarian(store, drive, Librarian.person(), shortOne).historyStart()));
    }

    @Test
    void theNewConversationButtonStandsBesideTheBoxInBothLanguages(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            String en = c.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString()).body();
            String button = "<a class=\"btn quiet\" id=\"chat-new\" href=\"/chat?new=1\" title=\"Start a fresh conversation; this one stays on the list\">New conversation</a>";
            assertTrue(en.contains(button), en);
            assertTrue(en.indexOf(button) > en.indexOf("<form id=\"chat-say\" class=\"composer\"") && en.indexOf(button) < en.indexOf(">Send</button>"), "in the composer's row, between Attach and Send");
            String ja = c.send(HttpRequest.newBuilder(URI.create(base + "/")).header("Accept-Language", "ja").build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(ja.contains("id=\"chat-new\" href=\"/chat?new=1\" title=\"新しい会話を始めます。この会話は一覧に残ります\">新しい会話</a>"), ja);
            // the button opens a fresh conversation, as the link always did
            HttpResponse<String> fresh = c.send(HttpRequest.newBuilder(URI.create(base + "/chat?new=1")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, fresh.statusCode()); assertTrue(fresh.body().contains("<span class=\"title\">A new conversation</span>"), fresh.body());
        } finally { d.stop(); }
    }

    @Test
    void theNoticeForAReportOnTheShelvesEndsBySuggestingAFreshConversation(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        long now = System.currentTimeMillis();
        store.write(new Investigation("I-0001-the-saros", "The saros, in short", Finding.State.accepted, "model:test", Instant.ofEpochMilli(now - 3_600_000).toString(), List.of(), List.of(), "## Answer\n\n223 months.\n"));
        Jobs jobs = new Jobs(store, j -> "");
        PastFieldsTest.done(jobs, "J-0001", "", "What is the saros?", "I-0001-the-saros", now);
        Librarian.Session s = Librarian.Session.open(store);
        s.append(msg("user", "find out what the saros is", now - 4_000_000L)); s.append(msg("assistant", "Research run J-0001 has started.", now - 3_999_000L));
        s.watch("J-0001");
        String notice = s.told("J-0001", RunProgress.of(Jobs.view(jobs.get("J-0001"))));
        System.out.println("[conversations] notice:\n" + notice);
        assertEquals("Research run J-0001 is done after 5 min. Its report is [I-0001-the-saros]. Say \"show it\" to read the answer. " + Librarian.Session.NEXT_CONVERSATION, notice);
        assertEquals("For the next question, a new conversation keeps things short: the New conversation button, or just ask — a day's quiet starts one on its own.", Librarian.Session.NEXT_CONVERSATION);
        // a run that failed suggests nothing: there is no report to read
        ModelsTurnTest.failedRun(store, "J-0309");
        Librarian.Session other = Librarian.Session.open(store);
        other.watch("J-0309");
        assertFalse(other.told("J-0309", RunProgress.of(Jobs.view(jobs.get("J-0309")))).contains("keeps things short"));
        // and on the page: the conversation that filed the run shows the notice when it is next opened
        Librarian.Session again = Librarian.Session.open(store);
        again.append(msg("user", "find out what the saros is", now - 4_000_000L)); again.append(msg("assistant", "Research run J-0001 has started.", now - 3_999_000L));
        again.watch("J-0001");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String page = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + d.port() + "/chat?session=" + again.id)).build(), HttpResponse.BodyHandlers.ofString()).body();
            assertTrue(page.contains(Pages.esc(Librarian.Session.NEXT_CONVERSATION)), page);
        } finally { d.stop(); }
    }
}
