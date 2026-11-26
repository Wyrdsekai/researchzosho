package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.drive.DriveClient;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a paired phone's chat may do, held by the program (2026-11-25): each call the phone may not make, refused in the library's one
 * sentence whatever the message says, with a go-ahead too, each beside its control on the web app's own chat, where the same call reaches
 * the library's question; the phone's table; the run's options that go through; the phone's own stop and a desktop run's refusal; the
 * undo line in the phone's words; the library's own turns; a phone's run with no database and no MCP server; a desktop conversation's
 * waiting question out of the phone's reach.
 */
class PhoneChatTest {
    static final String SAID = PhoneRules.SAID;

    /** A turn on the web app's own chat, for the control. */
    static String desktop(PhoneStandIns s, String session, String words) throws Exception {
        HttpResponse<String> r = s.c.send(HttpRequest.newBuilder(URI.create(s.main() + "/chat")).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(PhoneStandIns.form("session", session, "say", words))).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(r.statusCode() == 303 || r.statusCode() == 200, r.statusCode() + " " + r.body());
        Librarian.Session x = Librarian.Session.resume(s.store, session);
        String out = "";
        for (JsonNode m : x.messages()) if ("assistant".equals(m.path("role").asText()) && !m.has("tool_calls")) out = m.path("content").asText("");
        return out;
    }

