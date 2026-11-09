package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The keeper's actions and the reading actions as the chat's own commands (2026-11-07): explain, perspectives, established, read, remove,
 * the saved searches, the subjects, export, the MCP servers, dna, prefs, react, story and forecasts, the housekeeping, the readers, the
 * keys, the spend and the update, each answered by the library without the model, in sentences; the keeper's ones refused to a reader.
 */
class ChatCommandsMoreTest {
    @TempDir Path tmp;
    String realHome;

    @BeforeEach void scratch() throws Exception {
        QuietHome.quiet(tmp.resolve("home"));
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
    }
    @AfterEach void back() { System.setProperty("user.home", realHome); Config.invalidate(); }

    LibraryStore library() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        store.write(new Finding("F-0001-gears", "The gears were cut by hand", List.of("gears--cutting"), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high,
                "person", Instant.now().toString(), "2026-09-05", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://museum.example/gears", "n/a", "the museum's own page")), List.of(), null, "The museum states the gears were cut with files.\n"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        return store;
    }

    static String say(LibraryStore store, Patrons.Patron who, String line) throws IOException {
        Librarian.Session[] current = {Librarian.Session.open(store)};
        return ChatCommands.reply(store, who, "terminal", line, false, ChatCommandsTest.terminal(store, current));
    }

    @Test
    void theReadingActionsAnswerFromTheShelves() throws Exception {
        LibraryStore store = library();
        Patrons.Patron me = Patrons.Patron.PERSON;
        String explain = say(store, me, "/explain F-0001-gears --written");
        assertTrue(explain.contains("The museum states the gears were cut with files."), explain);
        assertTrue(say(store, me, "/established the gears were cut by hand").startsWith("Established: an accepted claim on the shelves bears on it.\n- [F-0001-gears]"), say(store, me, "/established the gears were cut by hand"));
        assertTrue(say(store, me, "/established the moons of Jupiter are made of cheese").startsWith("Not established:"));
        String read = say(store, me, "/read F-0001-gears");
        assertTrue(read.startsWith("The gears were cut by hand  [F-0001-gears]") && read.contains("cut with files"), read);
        String nowhere = say(store, me, "/read https://nowhere.example/page");
        assertTrue(nowhere.contains("No saved page matches"), nowhere);
        String perspectives = say(store, me, "/perspectives how were the gears of the mechanism cut?");
        assertTrue(perspectives.contains("No model") || perspectives.contains("—"), "a model is needed, and said so when absent: " + perspectives);
        assertTrue(say(store, me, "/explain").startsWith("Type /explain"));
    }

    @Test
    void theKeeperActsAndAReaderIsRefused() throws Exception {
        LibraryStore store = library();
        Patrons.Patron me = Patrons.Patron.PERSON, reader = new Patrons.Patron("did:key:reader", "a reader", "token");
        Patrons.setDefault(store, Patrons.Level.read);   // a fresh library lets anyone write; here a reader is a reader
        // the saved searches
        assertTrue(say(store, me, "/serials").startsWith("No saved search yet."));
        assertEquals("Kept: gears searches for \"antikythera gears\" every 3 days; what it finds lands in the inbox.", say(store, me, "/serials add gears antikythera gears 3"));
        assertTrue(say(store, me, "/serials").contains("- gears: antikythera gears · every 3 days"), say(store, me, "/serials"));
        assertEquals("The saved search gears is parked: it does not run until you unpark it.", say(store, me, "/serials park gears"));
        assertEquals("The saved search gears is removed.", say(store, me, "/serials remove gears"));
        // the proposed subjects
        assertEquals("No subjects are proposed.", say(store, me, "/subjects proposed"));
        Files.writeString(store.subjectsFile().resolveSibling("subjects.proposed.md"), "# Proposed subjects\n\n- gears--cutting — how the gears were cut\n- dials--saros — the Saros dial\n", StandardCharsets.UTF_8);
        assertTrue(say(store, me, "/subjects proposed").contains("1. gears--cutting — how the gears were cut"), say(store, me, "/subjects proposed"));
        String accepted = say(store, me, "/subjects accept 1 nope");
        assertTrue(accepted.startsWith("Accepted gears--cutting — how the gears were cut.\nNot a proposal: nope.\nNow researchzosho settle files the claims under it."), accepted);
        assertNotNull(Vocabulary.read(store.subjectsFile()).get("gears--cutting"), "it joined the subject list");
        assertTrue(say(store, me, "/subjects drop all").startsWith("Dropped dials--saros."));
        assertTrue(say(store, me, "/subjects").startsWith("The subjects, with how many claims each holds:"), say(store, me, "/subjects"));
        assertEquals("Deciding the proposed subjects is for those who may write to this library.", say(store, reader, "/subjects accept 1"));
        // export, the housekeeping, the spend, the update
        String md = say(store, me, "/export F-0001-gears --md");
        assertTrue(md.contains("is saved as a Markdown file at ") && md.contains("/exports/"), md);
        assertTrue(say(store, me, "/tonight").length() > 10);
        String crews = say(store, me, "/crews now");
        assertTrue(crews.startsWith("The housekeeping runs now, as J-"), crews);
        assertTrue(say(store, me, "/spend").startsWith("What research runs cost"));
        assertTrue(say(store, me, "/update").contains("researchzosho update now"));
        assertTrue(say(store, me, "/forecasts").length() > 5);
        assertTrue(say(store, me, "/dna").length() > 5);
        assertTrue(say(store, me, "/prefs").length() > 5);
        // the MCP servers, the readers and the keys: the keeper's alone
        String mcp = say(store, me, "/mcp list");
        assertFalse(mcp.contains("business of"), mcp);
        assertTrue(say(store, reader, "/mcp list").startsWith("The other MCP servers are set up by the person who keeps this library"));
        assertTrue(say(store, me, "/readers").startsWith("Anyone not listed may "), say(store, me, "/readers"));
        String invited = say(store, me, "/invite Ada Example read");
        assertTrue(invited.startsWith("Ada Example may read. Send them this link; it works once, for 7 days:\nhttp"), invited);
        assertTrue(say(store, me, "/readers allow did:key:zz write A Program").startsWith("did:key:zz may write."));
        assertTrue(say(store, me, "/readers token did:key:zz").startsWith("A token for did:key:zz, shown this once"));
        assertEquals("Anyone not listed may read.", say(store, me, "/readers default read"));
        assertTrue(say(store, me, "/readers").contains("Ada Example") && say(store, me, "/readers").contains("did:key:zz"));
        assertEquals("did:key:zz removed.", say(store, me, "/readers remove did:key:zz"));
        assertTrue(say(store, reader, "/readers").startsWith("The readers are the business of the person who keeps this library."));
        assertTrue(say(store, reader, "/keys").startsWith("The keys are the business of the person who keeps this library."));
        String keys = say(store, me, "/keys");
        assertNotNull(keys);
        // removing: shown first, then done with --yes; a reader may not
        assertEquals("Removing an entry is for those who may write to this library.", say(store, reader, "/remove F-0001-gears"));
        String preview = say(store, me, "/remove F-0001-gears");
        assertTrue(preview.contains("To go ahead: /remove F-0001-gears --yes"), preview);
        assertNotNull(store.finding("F-0001-gears"), "nothing went yet");
        String gone = say(store, me, "/remove F-0001-gears --yes");
        assertTrue(gone.contains("Deleted 1 entry."), gone);
        assertNull(store.finding("F-0001-gears"));
        // the help names them
        String help = ChatCommands.help(store, null);
        for (String w : List.of("/explain", "/perspectives", "/established", "/read", "/subjects", "/serials", "/export", "/remove", "/story", "/forecasts", "/dna", "/prefs", "/react", "/mcp", "/readers", "/invite", "/keys", "/crews", "/spend", "/update")) assertTrue(help.contains(w), w + " in " + help);
    }
}
