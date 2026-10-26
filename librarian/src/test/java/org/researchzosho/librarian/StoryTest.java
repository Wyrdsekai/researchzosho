package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** The story of a subject (0.6.6): chapters by decade from the claims, every sentence citing its claim, filed as a report and listed on the dossier. */
class StoryTest {
    private static final ObjectMapper J = new ObjectMapper();
    private static ObjectNode patron() { ObjectNode a = J.createObjectNode(); a.putObject("patron").put("did", "did:key:tester").put("name", "Tester").put("runtime", "test"); return a; }

    static Finding claim(LibraryStore store, String title, String body, String locator) throws Exception {
        String id = store.nextFindingId(title);
        Finding f = new Finding(id, title, List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.medium, "patron:test", Instant.now().toString(), "2026-10-24", Finding.Volatility.slow, "",
                List.of(new Finding.Source(locator, "n/a", "x")), List.of(), null, body + "\n");
        store.write(f); return f;
    }

    @Test
    void thinDecadesFoldIntoTheNextChapterAndAThinTailIntoTheOneBefore() {
        java.util.function.Function<Integer, List<Finding>> n = k -> java.util.Collections.nCopies(k, (Finding) null);
        Map<String, List<Finding>> decades = new TreeMap<>(Map.of("1950s", n.apply(1), "1980s", n.apply(1), "1990s", n.apply(4), "2010s", n.apply(3), "2020s", n.apply(1)));
        assertEquals(List.of("1950s–1990s", "2010s–2020s"), new ArrayList<>(Story.chapters(decades).keySet()));
        assertEquals(6, Story.chapters(decades).get("1950s–1990s").size()); assertEquals(4, Story.chapters(decades).get("2010s–2020s").size());
        assertEquals(List.of("1990s"), new ArrayList<>(Story.chapters(new TreeMap<>(Map.of("1990s", n.apply(5)))).keySet()));
        assertEquals(List.of("1990s–2000s"), new ArrayList<>(Story.chapters(new TreeMap<>(Map.of("1990s", n.apply(1), "2000s", n.apply(1)))).keySet()), "two thin decades and nothing else are one chapter");
    }

    @Test
    void theStoryIsWrittenADecadeAChapterFromTheClaimsAndListedOnTheDossier(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        claim(store, "Ada Example joined Acme", "In 1999 Ada Example joined Acme as a director.", "https://www.sec.gov/x");
        claim(store, "Ada Example left Acme", "In 2004 Ada Example left Acme after the layoffs.", "https://www.nytimes.com/x");
        claim(store, "Ada Example's trade", "Ada Example is an engineer.", "https://example.com/about");
        claim(store, "Ada Example: what it was like", "The Burlington team was gone within two years.", "told://interview/ada-example/2026-10-24");
        // a stand-in model: each chapter cites the first claim it was given and names a told one as told
        Researcher.Drive drive = new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String c) { return null; }
            @Override public String classify(ArrayNode m, int x) {
                String u = m.get(m.size() - 1).path("content").asText();
                Matcher id = Pattern.compile("\\[(F-\\d+-[a-z0-9-]+)\\]").matcher(u);
                String first = id.find() ? id.group(1) : "none";
                return "A chapter of prose [" + first + "]." + (u.contains(", told by") ? " As Tester told the library, the team was gone within two years [" + first + "]." : "");
            }
            @Override public int contextWindow() { return 8192; }
        };
        LibraryProtocol p = new LibraryProtocol(store);
        Story.DRIVE = () -> drive;
        ObjectNode r;
        try { r = p.dossier(patron().put("subject", "Ada Example").put("op", "story")); } finally { Story.DRIVE = Explain::drive; }
        assertEquals("Ada Example", r.path("subject").asText());
        assertEquals(2, r.path("chapters").asInt(), r.toString());   // Background (the undated two), then the thin 1990s and 2000s folded into one chapter
        assertEquals(4, r.path("claims").asInt());
        String id = r.path("id").asText(); assertTrue(id.startsWith("I-"), id);
        Investigation inv = store.investigation(id);
        assertEquals("The story of Ada Example", inv.title()); assertEquals("librarian:story", inv.writer()); assertEquals(4, inv.findings().size());
        String md = inv.body();
        assertTrue(md.startsWith("# The story of Ada Example\n"), md);
        assertTrue(md.indexOf("## Background") < md.indexOf("## 1990s–2000s") && md.contains("## Sources\n"), md);
        assertFalse(md.contains("## 2000s\n"), "a decade with one claim is no chapter of its own: " + md);
        assertTrue(md.contains("As Tester told the library"), "the told claim is said to be theirs: " + md);
        assertTrue(md.contains("(told)"), md);
        String dossier = p.dossier(patron().put("subject", "Ada Example")).path("markdown").asText();
        assertTrue(dossier.contains("## Story\n\n- [The story of Ada Example](/entry/" + id + ")"), dossier);
        assertFalse(dossier.contains(id + " — The story of"), "the story is not listed twice: " + dossier);
        assertTrue(dossier.contains("from 4 claims, chapter by chapter; current with the shelves"), dossier);
        // the story follows the shelves: a report on the subject lands, its timeline is filed, and the story is written again from more claims
        Story.DRIVE = () -> drive;
        try {
            String i2 = store.nextInvestigationId("Who is Ada Example? Her life.");
            Investigation later = new Investigation(i2, "Who is Ada Example? Her life.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(),
                    "## Timeline\n\n1981: B.A., Boston University [1]. 2010: Founded Acme Two [1].\n\n## References\n\n[1] https://a.example/bib\n");
            store.write(later);
            List<String> filed = Profiles.named("person").onReport(store, later);
            assertEquals(2, filed.size(), "the timeline's two events, filed: " + filed);
            Investigation fresh = Story.existing(store, "Ada Example");
            assertNotEquals(id, fresh.id(), "a new story report; the earlier one stays");
            assertEquals(6, fresh.findings().size(), "written from the grown shelves");
            assertNotNull(store.investigation(id), "the earlier version is kept");
            String again = p.dossier(patron().put("subject", "Ada Example")).path("markdown").asText();
            assertTrue(again.contains("](/entry/" + fresh.id() + ")") && again.contains("from 6 claims") && !again.contains("](/entry/" + id + ")"), "the dossier points at the newest: " + again);
        } finally { Story.DRIVE = Explain::drive; }
        // without a model the story says so; with nothing on the shelves it says how to start
        Story.DRIVE = () -> null;
        try {
            assertThrows(ProtocolError.class, () -> p.dossier(patron().put("subject", "Ada Example").put("op", "story")));
            assertNull(Story.refresh(store, "Ada Example"), "no model, no rewrite, no error at landing");
        } finally { Story.DRIVE = Explain::drive; }
    }
}
