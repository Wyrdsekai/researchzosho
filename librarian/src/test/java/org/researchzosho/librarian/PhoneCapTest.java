package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.Stopping;
import org.researchzosho.drive.DriveClient;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A spending cap per paired phone (the owner's choice, 2026-11-25): ten dollars a month unless set, set and shown by {@code phone cap}, 0 for
 * no paid model, none or -1 for no limit; what counts is every run and chat turn of that phone this month; checked when a run is filed,
 * when it starts, and while it goes (a run's cost is counted on every thread as it goes); a model of the person's own is never counted nor
 * refused; a paid model without prices is refused (nothing to count it with) unless the phone has no limit; two phones count apart.
 */
class PhoneCapTest {

    /** A paid model on the stand-in: the library keeps a key for its host, and it has prices. */
    static ModelEntries.Entry paid(PhoneStandIns s, String name, String prices) throws Exception {
        Config.set(DriveClient.hostKeySetting("127.0.0.1"), "made-up-test-key");
        return ModelEntries.put(name, "http://127.0.0.1:" + s.model.getAddress().getPort(), "stand-in", prices, null, null, null);
    }

    /** Spending on the ledger for a phone this month. */
    static void spent(LibraryStore store, String phoneId, ModelEntries.Entry e, double dollars) throws Exception {
        Spend.PHONE.set(phoneId);
        try { Spend.record(store, "J-9999", "", e, new long[]{1000, 0, 100}, dollars, 1, "filed", "research"); } finally { Spend.PHONE.remove(); }
    }

    static String cli(LibraryStore store, String... args) throws Exception { return PhoneCli.run(store, List.of(args), new PrintStream(new ByteArrayOutputStream())).text(); }

    @Test
    void tenDollarsUnlessSetAndPhoneCapSetsItShownInTheListAndOnThePhone(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            String cookie = s.pair("pixel");
            assertEquals(10.0, s.phone(cookie).cap());
            assertTrue(cli(s.store, "list").contains("cap $10.00 a month"), cli(s.store, "list"));
            assertTrue(cli(s.store, "cap", "pixel", "2.50").contains("now $2.50 a month"));
            assertEquals(2.5, s.phone(cookie).cap());
            String state = s.get(s.door(), "/m/state", cookie).body();
            assertTrue(state.contains("This month: $0.00 spent on paid models; its cap is $2.50 a month."), state);
            assertTrue(cli(s.store, "cap", "pixel", "none").contains("no limit"));
            assertEquals(Phones.NO_LIMIT, s.phone(cookie).cap());
            assertTrue(cli(s.store, "list").contains("cap no limit"), "no limit is said plainly: " + cli(s.store, "list"));
            assertTrue(s.get(s.door(), "/m/state", cookie).body().contains("no limit."));
            cli(s.store, "cap", "pixel", "-1");
            assertEquals(Phones.NO_LIMIT, s.phone(cookie).cap(), "-1 is no limit too");
            assertTrue(cli(s.store, "cap", "pixel", "0").contains("no paid model"));
            assertTrue(cli(s.store, "cap", "nobody", "5").contains("No phone is called nobody"));
            assertTrue(cli(s.store, "cap", "pixel", "lots").contains("A cap is dollars a month"));
            // phone pair --cap: the new phone's cap
            Config.set(PhoneDoor.KIND, "tailnet"); Config.set(PhoneDoor.ADDRESS, "https://homebox.tail1234.ts.net:8443");
            String paired = cli(s.store, "pair", "tablet", "--cap", "none", "--no-wait");
            assertTrue(paired.contains("no limit") && paired.contains("https://homebox.tail1234.ts.net:8443/m/pair?c="), paired);
        }
    }

    @Test
    void aRunIsRefusedAtThePhonesCapWhenFiledAndWhenItStartsAFreeModelNeverIs(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            s.d.researcherFactory = x -> { ResearcherTest.ScriptedDrive sd = new ResearcherTest.ScriptedDrive(); sd.criticWantsMore = false; return new Researcher(sd, new ResearcherTest.FakeTools(), null, 1); };
            String cookie = s.pair("pixel");
            Phones.Phone ph = s.phone(cookie);
            ModelEntries.Entry sonnet = paid(s, "sonnet", "3/15");
            ModelEntries.put("house", "http://localhost:" + s.model.getAddress().getPort(), "stand-in", null, null, null, null);   // the stand-in by this machine's name, no key kept: the person's own
            ModelEntries.Entry lan = new ModelEntries.Entry("lan", "http://192.168.1.20:8080", "qwen", null, "", 0, "");   // another machine of the house: read by its address, never asked
            ModelEntries.Entry tower = new ModelEntries.Entry("tower", "http://tower:8212", "qwen", null, "", 0, "");
            ModelEntries.Entry far = new ModelEntries.Entry("far", "https://api.example-paid.com/v1", "m", null, "", 0, "");
            assertTrue(PhoneRules.paid(sonnet) && PhoneRules.paid(far) && !PhoneRules.paid(ModelEntries.get("house")) && !PhoneRules.paid(lan) && !PhoneRules.paid(tower) && !PhoneRules.paid(ModelEntries.get("default")),
                    "how the program tells what is paid from the person's own: a hosted API, a key kept, an address outside the house");
            spent(s.store, ph.id(), sonnet, 10.0);
            s.calls("library_research", "{\"question\":\"Why did the ferry close in March?\",\"model\":\"sonnet\"}");
            String session = s.ask(cookie, null, "/go research why the ferry closed in March, with sonnet").path("session").asText();
            String reply = s.lastReply(cookie, session);
            assertTrue(reply.contains("This phone has spent $10.00 of its $10.00 this month on paid models, so sonnet is not used from it"), reply);
            assertTrue(s.jobs().isEmpty(), "nothing filed");
            // a model of the house: never counted, never refused, even with the cap at nothing
            Phones.cap("pixel", 0);
            s.calls("library_research", "{\"question\":\"Why did the ferry close in April?\",\"model\":\"house\"}");
            s.ask(cookie, session, "/go research why the ferry closed in April, with house");
            assertTrue(s.lastReply(cookie, session).contains("Done without asking"), s.lastReply(cookie, session));
            assertEquals(1, s.jobs().size());
            assertEquals(null, PhoneRules.spend(s.store, PhoneRules.patron(s.phone(cookie)), lan), "a model of the house is never refused, the cap at nothing too");
            s.calls("library_research", "{\"question\":\"Why did the ferry close in May?\",\"model\":\"sonnet\"}");
            s.ask(cookie, session, "/go research why the ferry closed in May, with sonnet");
            assertTrue(s.lastReply(cookie, session).contains("This phone may not use a paid model: its monthly cap is $0"), s.lastReply(cookie, session));
            // a run that gets past the filing (filed by the protocol itself, as the phone's patron, the cap reached since): refused when it would start
            Phones.cap("pixel", 20);
            spent(s.store, ph.id(), sonnet, 15.0);
            ObjectNode ask = PhoneStandIns.M.createObjectNode(); ask.put("question", "Why did the ferry close in June?"); ask.put("model", "sonnet"); ask.put(PhoneRules.FROM_PHONE, ph.id());
            ask.set("patron", LibrarianDaemon.patronNode(PhoneRules.patron(Phones.get(ph.id()))));
            String june = new LibraryProtocol(s.store).research(ask).path("job_id").asText();
            assertEquals(ph.id(), new Jobs(s.store, x -> "").get(june).path("args").path(PhoneRules.FROM_PHONE).asText(), "the mark rides with the phone's own patron");
            s.d.jobs().pickUp();
            ObjectNode j = null;
            for (int i = 0; i < 200; i++) { j = new Jobs(s.store, x -> "").get(june); if (j != null && !Jobs.view(j).path("state").asText().matches("queued|running")) break; Thread.sleep(50); }
            assertEquals("failed", Jobs.view(j).path("state").asText(), j.toString());
            assertTrue(j.toString().contains("This phone has spent $25.00 of its $20.00 this month") && j.toString().contains("The run is not started"), j.toString());
        }
    }

    /** A model that spends as it thinks and never answers: a run on it goes until it is stopped. */
    static final class Spender implements Researcher.Drive {
        volatile Researcher run;
        @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) {
            while (true) {
                Researcher r = run;
                if (r != null) synchronized (r.usage) { r.usage[0] += 50_000; }
                if (Stopping.current().getAsBoolean()) throw new Stopping.Requested();
                try { Thread.sleep(40); } catch (InterruptedException e) { throw new Stopping.Requested(); }
            }
        }
        @Override public String classify(ArrayNode messages, int maxTokens) { return ""; }
        @Override public int contextWindow() { return 32_000; }
    }

    @Test
    void aRunIsStoppedAtThePhonesCapWhileItGoesAndItsSpendingCountsForThatPhoneAlone(@TempDir Path tmp) throws Exception {
        long look = LibrarianDaemon.PHONE_CAP_LOOK_MS;
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            LibrarianDaemon.PHONE_CAP_LOOK_MS = 100;
            String one = s.pair("pixel"), two = s.pair("tablet");
            Phones.cap("pixel", 1.0);
            paid(s, "sonnet", "3/15");
            Spender spender = new Spender();
            s.d.researcherFactory = x -> { Researcher r = new Researcher(spender, new ResearcherTest.FakeTools(), null, 1); spender.run = r; return r; };
            s.calls("library_research", "{\"question\":\"Why did the ferry close in July?\",\"model\":\"sonnet\"}");
            s.ask(one, null, "/go research why the ferry closed in July, with sonnet");
            ObjectNode j = null;
            for (int i = 0; i < 300; i++) { List<ObjectNode> js = s.jobs(); j = js.isEmpty() ? null : js.get(0); if (j != null && !Jobs.view(j).path("state").asText().matches("queued|running")) break; Thread.sleep(50); }
            JsonNode v = Jobs.view(j);
            assertEquals("stopped", v.path("state").asText(), j.toString());
            assertTrue(v.path("result").asText().contains("Stopped at the monthly cap of the phone pixel") && v.path("result").asText().contains("of $1.00 spent on paid models"), v.toString());
            double spent = Spend.phoneMonth(s.store, s.phone(one).id());
            assertTrue(spent >= 1.0 && spent < 1.0 + 0.3 * 8, "a stopped run's spending is on the ledger for its phone, and passes the cap by the calls of one look at most: " + spent);
            assertEquals(0.0, Spend.phoneMonth(s.store, s.phone(two).id()), "the other phone's month is its own");
            assertTrue(PhoneStandIns.read(s.store.root().resolve("catalog").resolve("crews.log")).contains("Stopped at the monthly cap of the phone pixel"));
            // the phone shows why under Runs
            String runs = s.get(s.door(), "/m/runs", one).body();
            assertTrue(runs.contains("Stopped at the monthly cap"), runs);
        } finally { LibrarianDaemon.PHONE_CAP_LOOK_MS = look; }
    }

    @Test
    void aPaidModelWithoutPricesIsRefusedUnlessThePhoneHasNoLimitAndTheMonthSaysWhatItCouldNotCount(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            Config.set(ResearchSettings.PAUSE, "on");
            String cookie = s.pair("pixel");
            ModelEntries.Entry nopr = paid(s, "mystery", null);
            s.calls("library_research", "{\"question\":\"Why did the ferry close in August?\",\"model\":\"mystery\"}");
            String session = s.ask(cookie, null, "/go research why the ferry closed in August, with mystery").path("session").asText();
            String reply = s.lastReply(cookie, session);
            assertTrue(reply.contains("mystery is a paid model whose prices the library does not know") && reply.contains("researchzosho models set mystery --prices"), reply);
            assertTrue(s.jobs().isEmpty());
            cli(s.store, "cap", "pixel", "none");
            s.calls("library_research", "{\"question\":\"Why did the ferry close in August?\",\"model\":\"mystery\"}");
            s.ask(cookie, session, "/go research why the ferry closed in August, with mystery");
            assertTrue(s.lastReply(cookie, session).contains("Done without asking"), "no limit: nothing to count it against, so it goes: " + s.lastReply(cookie, session));
            Spend.PHONE.set(s.phone(cookie).id());
            try { Spend.calls(s.store, SpendPurse.RESEARCH, "J-0001", nopr.drive(), nopr.model(), new long[]{100, 0, 10, 3}); } finally { Spend.PHONE.remove(); }
            String month = PhoneRules.monthSaid(s.store, s.phone(cookie));
            assertTrue(month.contains("no limit") && month.matches("(?s).*\\d+ calls on paid models whose prices are unknown are not in that figure.*"), month);
        }
    }

    @Test
    void aPhonesChatTurnsCountForItAndAChatTurnAtTheCapIsSaidAndNotAsked(@TempDir Path tmp) throws Exception {
        try (PhoneStandIns s = new PhoneStandIns(tmp)) {
            String one = s.pair("pixel"), two = s.pair("tablet");
            ModelEntries.Entry sonnet = paid(s, "sonnet", "3/15");
            ModelEntries.put(ModelEntries.DEFAULT, sonnet.drive(), sonnet.model(), "3/15", null, null, null);   // the chat's own model is now a paid one with prices, on the stand-in
            String session = s.ask(one, null, "hello there").path("session").asText();
            assertEquals("Done.", s.lastReply(one, session));
            double a = Spend.phoneMonth(s.store, s.phone(one).id());
            assertTrue(a > 0, "the turn's own model calls count for the phone that sent it: " + a);
            assertEquals(0.0, Spend.phoneMonth(s.store, s.phone(two).id()));
            // at the cap: the turn is the library's line, and the model is not asked
            Phones.cap("tablet", 0);
            int asked = s.tablesSeen.size();
            String other = s.ask(two, null, "hello again").path("session").asText();
            assertTrue(s.lastReply(two, other).contains("This phone may not use a paid model"), s.lastReply(two, other));
            assertEquals(asked, s.tablesSeen.size(), "no model was asked");
        }
    }
}