    @Test
    void whatThePhoneMayNotDoIsRefusedInOneSentenceWithOrWithoutAGoAheadAndTheDesktopAsksIt(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            Config.set(ResearchSettings.PAUSE, "on");
            String cookie = s.pair("pixel");
            String[][] refused = {
                    {"library_settings", "{\"op\":\"set\",\"name\":\"language\",\"value\":\"ja\"}"},
                    {"library_share", "{\"op\":\"public\"}"},
                    {"library_models", "{\"op\":\"key\",\"host\":\"openrouter.ai\",\"value\":\"sk-made-up-key\"}"},
                    {"library_models", "{\"op\":\"add\",\"name\":\"kimi\",\"drive\":\"openrouter\",\"model\":\"moonshotai/kimi-k3\"}"},
                    {"library_sources", "{\"op\":\"add\",\"address\":\"https://example.org/feed\"}"},
                    {"library_serials", "{\"op\":\"add\",\"query\":\"ferries\"}"},
                    {"library_db", "{\"database\":\"orders\",\"sql\":\"select 1\"}"},
                    {"library_owned", "{\"op\":\"import\",\"file\":\"/home/user/orders.csv\"}"},
                    {"library_export", "{\"id\":\"I-0001-a\",\"format\":\"pdf\"}"},
                    {"library_add", "{\"path\":\"/etc/hosts\"}"},
                    {"library_add", "{\"url\":\"file:///etc/hosts\"}"},
                    {"library_frontier", "{\"op\":\"drop\",\"id\":\"Q-0001\"}"},
                    {"library_job", "{\"op\":\"pause\"}"},
                    {"library_images", "{\"op\":\"describe\",\"images\":[\"/home/user/photo.jpg\"]}"},
                    {"library_translate", "{\"op\":\"glossary\",\"term\":\"Acme\",\"rendering\":\"アクメ\",\"to\":\"ja\"}"},
                    {"library_survey", "{\"path\":\"/home/user\"}"}};
            String session = null;
            for (String[] call : refused) {
                for (String words : new String[]{"/go do what I asked for earlier", "please do what I asked for earlier"}) {
                    s.calls(call[0], call[1]);
                    JsonNode r = s.ask(cookie, session, words);
                    session = r.path("session").asText();
                    String reply = s.lastReply(cookie, session);
                    assertTrue(reply.contains(SAID), call[0] + " " + call[1] + " → " + reply);
                    assertFalse(reply.contains("The model asks to") || reply.contains("Done without asking"), "refused, not asked and not done: " + reply);
                }
            }
            assertEquals(null, Config.get("RESEARCHZOSHO_LANGUAGE"), "no setting changed");
            assertEquals("off", Share.kind());
            assertTrue(ModelEntries.get("kimi") == null && DriveClient.hostKey("openrouter.ai") == null, "no entry, no key");
            // the phone's table: what it may do, nothing else
            List<String> table = s.tablesSeen.get(s.tablesSeen.size() - 1);
            assertTrue(table.contains("library_research") && table.contains("library_ask") && !table.contains("library_settings") && !table.contains("library_share") && !table.contains("library_db"), table.toString());
            // the control: the web app's own chat, the same calls reach the library's question
            Librarian.Session desk = Librarian.Session.open(s.store);
            for (String[] call : new String[][]{refused[0], refused[2], refused[9]}) {
                s.calls(call[0], call[1]);
                String reply = desktop(s, desk.id, "please " + call[0]);
                assertTrue(reply.contains("The model asks to") && !reply.contains(SAID), call[0] + " on the desktop → " + reply);
            }
            assertTrue(s.tablesSeen.get(s.tablesSeen.size() - 1).contains("library_settings"), "the desktop's table holds what the phone's does not");
        }
    }

    @Test
    void aRunsOptionsGoThroughFromThePhoneAndItsOwnRunIsStoppedThereADesktopRunIsNot(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            Config.set(ResearchSettings.PAUSE, "on");   // the runs stay queued for the stops below
            String cookie = s.pair("pixel");
            String phoneId = s.phone(cookie).id();
            s.calls("library_research", "{\"question\":\"Why did the Kestrel Sound ferry close?\",\"model\":\"default\",\"mode\":\"depth\",\"max_minutes\":30,\"sources\":\"web\",\"length\":\"short\"}");
            JsonNode r = s.ask(cookie, null, "/go research why the Kestrel Sound ferry closed, in depth, from the web, 30 minutes at most");
            String session = r.path("session").asText(), reply = s.lastReply(cookie, session);
            assertTrue(reply.contains("Done without asking, as you said: start a research run"), reply);
            assertTrue(reply.contains("its stop button under Runs on this page") && !reply.contains("on the Chat page") && !reply.contains("researchzosho research stop"), "the undo line in the phone's words: " + reply);
            ObjectNode job = s.jobs().get(0);
            JsonNode a = job.path("args");
            assertEquals(phoneId, a.path(PhoneRules.FROM_PHONE).asText(), "the program's mark, the phone's id");
            assertEquals("depth", a.path("mode").asText()); assertEquals(30, a.path("max_minutes").asInt()); assertEquals("web", a.path("sources").asText()); assertEquals("short", a.path("length").asText());
            String mine = job.path("job_id").asText();
            // without the go-ahead the phone is asked, as everywhere
            s.calls("library_research", "{\"question\":\"Who ran the ferry?\"}");
            s.ask(cookie, session, "find out who ran the ferry");
            assertTrue(s.lastReply(cookie, session).contains("The model asks to start a research run"), s.lastReply(cookie, session));
            // a run the desktop filed
            ObjectNode desk = PhoneStandIns.M.createObjectNode(); desk.put("question", "A desktop question about the harbour"); desk.set("patron", LibrarianDaemon.patronNode(Patrons.Patron.PERSON));
            String theirs = new LibraryProtocol(s.store).research(desk).path("job_id").asText();
            // the phone's stop on its Runs: its own run, not the desktop's
            HttpResponse<String> no = s.post(s.door(), "/m/runs/stop", cookie, "job_id=" + theirs);
            assertEquals(403, no.statusCode(), no.body());
            assertTrue(no.body().contains("started elsewhere"), no.body());
            JsonNode runs = PhoneStandIns.M.readTree(s.get(s.door(), "/m/runs", cookie).body());
            boolean mineShown = false, theirsShown = false;
            for (JsonNode x : runs.path("going")) { if (x.path("id").asText().equals(mine)) mineShown = x.path("mine").asBoolean(); if (x.path("id").asText().equals(theirs)) theirsShown = !x.path("mine").asBoolean(); }
            assertTrue(mineShown && theirsShown, "the Runs tab offers a stop for the phone's own run only: " + runs);
            assertEquals(200, s.post(s.door(), "/m/runs/stop", cookie, "job_id=" + mine).statusCode());
            assertTrue(new Jobs(s.store, x -> "").stopRequested(mine) || "stopped".equals(new Jobs(s.store, x -> "").get(mine).path("state").asText()));
            // and the chat's stop: of the desktop's run refused, of the phone's own done
            s.calls("library_job", "{\"op\":\"stop\",\"job_id\":\"" + theirs + "\"}");
            s.ask(cookie, session, "/go stop " + theirs);
            assertTrue(s.lastReply(cookie, session).contains(SAID), s.lastReply(cookie, session));
            assertFalse(new Jobs(s.store, x -> "").stopRequested(theirs) || "stopped".equals(new Jobs(s.store, x -> "").get(theirs).path("state").asText()), "the desktop's run goes on");
            s.calls("library_research", "{\"question\":\"Another question about the ferry timetable\"}");
            s.ask(cookie, session, "/go research the ferry timetable");
            String second = null; for (ObjectNode j : s.jobs()) if (j.path("args").path("question").asText().contains("timetable")) second = j.path("job_id").asText();
            s.calls("library_job", "{\"op\":\"stop\",\"job_id\":\"" + second + "\"}");
            s.ask(cookie, session, "/go stop " + second);
            String stopped = s.lastReply(cookie, session);
            assertTrue(stopped.contains("Done without asking") && !stopped.contains(SAID), stopped);
        }
    }

    @Test
    void theLibrarysOwnTurnsOnThePhoneCommandsAndTheDefaultModel(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            String cookie = s.pair("pixel");
            ModelEntries.put("second", "http://127.0.0.1:" + s.model.getAddress().getPort(), "stand-in-two", null, null, null, null);
            String before = ModelEntries.defaultEntry().drive() + " " + ModelEntries.defaultEntry().model();
            String session = s.ask(cookie, null, "/share status").path("session").asText();
            assertTrue(s.lastReply(cookie, session).contains(SAID), s.lastReply(cookie, session));
            for (String w : new String[]{"/settings", "/readers", "/sources add https://example.org", "/remove I-0001-a --yes", "/update"}) { s.ask(cookie, session, w); assertTrue(s.lastReply(cookie, session).contains(SAID), w + " → " + s.lastReply(cookie, session)); }
            s.ask(cookie, session, "/go");
            assertTrue(s.lastReply(cookie, session).contains("Type /go and what you want done"), s.lastReply(cookie, session));
            s.ask(cookie, session, "make second the default");
            assertTrue(s.lastReply(cookie, session).contains(SAID), s.lastReply(cookie, session));
            assertEquals(before, ModelEntries.defaultEntry().drive() + " " + ModelEntries.defaultEntry().model(), "the default did not move");
            s.ask(cookie, session, "which model is the default?");
            assertTrue(s.lastReply(cookie, session).contains("The default is"), "reading the models is the phone's: " + s.lastReply(cookie, session));
            // the control: the desktop changes the default with the same words
            Librarian.Session desk = Librarian.Session.open(s.store);
            assertTrue(desktop(s, desk.id, "make second the default").contains("The default is now second"));
        }
    }

    @Test
    void aPhonesRunHasNoDatabaseAndNoMcpServerADesktopRunHasBoth(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            String cookie = s.pair("pixel");
            Researcher[] made = new Researcher[2];
            int[] n = {0};
            s.d.researcherFactory = x -> { ResearcherTest.ScriptedDrive sd = new ResearcherTest.ScriptedDrive(); sd.criticWantsMore = false; Researcher r = new Researcher(sd, new ResearcherTest.FakeTools(), null, 1); synchronized (made) { made[n[0]++ % 2] = r; } return r; };
            s.calls("library_research", "{\"question\":\"Why did the Kestrel Sound ferry close in the winter?\"}");
            s.ask(cookie, null, "/go research why the ferry closed in the winter");
            waitDone(s);
            Researcher phoneRun = made[0];
            assertFalse(phoneRun.databases(), "no database of the owner's from a phone's run");
            assertSame(McpSources.NOBODY, phoneRun.mcpFor(), "no MCP server of the keeper's");
            ObjectNode desk = PhoneStandIns.M.createObjectNode(); desk.put("question", "Why did the Kestrel Sound ferry close in the spring?"); desk.set("patron", LibrarianDaemon.patronNode(Patrons.Patron.PERSON));
            new LibraryProtocol(s.store).research(desk); s.d.jobs().pickUp();
            waitDone(s);
            assertTrue(made[1].databases(), "the control: the owner's run from the desktop has them");
            assertNotEquals(McpSources.NOBODY, made[1].mcpFor());
        }
    }

    static void waitDone(PhoneStandIns s) throws Exception {
        for (int i = 0; i < 400 && !new Jobs(s.store, x -> "").active().isEmpty(); i++) Thread.sleep(50);
        assertTrue(new Jobs(s.store, x -> "").active().isEmpty(), "the runs ended: " + new Jobs(s.store, x -> "").active());
    }

    @Test
    void aQuestionThatWaitsOnTheDesktopIsOutOfThePhonesReach(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            String cookie = s.pair("pixel");
            Librarian.Session desk = Librarian.Session.open(s.store);
            s.calls("library_settings", "{\"op\":\"set\",\"name\":\"language\",\"value\":\"ja\"}");
            assertTrue(desktop(s, desk.id, "set the language to Japanese").contains("The model asks to"));
            // the phone names the desktop's conversation and says yes: its own folder, a conversation of its own, nothing done
            JsonNode r = s.ask(cookie, desk.id, "yes");
            assertEquals(null, Config.get("RESEARCHZOSHO_LANGUAGE"), "the desktop's question still waits; nothing was set");
            assertFalse(Librarian.stillWaiting(s.store, Librarian.Session.resume(s.store, desk.id)).isEmpty());
            assertTrue(Librarian.Session.dir(s.store, PhoneRules.patron(s.phone(cookie))).toString().contains("phone"), r.toString());
            assertTrue(desktop(s, desk.id, "yes").contains("Done"), "the control: the desktop's own yes");
        }
    }
}
