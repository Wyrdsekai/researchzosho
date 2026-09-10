package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Setting and unsetting what the housekeeping runs on its own: kept searches and open questions, over the protocol and the files. */
class HousekeepingCasesTest {

    static final ObjectMapper M = new ObjectMapper();

    static ObjectNode args(String json) throws Exception { return (ObjectNode) M.readTree(json); }

    @Test
    void aKeptSearchIsAddedListedAndRemovedOverTheProtocol(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.set(store, "did:key:zA", "A", Patrons.Level.write);
        var p = new LibraryProtocol(store);
        var kept = p.serials(args("{\"op\":\"add\",\"name\":\"Lead Paint!\",\"query\":\"lead paint regulation news\",\"every_days\":14,\"patron\":{\"did\":\"did:key:zA\"}}"));
        assertEquals("lead-paint", kept.get("name").asText(), "the name is made file-safe");
        var list = p.serials(args("{\"op\":\"list\"}"));
        assertEquals(1, list.get("searches").size());
        assertEquals("lead paint regulation news", list.get("searches").get(0).get("query").asText());
        assertTrue(list.get("searches").get(0).get("due").asBoolean(), "never run: due");
        assertTrue(java.nio.file.Files.readString(Serials.shelvesFile(store)).contains("- lead-paint | lead paint regulation news | every 14 days | last -"), "the file is the plain line the guide documents");
        var removed = p.serials(args("{\"op\":\"remove\",\"name\":\"lead-paint\",\"patron\":{\"did\":\"did:key:zA\"}}"));
        assertTrue(removed.get("removed").asBoolean());
        assertEquals(0, p.serials(args("{\"op\":\"list\"}")).get("searches").size());
        assertThrows(ProtocolError.class, () -> p.serials(args("{\"op\":\"remove\",\"name\":\"nope\",\"patron\":{\"did\":\"did:key:zA\"}}")));
    }

    @Test
    void anOpenQuestionCanBeDroppedAndStaysOnRecordClosed(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.set(store, "did:key:zA", "A", Patrons.Level.write);
        var p = new LibraryProtocol(store);
        p.frontier(args("{\"op\":\"add\",\"question\":\"when was lead paint banned in Japan\",\"patron\":{\"did\":\"did:key:zA\"}}"));
        assertEquals(1, p.frontier(args("{\"op\":\"list\"}")).get("questions").size());
        var dropped = p.frontier(args("{\"op\":\"drop\",\"question\":\"when was lead paint banned in Japan\",\"patron\":{\"did\":\"did:key:zA\"}}"));
        assertTrue(dropped.get("dropped").asBoolean());
        assertEquals(0, p.frontier(args("{\"op\":\"list\"}")).get("questions").size(), "no longer open");
        String file = java.nio.file.Files.readString(store.frontierFile());
        assertTrue(file.contains("when was lead paint banned in Japan ⇒ explored") && file.contains("dropped by patron:did:key:zA"), "kept on record, marked dropped: " + file);
        assertThrows(ProtocolError.class, () -> p.frontier(args("{\"op\":\"drop\",\"question\":\"when was lead paint banned in Japan\",\"patron\":{\"did\":\"did:key:zA\"}}")), "dropping twice is not found");
        assertFalse(Tonight.plan(store, java.time.LocalDate.now()).picks().stream().anyMatch(l -> l.text().contains("Japan")), "the explorer will not take a dropped question");
    }

    @Test
    void readingNeedsNoWriteAccessButChangingDoes(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.set(store, "did:key:zR", "R", Patrons.Level.read);
        var p = new LibraryProtocol(store);
        assertEquals(0, p.serials(args("{\"op\":\"list\",\"patron\":{\"did\":\"did:key:zR\"}}")).get("searches").size());
        assertThrows(ProtocolError.class, () -> p.serials(args("{\"op\":\"add\",\"name\":\"x\",\"query\":\"some query\",\"patron\":{\"did\":\"did:key:zR\"}}")));
    }

    @Test
    void everyDaysCanChangeWithoutLosingTheLastRun(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Patrons.set(store, "did:key:zA", "A", Patrons.Level.write);
        var p = new LibraryProtocol(store);
        p.serials(args("{\"op\":\"add\",\"name\":\"lead\",\"query\":\"lead paint news\",\"every_days\":7,\"patron\":{\"did\":\"did:key:zA\"}}"));
        Path shelves = store.root().resolve("catalog").resolve("shelves.md");
        java.nio.file.Files.writeString(shelves, java.nio.file.Files.readString(shelves).replace("| last -", "| last 2026-09-01"));
        var r = p.serials(args("{\"op\":\"every\",\"name\":\"lead\",\"every_days\":30,\"patron\":{\"did\":\"did:key:zA\"}}"));
        assertEquals(30, r.get("every_days").asInt());
        var s = Serials.shelves(store).get(0);
        assertEquals(30, s.everyDays());
        assertEquals("2026-09-01", s.lastChecked(), "the last-run date survives the change");
        assertThrows(ProtocolError.class, () -> p.serials(args("{\"op\":\"every\",\"name\":\"lead\",\"every_days\":0,\"patron\":{\"did\":\"did:key:zA\"}}")));
    }

    @Test
    void versionsCompareNumerically() {
        assertTrue(org.researchzosho.Version.compare("0.1.2", "0.1.1") > 0);
        assertTrue(org.researchzosho.Version.compare("0.10.0", "0.9.9") > 0);
        assertEquals(0, org.researchzosho.Version.compare("1.0.0", "1.0"));
        assertTrue(org.researchzosho.Version.compare("0.1.1", "0.1.2") < 0);
    }
}
