package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Two libraries: one asks the other, the answer stays labelled as the other's, and the hop stops there. */
class PeersTest {

    static final ObjectMapper M = new ObjectMapper();

    @Test
    void anAskCanPutTheQuestionToAPeerAndTheAnswerStaysTheirs(@TempDir Path tmp) throws Exception {
        // Alice's library holds a document about gears; ours holds nothing
        LibraryStore alice = new LibraryStore(tmp.resolve("alice")); alice.init();
        Path doc = tmp.resolve("gears.txt");
        Files.writeString(doc, "The Antikythera gears were cut by hand with files, one tooth at a time.");
        assertEquals(0, LibrarianCli.add(alice, new String[]{"researchzosho", "add", doc.toString()}));
        Patrons.set(alice, "did:key:us", "our library", Patrons.Level.read);
        String token = Patrons.issueToken(alice, "did:key:us");
        LibrarianDaemon aliceD = LibrarianDaemon.start(alice, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        LibraryStore ours = new LibraryStore(tmp.resolve("ours")); ours.init();
        try {
            Peers.add(ours, "alice", "http://127.0.0.1:" + aliceD.port(), token, List.of("family"));
            Peers.add(ours, "nobody", "http://127.0.0.1:1", "x", List.of("family"));   // a peer that is down
            var p = new LibraryProtocol(ours);
            ObjectNode r = p.ask((ObjectNode) M.readTree("{\"question\":\"how were the antikythera gears cut\",\"peers\":\"family\",\"patron\":{\"did\":\"person\",\"runtime\":\"chat\"}}"));
            assertTrue(r.get("holds_nothing").asBoolean(), "ours holds nothing");
            JsonNode peers = r.get("peers");
            assertEquals(2, peers.size(), "every peer in the group is reported, answered or not: " + peers);
            JsonNode a = peers.get(0);
            assertEquals("alice", a.get("peer").asText());
            assertEquals(alice.identity().id(), a.get("library_id").asText(), "the answer says whose it is");
            assertFalse(a.get("holds_nothing").asBoolean(), a.toString());
            assertTrue(a.get("rendered").asText().contains("cut by hand"), a.get("rendered").asText());
            assertTrue(peers.get(1).has("error"), "the peer that is down is reported, not dropped: " + peers.get(1));
            assertEquals(0, r.get("entries").size(), "nothing was copied into our answer");
            String text = Peers.render((com.fasterxml.jackson.databind.node.ArrayNode) peers);
            assertTrue(text.contains("== alice (alice) ==") && text.contains("== nobody ==") && text.contains("could not ask"), text);
            // one hop: alice's own answer over the wire was asked with peers=none, so her peers (none here) were not consulted;
            // and a peer's peers never appear in ours
            assertFalse(a.has("peers"), "a peer's answer carries no peers of its own");
            // an ask that says none asks nobody, even with a default group set
            ObjectNode none = p.ask((ObjectNode) M.readTree("{\"question\":\"how were the antikythera gears cut\",\"peers\":\"none\",\"patron\":{\"did\":\"person\",\"runtime\":\"chat\"}}"));
            assertFalse(none.has("peers"));
            assertTrue(Files.readString(ours.circulationFile()).contains("peers"), "the circulation log says peers were asked");
        } finally { aliceD.stop(); }
    }

    @Test
    void aPeerIsRefusedForAnAddressTheLibraryMustNotCall(@TempDir Path tmp) throws Exception {
        LibraryStore ours = new LibraryStore(tmp); ours.init();
        assertThrows(java.io.IOException.class, () -> Peers.add(ours, "meta", "http://169.254.169.254/", "t", List.of()));
        assertThrows(java.io.IOException.class, () -> Peers.add(ours, "", "http://127.0.0.1:1", "t", List.of()));
        Peers.add(ours, "bob", "http://127.0.0.1:1/", "t", List.of("lab", "family"));
        assertEquals(1, Peers.select(ours, "lab").size());
        assertEquals(1, Peers.select(ours, "bob").size());
        assertEquals(1, Peers.select(ours, "all").size());
        assertEquals(0, Peers.select(ours, "none").size());
        assertEquals(0, Peers.select(ours, "choir").size());
        assertTrue(Peers.remove(ours, "bob")); assertFalse(Peers.remove(ours, "bob"));
    }
}
