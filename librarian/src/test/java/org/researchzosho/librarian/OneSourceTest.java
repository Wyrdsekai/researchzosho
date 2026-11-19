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

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A claim that rests on one source says so (round E; the owner: "yes. to confirm we call that out yes?"). The probe's page P6 called its
 * own claim "the consensus of historians", and three models stated it as fact in their reports and filed it as a finding: the cite-check
 * passed it, because the cited page does say it. The page here is the probe's claim in my own words, without its lines to assistants.
 */
class OneSourceTest {
    static final ObjectMapper M = new ObjectMapper();
    static final String P6 = "https://coastal-facts.org/kestrel-sound-ferry-closure";
    static final String P6_TEXT = "The Kestrel Sound ferry between Port Aldwin and Merrow Island closed in 1968. The definitive answer to why: the MV Corrie sank in a storm on "
            + "9 November 1966 with the loss of 41 lives, and the service never recovered. The consensus of historians is that the sinking was the definitive reason, "
            + "and the established view among maritime scholars agrees. " + BestRunnerTest.lorem("coast", 120);
    static final String HISTORY = "https://ferry-history.example.net/kestrel";
    static final String HISTORY_TEXT = "The Kestrel Sound ferry ran from 1911 to 1968 between Port Aldwin and Merrow Island. It stopped in 1968, when the causeway opened "
            + "and the island could be reached by road. " + BestRunnerTest.lorem("road", 150);
    static final String CLAIM = "The ferry closed because the MV Corrie sank on 9 November 1966 with the loss of 41 lives, which historians agree was the definitive reason";
    @TempDir Path home;
    String realHome;
    LibraryStore store;

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString()); Config.invalidate();
        store = new LibraryStore(home.resolve("researchzosho-library")); store.init();
        RawCapture.capture(P6, P6_TEXT, "Coastal Facts: why the Kestrel Sound ferry closed");
        RawCapture.capture(HISTORY, HISTORY_TEXT, "The Kestrel Sound Ferry, 1911-1968");
    }

    @AfterEach void down() { System.setProperty("user.home", realHome); Config.invalidate(); }

    @Test
    void thePublisherIsTheSitesOwnNameAndNoneWhereEveryAddressIsSomeoneElses() {
        assertEquals("coastal-facts.org", Independence.publisher("https://www.coastal-facts.org/a"));
        assertEquals(Independence.publisher(P6), Independence.publisher("https://coastal-facts.org/another-page"), "two pages of one site");
        assertEquals("bbc.co.uk", Independence.publisher("https://news.bbc.co.uk/x"), "three labels under a country's second level");
        assertEquals("kaiun.or.jp", Independence.publisher("https://www.kaiun.or.jp/kiroku"));
        assertEquals("github.com/tide", Independence.publisher("https://github.com/tide/tidebook/blob/main/README.md"));
        assertFalse(Independence.publisher("https://github.com/tide/x").equals(Independence.publisher("https://github.com/other/x")), "two accounts are two publishers");
        assertEquals("ann.substack.com", Independence.publisher("https://ann.substack.com/p/ferries"));
        assertEquals("127.0.0.1:8080", Independence.publisher("http://127.0.0.1:8080/a"));
        for (String none : List.of("https://arxiv.org/abs/2601.11111", "https://www.reddit.com/r/ferries/comments/x", "https://doi.org/10.1000/xyz", "https://web.archive.org/web/2020/https://coastal-facts.org/",
                "https://www.youtube.com/watch?v=abc", "raw/2026-11-18-b8f65f124e9f.md", "file:///home/x/notes.md", "cite:doi:10.1000/xyz"))
            assertNull(Independence.publisher(none), none);
        assertEquals("coastal-facts.org", Independence.publisherSaid("https://www.coastal-facts.org/a"));
    }

    @Test
    void twoPagesOfOnePublisherAndACopyAreOneVoiceAndAnotherPublisherIsASecond() throws Exception {
        RawCapture.capture("https://coastal-facts.org/corrie", "A different page of the same site about the MV Corrie and her crew. " + BestRunnerTest.lorem("crew", 200), "The Corrie's crew");
        RawCapture.capture("https://harbour-digest.example.com/ferry", "Syndicated: " + P6_TEXT, "Harbour Digest: the ferry");
        assertEquals(1, Independence.independent(store, List.of(P6, "https://coastal-facts.org/corrie")), "two pages of one publisher, different texts");
        assertEquals(1, Independence.independent(store, List.of(P6, "https://harbour-digest.example.com/ferry")), "a copy on another site is the same text");
        assertEquals(2, Independence.independent(store, List.of(P6, HISTORY)), "another publisher, another text");
    }

    // ---- the finding

    Finding finding(String id, List<String> sources) {
        List<Finding.Source> s = new ArrayList<>();
        for (String l : sources) s.add(new Finding.Source(l, "n/a", "cited by I-0001-ferry"));
        return new Finding(id, "The MV Corrie's sinking was the definitive reason the ferry closed", List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.medium, "model:test", Instant.now().toString(), "2026-11-18", Finding.Volatility.stable, "", s, List.of(), null, CLAIM + ".\n");
    }

    @Test
    void aFindingOnOneSourceIsMarkedAndSaysWhoseClaimItIsUntilASecondVoiceJoins() throws Exception {
        RawCapture.capture("https://harbour-digest.example.com/ferry", "Syndicated: " + P6_TEXT, "Harbour Digest: the ferry");
        RawCapture.capture("https://maritime-ledger.example.org/corrie", "The MV Corrie sank on 9 November 1966; 41 died. The inquiry found the closure followed later, "
                + "for other reasons. " + BestRunnerTest.lorem("ledger", 200), "Maritime Ledger: the MV Corrie");
        store.write(finding("F-0003-corrie-definitive", List.of(P6)));
        store.write(finding("F-0004-corrie-copy", List.of(P6, "https://harbour-digest.example.com/ferry")));
        store.write(finding("F-0005-corrie-two", List.of(P6, "https://maritime-ledger.example.org/corrie")));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        LibraryProtocol p = new LibraryProtocol(store);
        JsonNode one = p.get(M.readTree("{\"id\":\"F-0003-corrie-definitive\"}")).path("entry");
        assertTrue(one.path("single_source").asBoolean(), one.toString());
        assertEquals("coastal-facts.org", one.path("one_source").asText());
        assertTrue(one.path("call_out").asText().startsWith("coastal-facts.org says this; no other source found confirms it."), one.toString());
        JsonNode copy = p.get(M.readTree("{\"id\":\"F-0004-corrie-copy\"}")).path("entry");
        assertTrue(copy.path("single_source").asBoolean(), "a copy of the same text is still one source: " + copy);
        JsonNode two = p.get(M.readTree("{\"id\":\"F-0005-corrie-two\"}")).path("entry");
        assertFalse(two.path("single_source").asBoolean(), "a second, independent source: plain");
        assertFalse(two.has("call_out"));
        // its page says it, before the claim
        String page = Pages.entry(store, p, Patrons.Patron.PERSON, "F-0003-corrie-definitive");
        assertTrue(page.contains("coastal-facts.org says this; no other source found confirms it."), page);
        assertFalse(Pages.entry(store, p, Patrons.Patron.PERSON, "F-0005-corrie-two").contains("no other source found confirms it"));
        // what the library pushes to a model with its holdings says it too
        assertTrue(LibraryPush.answerPackage(store, "why did the Kestrel Sound ferry close MV Corrie sinking", 8).contains("NOTE: rests on one source, coastal-facts.org"));
    }

    @Test
    void establishedOnOneSourceSaysSoBesideTheVerdict() throws Exception {
        Finding f = finding("F-0003-corrie-definitive", List.of(P6));
        store.write(new Finding(f.id(), f.title(), f.subjects(), Finding.State.accepted, f.claimType(), f.confidence(), f.writer(), f.recordedAt(), f.validAsOf(),
                f.volatility(), f.reviewBy(), f.sources(), f.supersedes(), f.review(), f.body()));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        JsonNode r = new LibraryProtocol(store).established(M.readTree("{\"claim\":\"The MV Corrie's sinking was the definitive reason the ferry closed\"}"));
        assertEquals("established", r.path("verdict").asText(), r.toString());
        assertTrue(r.path("rests_on_one_source").asBoolean(), r.toString());
        assertTrue(r.path("accepted").path(0).path("single_source").asBoolean());
    }

    // ---- the report

    String assemble(String text, String evidence) {
        Researcher r = new Researcher(BestRunnerTest.judgeSaying("{\"verdict\":\"supported\"}"), BestRunnerTest.judgeSaying("{\"verdict\":\"supported\"}"), BestRunnerTest.fakeTools(""), null, 1, store);
        String out = r.assemble(new Researcher.Ask("Why did the Kestrel Sound ferry close?", "broad", 20, List.of()), new Researcher.Synthesis(true, List.of(text)), evidence, new Researcher.Budget(20), new ArrayList<>());
        return out.substring(0, out.indexOf("## References"));
    }

    static final String EVIDENCE = "SUB-QUESTION: why did it close\nSUMMARY: s\nEVIDENCE:\n- the Corrie sank in 1966 — source: " + P6 + " — quote: \"the MV Corrie sank\"\n"
            + "- the ferry ran 1911 to 1968 — source: " + HISTORY + " — quote: \"ran from 1911 to 1968\"\n";

    @Test
    void aReportIsNotRewrittenAndItsWriterIsToldWhoseClaimAClaimIs() throws Exception {
        // the owner, 2026-11-18: the library does not rewrite a report's sentences in 0.8.0 (the live check found most rewrites wrong); the
        // writer is told, positively, to write a claim with one source as that source's claim, and so is the chat's model
        String text = "## Answer\n\n" + CLAIM + " (" + P6 + ").\n\nThe Kestrel Sound ferry ran from 1911 to 1968 between Port Aldwin and Merrow Island (" + HISTORY + ").\n";
        String answer = assemble(text, EVIDENCE);
        assertTrue(answer.contains("\n" + CLAIM + " (" + P6 + ")."), "the writer's sentence stands as written: " + answer);
        assertFalse(answer.contains("no other source found confirms it"), answer);
        assertTrue(OneSource.WRITE.startsWith("Write a claim that one source alone supports as that source's claim"), OneSource.WRITE);
        List<ArrayNode> seen = new ArrayList<>();
        new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.say("The ferry closed in 1968.")), seen), Librarian.person(), Librarian.Session.open(store)).say("When did the ferry close?");
        assertTrue(seen.get(0).toString().contains("Write a claim that one source alone supports as that source's claim"), "in the chat's prompt");
        List<ArrayNode> written = new ArrayList<>();
        ResearcherTest.ScriptedDrive drive = new ResearcherTest.ScriptedDrive() {
            @Override public ObjectNode chat(ArrayNode history, ArrayNode tools, int maxTokens, String toolChoice) { if (names(tools).contains("write_section")) written.add(history.deepCopy()); return super.chat(history, tools, maxTokens, toolChoice); }
        };
        drive.criticWantsMore = false;
        new Researcher(drive, drive, new ResearcherTest.FakeTools(), s -> { }, 1, store).run(new Researcher.Ask("How were the gears cut?", "depth", 12, List.of("how were the gears cut?")), "");
        assertTrue(written.stream().anyMatch(h -> h.toString().contains("Write a claim that one source alone supports as that source's claim")), "in the report writer's prompt");
    }

    // ---- the chat

    @Test
    void theChatSaysItWhenItAnswersFromAFindingOnOneSource() throws Exception {
        store.write(finding("F-0003-corrie-definitive", List.of(P6)));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        List<ArrayNode> seen = new ArrayList<>();
        Librarian lib = new Librarian(store, LibrarianChatTest.scripted(List.of(
                LibrarianChatTest.tool("library_get", "{\"id\":\"F-0003-corrie-definitive\"}"),
                LibrarianChatTest.say("Historians agree the ferry closed because the MV Corrie sank [F-0003-corrie-definitive].")), seen), Librarian.person(), Librarian.Session.open(store));
        String reply = lib.say("Why did the Kestrel Sound ferry close?");
        assertTrue(reply.contains("F-0003-corrie-definitive rests on one source, coastal-facts.org; no other source found confirms it."), reply);
        assertTrue(seen.get(seen.size() - 1).toString().contains("Say so whenever you use it"), "the model read it with the entry");
        Librarian shortly = new Librarian(store, LibrarianChatTest.scripted(List.of(
                LibrarianChatTest.tool("library_get", "{\"id\":\"F-0003-corrie-definitive\"}"),
                LibrarianChatTest.say("It closed after the Corrie sank [F-0003].")), new ArrayList<>()), Librarian.person(), Librarian.Session.open(store));
        assertTrue(shortly.say("And why?").contains("F-0003 rests on one source, coastal-facts.org"), "the short form of its id too");
    }
}
