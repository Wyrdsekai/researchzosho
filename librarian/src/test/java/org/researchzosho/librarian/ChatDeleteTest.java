package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A conversation can be deleted (the keeper, 2026-11-11: "we should add a way to delete conversations"), wherever conversations are listed
 * or opened: Delete… on each conversation in the Chat page's list and on the open one, the chat's /delete at the terminal and on the page,
 * and the command line's chat --delete. Its file and every file beside it go, the chat's turn log keeps no words of it, and what it started
 * stays the library's. A reply being written holds the delete; a question that waits in it is released as no answer, as its ten minutes
 * would release it; a run that ends after it is gone ends quietly; nobody deletes another person's conversation.
 */
class ChatDeleteTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String MEANS_EN = "Deleting it removes its messages for good; this cannot be undone. The reports, research runs and translations it started stay in the library, and a file attached in it stays on the shelves.";
    static final String MEANS_JA = "削除すると、この会話のメッセージは完全に消え、元に戻せません。この会話から始めたレポート・調査・翻訳はライブラリに残り、この会話で添付したファイルは書架に残ります。";

    @TempDir Path tmp;
    String realHome;

    @BeforeEach void scratch() throws Exception {
        QuietHome.quiet(tmp.resolve("home"));
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
    }
    @AfterEach void back() { System.setProperty("user.home", realHome); Config.invalidate(); Chrome.clear(); }

    LibraryStore library() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    static ObjectNode msg(String role, String content) { ObjectNode m = M.createObjectNode(); m.put("role", role); m.put("content", content); m.put("at", System.currentTimeMillis()); return m; }

    /** A conversation of the keeper's with one exchange in it, by its id. */
    static Librarian.Session had(LibraryStore store, String id, String words) throws Exception {
        Path dir = Librarian.Session.dir(store);
        Files.createDirectories(dir);
        Librarian.Session s = new Librarian.Session(id, dir.resolve(id + ".jsonl"));
        s.append(msg("user", words)); s.append(msg("assistant", "The shelves hold nothing on it yet."));
        return s;
    }

    /** The files in a conversation folder whose names begin with a conversation's id and a dot. */
    static List<String> filesOf(Path dir, String id) throws Exception {
        try (var l = Files.list(dir)) { return l.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(id + ".")).sorted().toList(); }
    }

    static String location(HttpResponse<String> r) { return r.headers().firstValue("Location").orElse(""); }

    static String noteOf(String location) { Matcher m = Pattern.compile("[?&]note=([^&]*)").matcher(location); return m.find() ? URLDecoder.decode(m.group(1), StandardCharsets.UTF_8) : ""; }

    static HttpResponse<String> get(HttpClient c, String url, String lang) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        if (lang != null) b.header("Accept-Language", lang);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> post(HttpClient c, String url, String form, String lang) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form));
        if (lang != null) b.header("Accept-Language", lang);
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The once-only token of the Delete… form for {@code id} in a page. */
    static String onceFor(String page, String id) {
        Matcher m = Pattern.compile("name=\"session\" value=\"" + Pattern.quote(id) + "\"><input type=\"hidden\" name=\"showing\" value=\"[^\"]*\"><input type=\"hidden\" name=\"once\" value=\"([0-9a-f]+)\"").matcher(page);
        return m.find() ? m.group(1) : "";
    }

    @Test
    void deletingRemovesTheConversationAndEveryFileBesideItAndNothingElse() throws Exception {
        LibraryStore store = library();
        Path dir = Librarian.Session.dir(store);
        Librarian.Session s = had(store, "C-2026-11-08-06-41-28", "What is the saros?");
        Librarian.Session twin = had(store, "C-2026-11-08-06-41-28-1", "Which gears were cut by hand?");   // begun in the same second: its id begins with the other's
        Librarian.Session other = had(store, "C-2026-11-09-10-00-00", "Who built the mechanism?");
        // every file the code keeps beside a conversation
        s.model("homebox");
        s.translateAsk(new TranslateTurn.Ask("this file", "ja", "", "checked", "", "", ""));
        s.imagesFound(List.of(new Images.Found("https://example.org/dial.jpg", "https://example.org/dial", "The dial", "example.org", 640, 480, "", "web", "")));
        s.watch("J-0001");
        s.offerChoice("who:Ada Example", "Which Ada Example is meant?", List.of("the engineer", "none of them"));
        Files.writeString(s.turnFile(), "2147483646\t" + System.currentTimeMillis());   // the mark of a turn whose program is gone
        assertFalse(s.turnRunning(), "a mark whose program is gone is no turn");
        Files.writeString(s.turnFile(), ProcessHandle.current().pid() + "\t" + (System.currentTimeMillis() - 2 * Librarian.Session.TURN_MS));
        assertFalse(s.turnRunning(), "nor is a mark older than an hour");
        twin.watch("J-0002");
        assertEquals(List.of(s.id + ".jsonl", s.id + ".jsonl.images", s.id + ".jsonl.model", s.id + ".jsonl.translate", s.id + ".offers", s.id + ".runs", s.id + ".turn"), filesOf(dir, s.id));
        // the chat's turn log holds the first words of each turn
        new Librarian(store, null, Librarian.person(), s).record("What is the saros?", List.of("library_search"), 0, 1200);
        new Librarian(store, null, Librarian.person(), twin).record("Which gears were cut by hand?", List.of(), 0, 900);
        // what the conversation started is the library's: a report and the record of the run that filed it
        long now = System.currentTimeMillis();
        store.write(new Investigation("I-0001-the-saros", "The saros, in short", Finding.State.accepted, "model:test", Instant.ofEpochMilli(now - 3_600_000).toString(), List.of(), List.of(), "## Answer\n\n223 months.\n"));
        PastFieldsTest.done(new Jobs(store, j -> ""), "J-0001", "", "What is the saros?", "I-0001-the-saros", now);

        Librarian.Session.Deleted d = Librarian.Session.delete(store, s.id, Librarian.person());
        System.out.println("[delete] " + d.said());
        assertEquals(Librarian.Session.Outcome.DELETED, d.outcome());
        assertEquals(s.id, d.id()); assertEquals("What is the saros?", d.title());
        assertEquals("The conversation “What is the saros?” is deleted, and its messages are gone for good. The reports, research runs and translations it started stay in the library, and the files attached in it stay on the shelves.", d.said());
        assertEquals(List.of(), filesOf(dir, s.id), "every file of the conversation is gone");
        assertEquals(List.of(twin.id + ".jsonl", twin.id + ".runs"), filesOf(dir, twin.id), "and its twin's are where they were");
        assertEquals(List.of(other.id, twin.id), Librarian.Session.list(store), "the list no longer shows it");
        assertNull(Librarian.Session.resume(store, s.id));
        assertEquals(2, Librarian.Session.resume(store, twin.id).messages().size());
        assertEquals(2, Librarian.Session.resume(store, other.id).messages().size());
        // the turn log keeps the counts and the time of its turn, and no words
        List<JsonNode> turns = new ArrayList<>(); for (String l : Files.readAllLines(Librarian.turnsFile(store))) turns.add(M.readTree(l));
        JsonNode gone = turns.stream().filter(t -> t.path("session").asText().equals(s.id)).findFirst().orElseThrow();
        assertFalse(gone.has("words"), gone.toString());
        assertEquals("library_search", gone.path("tools").get(0).asText()); assertEquals(1200, gone.path("ms").asInt());
        assertEquals("Which gears were cut by hand?", turns.stream().filter(t -> t.path("session").asText().equals(twin.id)).findFirst().orElseThrow().path("words").asText(), "another conversation's line is untouched");
        // the report and the run's record stay
        assertNotNull(store.investigation("I-0001-the-saros"));
        assertEquals("done", new Jobs(store, j -> "").get("J-0001").path("state").asText());
        // the second time, there is nothing by that id
        Librarian.Session.Deleted again = Librarian.Session.delete(store, s.id, Librarian.person());
        assertEquals(Librarian.Session.Outcome.NOT_FOUND, again.outcome());
        assertEquals("No conversation of yours has the id C-2026-11-08-06-41-28, so nothing was deleted.", again.said());
        // a delete takes the whole id: a start of one, a path, or nothing at all deletes nothing
        for (String id : List.of("C-2026-11-08-06-41", "../chat/" + twin.id, "chat/" + twin.id, twin.id + ".jsonl", "", "   ")) assertEquals(Librarian.Session.Outcome.NOT_FOUND, Librarian.Session.delete(store, id, Librarian.person()).outcome(), id);
        assertEquals(List.of(other.id, twin.id), Librarian.Session.list(store));
    }

    @Test
    void thePageDeletesThroughItsFormAndGoesToTheMostRecentLeftInBothLanguages() throws Exception {
        LibraryStore store = library();
        Librarian.Session oldest = had(store, "C-2026-11-01-09-00-00", "What is the saros?");
        Librarian.Session middle = had(store, "C-2026-11-02-09-00-00", "Which gears were cut by hand?");
        Librarian.Session newest = had(store, "C-2026-11-03-09-00-00", "Who built the mechanism?");
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            String base = "http://127.0.0.1:" + d.port();
            // a file attached in the middle conversation goes onto the shelves with its turn
            HttpResponse<String> att = ChatAttachmentTest.post(c, base + "/chat", "----rzboundary", ChatAttachmentTest.multipart("----rzboundary", middle.id, "What do my notes say?", "gears.txt", "The gears of the mechanism were cut by hand.\n".repeat(4)), null);
            assertEquals(200, att.statusCode(), att.body());
            String shelved = Librarian.Session.resume(store, middle.id).messages().stream().filter(m -> m.has("att")).findFirst().orElseThrow().path("att").path("id").asText();
            assertFalse(shelved.isEmpty());

            // the page: Delete… on each conversation in the list and on the open one, with what deleting means, and no script needed
            String page = get(c, base + "/chat?session=" + middle.id, null).body();
            assertEquals(4, page.split("<details class=\"remove\"><summary class=\"k\">Delete…</summary>", -1).length - 1, "three in the list, one on the open conversation");
            assertTrue(page.contains("<input type=\"hidden\" name=\"op\" value=\"delete\"><input type=\"hidden\" name=\"session\" value=\"" + oldest.id + "\"><input type=\"hidden\" name=\"showing\" value=\"" + middle.id + "\">"), page);
            assertTrue(page.contains("<p class=\"k\">" + MEANS_EN + "</p><button class=\"btn quiet\">Delete this conversation</button></form></details>"), page);
            assertTrue(page.contains("<div class=\"srow\"><a class=\"s on\" href=\"/chat?session=" + middle.id + "\">Which gears were cut by hand?"), "the open one is marked in the list as before: " + page);
            String ja = get(c, base + "/chat?session=" + middle.id, "ja").body();
            assertTrue(ja.contains("<summary class=\"k\">削除…</summary>") && ja.contains("<p class=\"k\">" + MEANS_JA + "</p><button class=\"btn quiet\">この会話を削除</button>"), "in Japanese: " + ja);

            // the open conversation deleted: the page goes to the most recent one left, and says what happened under its latest turn
            String once = onceFor(page, middle.id);
            assertFalse(once.isEmpty());
            HttpResponse<String> r = post(c, base + "/chat", "op=delete&session=" + middle.id + "&showing=" + middle.id + "&once=" + once, null);
            assertEquals(303, r.statusCode(), r.body());
            assertTrue(location(r).startsWith("/chat?note="), "no conversation named: the most recent left opens: " + location(r));
            assertEquals("The conversation “Which gears were cut by hand?” is deleted, and its messages are gone for good. The reports, research runs and translations it started stay in the library, and the files attached in it stay on the shelves.", noteOf(location(r)));
            assertFalse(Files.exists(middle.file));
            String after = get(c, base + location(r), null).body();
            assertTrue(after.contains("<span class=\"title\">Who built the mechanism?</span>"), "the most recent left is open: " + after);
            assertTrue(after.contains("<p class=\"k note\">The conversation “Which gears were cut by hand?” is deleted"), after);
            assertFalse(after.contains("href=\"/chat?session=" + middle.id + "\""), "the list no longer shows it");
            try (var raw = Files.walk(store.rawDir())) { assertTrue(raw.anyMatch(p -> p.getFileName().toString().equals(shelved)), "the file attached in it is on the shelves still: " + shelved); }
            // the same form sent again (a second click, the back button) goes where the first went, and deletes nothing more
            HttpResponse<String> twice = post(c, base + "/chat", "op=delete&session=" + middle.id + "&showing=" + middle.id + "&once=" + once, null);
            assertEquals(location(r), location(twice));

            // one deleted from the list while another is open: the page stays on the open one; in Japanese, the sentence is Japanese
            HttpResponse<String> fromList = post(c, base + "/chat", "op=delete&session=" + oldest.id + "&showing=" + newest.id, "ja");
            assertTrue(location(fromList).startsWith("/chat?session=" + newest.id + "&note="), location(fromList));
            assertEquals("会話「What is the saros?」を削除しました。メッセージは完全に消えました。この会話から始めたレポート・調査・翻訳はライブラリに残り、この会話で添付したファイルは書架に残ります。", noteOf(location(fromList)));
            assertEquals(List.of(newest.id), Librarian.Session.list(store));

            // a conversation that is not there any more (another tab deleted it): a plain sentence, the page where it was
            HttpResponse<String> stale = post(c, base + "/chat", "op=delete&session=" + oldest.id + "&showing=" + newest.id, null);
            assertEquals(303, stale.statusCode());
            assertEquals("No conversation of yours has the id " + oldest.id + ", so nothing was deleted.", noteOf(location(stale)));
            assertTrue(get(c, base + location(stale), null).body().contains("<p class=\"k note\">No conversation of yours has the id " + oldest.id + ", so nothing was deleted.</p>"));

            // the last one deleted: a new conversation opens, with nothing to delete on it
            HttpResponse<String> last = post(c, base + "/chat", "op=delete&session=" + newest.id + "&showing=" + newest.id, null);
            String empty = get(c, base + location(last), null).body();
            assertTrue(empty.contains("<span class=\"title\">A new conversation</span>") && empty.contains("No conversations yet."), empty);
            assertFalse(empty.contains("<details class=\"remove\">"), "a new conversation is on no list: nothing to delete");
            assertTrue(Librarian.Session.list(store).isEmpty());
        } finally { d.stop(); }
    }

    @Test
    void nobodyDeletesAnotherPersonsConversation() throws Exception {
        LibraryStore store = library();
        Librarian.Session mine = had(store, "C-2026-11-05-08-00-00", "Where does Ada Example teach now?");
        Patrons.set(store, "did:key:reader", "a cousin", Patrons.Level.read);
        Patrons.set(store, "did:key:other", "a neighbour", Patrons.Level.read);
        Patrons.Patron cousin = new Patrons.Patron("did:key:reader", "a cousin", "web"), neighbour = new Patrons.Patron("did:key:other", "a neighbour", "web");
        Librarian.Session theirs = Librarian.Session.latest(store, cousin);
        theirs.append(msg("user", "Where was Tom Hale of Leeds buried?"));
        String theirsRel = Librarian.Session.dir(store).relativize(theirs.file).toString().replace(".jsonl", "");

        // a reader cannot reach the keeper's, by the id, by a path, or through the page's form
        assertEquals(Librarian.Session.Outcome.NOT_FOUND, Librarian.Session.delete(store, mine.id, cousin).outcome());
        String to = Pages.chatDelete(store, cousin, Map.of("op", "delete", "session", mine.id, "showing", theirs.id));
        assertEquals("No conversation of yours has the id " + mine.id + ", so nothing was deleted.", noteOf(to));
        assertTrue(Files.exists(mine.file));
        // one reader cannot reach another's
        assertEquals(Librarian.Session.Outcome.NOT_FOUND, Librarian.Session.delete(store, theirs.id, neighbour).outcome());
        // the keeper's list holds the keeper's conversations only, and this change gives the keeper no more: not by the id, not by the path under readers/
        assertEquals(Librarian.Session.Outcome.NOT_FOUND, Librarian.Session.delete(store, theirs.id, Librarian.person()).outcome());
        assertEquals(Librarian.Session.Outcome.NOT_FOUND, Librarian.Session.delete(store, theirsRel, Librarian.person()).outcome(), theirsRel);
        assertEquals(Librarian.Session.Outcome.NOT_FOUND, Librarian.Session.delete(store, theirs.id, Patrons.Patron.PERSON).outcome());
        assertTrue(Files.exists(theirs.file));
        // a browser that has not signed in, when the sign-in is on, has no conversations and deletes none
        WebAccess.OVERRIDE = Boolean.TRUE;
        try { assertEquals(Librarian.Session.Outcome.NOT_FOUND, Librarian.Session.delete(store, mine.id, Patrons.Patron.WEB).outcome()); }
        finally { WebAccess.OVERRIDE = null; }
        assertTrue(Files.exists(mine.file));
        // each deletes their own
        assertEquals(Librarian.Session.Outcome.DELETED, Librarian.Session.delete(store, theirs.id, cousin).outcome());
        assertFalse(Files.exists(theirs.file));
        assertEquals(Librarian.Session.Outcome.DELETED, Librarian.Session.delete(store, mine.id, Patrons.Patron.PERSON).outcome());
        assertFalse(Files.exists(mine.file));
    }

    @Test
    void aQuestionThatWaitsIsReleasedAsNoAnswerAsItsTenMinutesWouldReleaseIt() throws Exception {
        LibraryStore store = library();
        // a field's question: no answer is ordinary research, the run starts as it was filed
        Librarian lib = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + GenealogyOfferTest.FAMILY + "\"}"), LibrarianChatTest.say("I have filed it.")), new ArrayList<>()), Librarian.person(), Librarian.Session.open(store));
        lib.say("Find out who my great-grandfather's parents were");
        ObjectNode filed = GenealogyOfferTest.filed(store);
        String job = filed.path("job_id").asText();
        assertEquals(Jobs.OFFERED, filed.path("state").asText());
        assertTrue(lib.waitingForAnswer());
        Librarian.Session.Deleted d = Librarian.Session.delete(store, lib.session().id, Librarian.person());
        System.out.println("[delete] " + d.said());
        assertEquals(Librarian.Session.Outcome.DELETED, d.outcome());
        assertEquals(List.of(job), d.started()); assertEquals(List.of(), d.notStarted());
        ObjectNode after = new Jobs(store, j -> "").get(job);
        assertEquals("queued", after.path("state").asText(), after.toString());
        assertFalse(after.path("args").has("field"), "no answer is ordinary research");
        assertEquals("none", after.path("answered").asText(), "written as no answer, as the end of the wait writes it (a no would say no)");
        assertTrue(d.said().endsWith(" Research run " + job + " was waiting for your answer there; it starts now as ordinary research, as when no answer comes."), d.said());

        // a question with named choices: no answer is no choice, the run is not started
        Librarian choose = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"Which spectrometer should the lab buy?\", \"choices\": [\"Raman\", \"FTIR\"], \"choice_question\": \"Which kind is the question about?\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>()), Librarian.person(), Librarian.Session.open(store));
        choose.say("Find out which spectrometer the lab should buy");
        String chosen = GenealogyOfferTest.filed(store).path("job_id").asText();
        assertEquals(Jobs.OFFERED, new Jobs(store, j -> "").get(chosen).path("state").asText());
        Librarian.Session.Deleted c = Librarian.Session.delete(store, choose.session().id, Librarian.person());
        assertEquals(List.of(), c.started()); assertEquals(List.of(chosen), c.notStarted());
        ObjectNode stopped = new Jobs(store, j -> "").get(chosen);
        assertEquals("stopped", stopped.path("state").asText(), stopped.toString());
        assertTrue(stopped.path("result").asText().contains("no choice"), stopped.toString());
        assertTrue(c.said().endsWith(" Research run " + chosen + " was waiting for your answer there; it is not started, as when no answer comes."), c.said());

        // in Japanese
        Chrome.here("ja");
        assertTrue(c.said().endsWith("調査 " + chosen + " はその会話であなたの返事を待っていました。返事がなかったときと同じく、始めません。"), c.said());
    }

    @Test
    void aReplyBeingWrittenHoldsTheDeleteUntilItHasCome() throws Exception {
        LibraryStore store = library();
        CountDownLatch inTurn = new CountDownLatch(1), letGo = new CountDownLatch(1);
        Researcher.Drive slow = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
                inTurn.countDown();
                try { letGo.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                ObjectNode m = M.createObjectNode(); m.put("role", "assistant"); m.put("content", "The saros is 223 synodic months."); return m;
            }
            @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
            @Override public int contextWindow() { return 16384; }
        };
        Librarian.Session s = Librarian.Session.open(store);
        Librarian lib = new Librarian(store, slow, Librarian.person(), s);
        String[] reply = {null};
        Thread turn = new Thread(() -> { try { reply[0] = lib.say("What is the saros?"); } catch (Exception e) { reply[0] = "failed: " + e; } });
        turn.start();
        assertTrue(inTurn.await(30, TimeUnit.SECONDS), "the turn reached the model");
        assertTrue(s.turnRunning());
        Librarian.Session.Deleted held = Librarian.Session.delete(store, s.id, Librarian.person());
        assertEquals(Librarian.Session.Outcome.TURN_RUNNING, held.outcome());
        assertEquals("A reply is still being written in the conversation “What is the saros?”, so it was not deleted. Delete it once the reply has come.", held.said());
        String to = Pages.chatDelete(store, Patrons.Patron.PERSON, Map.of("op", "delete", "session", s.id, "showing", s.id));
        assertTrue(to.startsWith("/chat?session=" + s.id + "&note="), "the page stays on it, and says why: " + to);
        assertTrue(Files.exists(s.file));
        letGo.countDown();
        turn.join(30_000);
        assertEquals("The saros is 223 synodic months.", reply[0]);
        assertFalse(Files.exists(s.turnFile()), "the mark goes with the turn");
        assertFalse(s.turnRunning());
        assertEquals(Librarian.Session.Outcome.DELETED, Librarian.Session.delete(store, s.id, Librarian.person()).outcome());
        assertTrue(filesOf(Librarian.Session.dir(store), s.id).isEmpty());
    }

    @Test
    void aRunThatEndsAfterItsConversationIsGoneEndsQuietly() throws Exception {
        LibraryStore store = library();
        Librarian.Session s = had(store, "C-2026-11-10-09-00-00", "find out what the saros is");
        s.watch("J-0001");   // the run it started, still going
        Librarian.Session other = had(store, "C-2026-11-10-10-00-00", "Who built the mechanism?");
        assertEquals(Librarian.Session.Outcome.DELETED, Librarian.Session.delete(store, s.id, Librarian.person()).outcome());
        // the run ends after: its record and its report are the library's; its notice has no conversation to go to
        long now = System.currentTimeMillis();
        Jobs jobs = new Jobs(store, j -> "");
        store.write(new Investigation("I-0001-the-saros", "The saros, in short", Finding.State.accepted, "model:test", Instant.ofEpochMilli(now - 3_600_000).toString(), List.of(), List.of(), "## Answer\n\n223 months.\n"));
        PastFieldsTest.done(jobs, "J-0001", "", "What is the saros?", "I-0001-the-saros", now);
        // whoever still held the conversation (the terminal, a tab): no notice is due, telling it fails nothing, and nothing comes back under its id
        assertTrue(s.gone());
        assertTrue(s.watched().isEmpty());
        String notice = s.told("J-0001", RunProgress.of(Jobs.view(jobs.get("J-0001"))));
        assertTrue(notice.startsWith("Research run J-0001 is done"), notice);
        s.append(msg("user", "show it")); s.watch("J-0002"); s.offer("J-0003", "genealogy", "Who were his parents?"); s.answer("J-0003", "genealogy", "yes", "Who were his parents?"); s.model("homebox");
        assertTrue(filesOf(Librarian.Session.dir(store), s.id).isEmpty(), "nothing written back: " + filesOf(Librarian.Session.dir(store), s.id));
        assertEquals(List.of(other.id), Librarian.Session.list(store));
        assertEquals("No research run is being followed in this conversation.", ChatCommands.runs(store, Patrons.Patron.PERSON, s));
        // the pages: the deleted id opens the most recent left, with no notice in it; the strip of runs answers
        String page = Pages.chat(store, Patrons.Patron.PERSON, Map.of("session", s.id));
        assertTrue(page.contains("<span class=\"title\">Who built the mechanism?</span>") && !page.contains("Research run J-0001 is done"), page);
        assertTrue(Pages.chatRuns(store, new LibraryProtocol(store), Patrons.Patron.PERSON, s.id).startsWith("<div class=\"striphead\""));
        assertTrue(Librarian.Session.resume(store, other.id).messages().stream().noneMatch(m -> m.path("content").asText().contains("J-0001")), "the notice went into no other conversation");
        assertEquals("done", jobs.get("J-0001").path("state").asText());
        assertNotNull(store.investigation("I-0001-the-saros"));
    }

    @Test
    void theChatsDeleteSaysWhatGoesFirstAndGoesOnInAFreshOne() throws Exception {
        LibraryStore store = library();
        Librarian.Session[] current = {had(store, "C-2026-11-06-07-00-00", "hello")};
        Librarian.Session earlier = had(store, "C-2026-11-05-07-00-00", "an earlier question");
        ChatCommands.Front front = ChatCommandsTest.terminal(store, current);
        Patrons.Patron me = Patrons.Patron.PERSON;
        String here = current[0].id;
        // shown first, deleted with --yes
        String shown = ChatCommands.reply(store, me, "terminal", "/delete", false, front);
        assertEquals("Delete the conversation “hello” (" + here + ")? " + MEANS_EN + "\nTo go ahead: /delete " + here + " --yes", shown);
        assertTrue(Files.exists(current[0].file));
        String gone = ChatCommands.reply(store, me, "terminal", "/delete " + here + " --yes", false, front);
        assertTrue(gone.startsWith("The conversation “hello” is deleted, and its messages are gone for good.") && gone.endsWith("\nA fresh conversation, " + current[0].id + "."), gone);
        assertNotEquals(here, current[0].id, "the front is in a fresh conversation");
        assertEquals(List.of(earlier.id), Librarian.Session.list(store));
        // a fresh one has nothing to delete; an id that is none of this person's deletes nothing; a unique start of an id names it, as /resume takes it
        assertEquals("This conversation has nothing in it yet, so there is nothing to delete.", ChatCommands.reply(store, me, "terminal", "/delete", false, front));
        assertEquals("No conversation of yours has the id nope, so nothing was deleted. /sessions lists them.", ChatCommands.reply(store, me, "terminal", "/delete nope --yes", false, front));
        String fresh = current[0].id;
        String other = ChatCommands.reply(store, me, "terminal", "/delete C-2026-11-05 --yes", false, front);
        assertTrue(other.startsWith("The conversation “an earlier question” is deleted"), other);
        assertEquals(fresh, current[0].id, "another conversation deleted: the front stays where it is");
        assertTrue(Librarian.Session.list(store).isEmpty());
        assertTrue(ChatCommands.reply(store, me, "terminal", "/help", false, front).contains("/delete, to delete this conversation, or /delete and an id for another of yours"));
        // in Japanese
        Librarian.Session ja = had(store, "C-2026-11-07-07-00-00", "こんにちは");
        current[0] = ja;
        Chrome.here("ja");
        assertEquals("会話「こんにちは」（" + ja.id + "）を削除しますか？ " + MEANS_JA + "\n実行するには：/delete " + ja.id + " --yes", ChatCommands.reply(store, me, "terminal", "/delete", false, front));
    }

    @Test
    void thePagesTypedDeleteGoesToAFreshConversationThatHoldsTheExchange() throws Exception {
        LibraryStore store = library();
        Librarian.Session s = Librarian.Session.open(store, Patrons.Patron.PERSON);
        s = new Librarian.Session("C-2026-11-06-08-00-00", s.file.resolveSibling("C-2026-11-06-08-00-00.jsonl"));   // begun earlier than this second, as a conversation one deletes was
        Pages.chatReplied(s, "hello", "Hello.");
        Librarian.Session shown = Pages.chatPost(null, store, Patrons.Patron.PERSON, Map.of("session", s.id, "say", "/delete"));
        assertEquals(s.id, shown.id);
        assertTrue(CareerInterviewTest.lastReply(store, s.id).startsWith("Delete the conversation “hello” (" + s.id + ")?"), CareerInterviewTest.lastReply(store, s.id));
        Librarian.Session next = Pages.chatPost(null, store, Patrons.Patron.PERSON, Map.of("session", s.id, "say", "/delete " + s.id + " --yes"));
        assertNotEquals(s.id, next.id, "the page is sent to the fresh one");
        assertFalse(Files.exists(s.file));
        String said = CareerInterviewTest.lastReply(store, next.id);
        assertTrue(said.startsWith("The conversation “hello” is deleted") && said.endsWith("A fresh conversation, " + next.id + "."), said);
        assertEquals(List.of(next.id), Librarian.Session.list(store));
    }

    @Test
    void theCommandLineSaysWhatGoesFirstAndDeletesWithYes() throws Exception {
        LibraryStore store = library();
        Librarian.Session s = had(store, "C-2026-11-04-07-00-00", "What is the saros?");
        String[] said = cli(store, "--delete", s.id);
        assertEquals("0", said[0]);
        assertEquals("Delete the conversation “What is the saros?” (" + s.id + ")? " + MEANS_EN + "\nTo go ahead: researchzosho chat --delete " + s.id + " --yes", said[1].strip());
        assertTrue(Files.exists(s.file));
        said = cli(store, "--delete", "C-2026-11-04", "--yes");   // a unique start of the id, as --resume takes it
        assertEquals("0", said[0], said[2]);
        assertTrue(said[1].startsWith("The conversation “What is the saros?” is deleted"), said[1]);
        assertFalse(Files.exists(s.file));
        said = cli(store, "--delete", s.id, "--yes");
        assertEquals("2", said[0]);
        assertEquals("No conversation of yours has the id " + s.id + ", so nothing was deleted. researchzosho chat --sessions lists them.", said[2].strip());
        said = cli(store, "--delete");
        assertEquals("2", said[0]);
        assertEquals("Say which conversation: researchzosho chat --delete and its id. researchzosho chat --sessions lists them.", said[2].strip());
    }

    /** {@code researchzosho chat …} at the command line: the exit code, what it printed, what it printed as an error. */
    static String[] cli(LibraryStore store, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("researchzosho", "chat")); argv.addAll(List.of(args));
        PrintStream out0 = System.out, err0 = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8)); System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        int rc;
        try { rc = LibrarianCli.chat(store, argv.toArray(new String[0]), "http://127.0.0.1:1", "none"); }
        finally { System.setOut(out0); System.setErr(err0); }
        return new String[]{String.valueOf(rc), out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8)};
    }
}
