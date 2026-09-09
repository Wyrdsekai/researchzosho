package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MindMapTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    @Test
    void insertionsBuildTheTreeAndUnknownParentsAttachAtRoot() throws Exception {
        MindMap map = new MindMap("JA subtitle register");
        int n = map.apply(json("""
                [{"parent": "JA subtitle register", "concept": "sociolect markers",
                  "note": "yakuza speech drops keigo https://example.org/a"},
                 {"parent": "sociolect markers", "concept": "pronouns",
                  "note": "ore/temee signal register"},
                 {"parent": "no such parent", "concept": "EN genre voice",
                  "note": "noir renders threat tersely"}]"""));
        assertEquals(3, n);
        String r = map.render();
        assertTrue(r.contains("◆ JA subtitle register"), r);
        assertTrue(r.indexOf("sociolect markers") < r.indexOf("pronouns"), "nesting held");
        assertTrue(r.contains("EN genre voice"), "unknown parent attaches at root, never dropped");
    }

    @Test
    void duplicateNotesAndBlankInsertionsAreIgnored() throws Exception {
        MindMap map = new MindMap("t");
        assertEquals(1, map.apply(json("""
                [{"parent": "t", "concept": "c", "note": "same"},
                 {"parent": "t", "concept": "c", "note": "same"},
                 {"parent": "t", "concept": "", "note": "no concept"},
                 {"parent": "t", "concept": "c2", "note": ""}]""")));
    }

    @Test
    void boundsHold() throws Exception {
        MindMap map = new MindMap("t");
        for (int i = 0; i < MindMap.MAX_NODES + 20; i++) {
            map.apply(json("[{\"parent\": \"t\", \"concept\": \"c" + i
                    + "\", \"note\": \"note " + i + "\"}]"));
        }
        assertTrue(map.nodes() <= MindMap.MAX_NODES, "node cap holds: " + map.nodes());
    }

    @Test
    void investigationBodyComesFromTheTreeAndCarriesUrls() throws Exception {
        MindMap map = new MindMap("JA subtitle register");
        map.apply(json("""
                [{"parent": "JA subtitle register", "concept": "sociolect markers",
                  "note": "keigo dropping per https://example.org/a"}]"""));
        String body = map.toInvestigationBody();
        assertTrue(body.contains("## Question"), body);
        assertTrue(body.contains("### sociolect markers"), body);
        assertTrue(body.contains("## Sources cited"), body);
        assertTrue(body.contains("https://example.org/a"), body);
        // and the body parses into a valid draft investigation end to end
        Investigation inv = new Investigation("I-0001-ja-subtitle-register",
                "JA subtitle register", Finding.State.draft, "person+model",
                "2026-09-01T10:00:00Z", java.util.List.of(), java.util.List.of(), body);
        assertEquals(inv, Investigation.parse(inv.format()));
    }

    @Test
    void emptyLeavesAreTidiedAway() throws Exception {
        MindMap map = new MindMap("t");
        map.apply(json("""
                [{"parent": "t", "concept": "keeper", "note": "content"}]"""));
        // a concept with neither notes nor children never survives apply's tidy pass
        map.apply(json("""
                [{"parent": "keeper", "concept": "hollow", "note": ""}]"""));
        assertFalse(map.render().contains("hollow"), map.render());
    }
}
