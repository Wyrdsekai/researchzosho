package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The seeded measurement from the design note: Swanson's bridge planted in a small library. Fish oil and Raynaud's
 * never appear in one source; both areas' claims speak of blood viscosity and platelet aggregation; two distractor
 * areas share only common words; one pair IS named together by a captured page and must not be proposed.
 */
class BridgesTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static java.util.function.Supplier<Researcher.Drive> drives;
    @BeforeAll static void noModel() { drives = Explain.DRIVES; Explain.DRIVES = () -> null; }
    @AfterAll static void restore() { Explain.DRIVES = drives; }

    static int n = 0;
    static Finding claim(String subject, String title, String body, String tripleS, String tripleP, String tripleO) {
        n++;
        return new Finding(String.format("F-%04d-%s", n, title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "")), title, List.of(subject), Finding.State.accepted,
                Finding.ClaimType.extraction, Finding.Confidence.high, "person", "2026-09-14T00:00:00Z", "2026-09-14", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/" + n, "n/a", "seed")), List.of(), null, body + "\n",
                tripleS == null ? null : new Finding.Triple(tripleS, tripleP, tripleO), List.of());
    }

    static LibraryStore seeded(Path home) throws Exception {
        LibraryStore store = new LibraryStore(home.resolve("lib")); store.init();
        Files.writeString(store.subjectsFile(), """
                # Subjects

                - nutrition--fish-oil — dietary fish oil and its effects
                - vascular--raynauds — Raynaud's disease
                - horology--antikythera — the Antikythera mechanism
                - genealogy--census — census records
                - vascular--migraine — migraine
                """);
        // area A: fish oil (never mentions Raynaud's)
        store.write(claim("nutrition--fish-oil", "Fish oil lowers blood viscosity", "Dietary fish oil lowers blood viscosity in healthy volunteers, measured over twelve weeks.", "fish oil", "lowers", "blood viscosity"));
        store.write(claim("nutrition--fish-oil", "Fish oil reduces platelet aggregation", "Eicosapentaenoic acid in fish oil reduces platelet aggregation and vascular reactivity.", "fish oil", "reduces", "platelet aggregation"));
        store.write(claim("nutrition--fish-oil", "Fish oil and triglycerides", "Fish oil supplements lower serum triglycerides.", "fish oil", "lowers", "triglycerides"));
        // area C: Raynaud's (never mentions fish oil)
        store.write(claim("vascular--raynauds", "Raynaud's involves high blood viscosity", "Patients with Raynaud's disease show raised blood viscosity and vascular reactivity to cold.", "Raynaud's disease", "involves", "blood viscosity"));
        store.write(claim("vascular--raynauds", "Platelet aggregation in Raynaud's", "Platelet aggregation is increased in Raynaud's disease, with episodic vasospasm of the digits.", "Raynaud's disease", "shows", "platelet aggregation"));
        // distractors: share only common words with A and C
        store.write(claim("horology--antikythera", "Gears cut by hand", "The Antikythera gears were cut by hand with files; the teeth are triangular, measured on the fragments.", "Antikythera mechanism", "has", "hand-cut gears"));
        store.write(claim("horology--antikythera", "Saros dial", "The back dial counts 223 lunar months, the Saros period, measured on the fragments.", "Antikythera mechanism", "shows", "Saros cycle"));
        store.write(claim("genealogy--census", "Census age rounding", "Ages in the 1841 census were rounded down to a multiple of five for adults, measured over the returns.", "1841 census", "rounds", "ages"));
        store.write(claim("genealogy--census", "Census enumerators", "Enumerators copied household schedules into books; the schedules were then destroyed.", "1841 census", "used", "enumerators"));
        // a pair that shares specific terms BUT a captured page names both: migraine and Raynaud's
        store.write(claim("vascular--migraine", "Migraine and platelet aggregation", "Platelet aggregation is raised during migraine attacks, with vascular reactivity and blood viscosity changes.", "migraine", "shows", "platelet aggregation"));
        store.write(claim("vascular--migraine", "Migraine and viscosity", "Blood viscosity rises in migraine with aura; vascular reactivity is altered.", "migraine", "raises", "blood viscosity"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        RawCapture.capture(store, "https://example.org/review", "A review notes that migraine and Raynaud's disease co-occur more often than chance, sharing vascular reactivity.", "Migraine and Raynaud's: a review", "test", "");
        return store;
    }

    @Test
    void thePlantedBridgeIsFoundFirstAndTheNamedPairIsNot(@TempDir Path home) throws Exception {
        LibraryStore store = seeded(home);
        List<Bridges.Area> areas = Bridges.areas(store);
        assertEquals(5, areas.size(), areas.stream().map(Bridges.Area::slug).toList().toString());
        List<Bridges.Pair> pairs = Bridges.candidates(store, Bridges.Settings.defaults().with("reach", "high"), "nutrition--fish-oil", new Random(1), null);
        assertFalse(pairs.isEmpty());
        Bridges.Pair top = pairs.get(0);
        assertEquals("vascular--raynauds", top.c().slug(), pairs.toString());
        assertEquals(0, top.coMentions());
        assertTrue(top.via().contains("viscosity") && top.via().contains("platelet") && top.via().contains("aggregation"), top.via().toString());
        assertFalse(top.via().contains("measured") || top.via().contains("fragments"), "a generic word or one area's own word is not a bridge: " + top.via());
        assertTrue(pairs.stream().noneMatch(p -> p.c().slug().equals("horology--antikythera") && p.coMentions() == 0 && p.via().size() >= 3), "the distractor shares nothing specific");
        // from Raynaud's: migraine shares the same terms but a captured review names both — not novel under strict
        List<Bridges.Pair> fromR = Bridges.candidates(store, Bridges.Settings.defaults().with("reach", "high"), "vascular--raynauds", new Random(1), null);
        Bridges.Pair mig = fromR.stream().filter(p -> p.c().slug().equals("vascular--migraine")).findFirst().orElseThrow();
        assertTrue(mig.coMentions() > 0, "the review names both");
        assertEquals("nutrition--fish-oil", fromR.get(0).c().slug(), "the novel pair ranks first");
        // loose: only a joint claim rules a pair out, so migraine comes through
        List<Bridges.Pair> loose = Bridges.candidates(store, Bridges.Settings.defaults().with("reach", "high").with("loose", ""), "vascular--raynauds", new Random(1), null);
        assertTrue(loose.stream().anyMatch(p -> p.c().slug().equals("vascular--migraine") && p.coMentions() == 0));
        // reach low: only neighbours (the same facet) — fish oil is not a neighbour of Raynaud's
        List<Bridges.Pair> low = Bridges.candidates(store, Bridges.Settings.defaults().with("reach", "low"), "vascular--raynauds", new Random(1), null);
        assertTrue(low.stream().noneMatch(p -> p.c().slug().equals("nutrition--fish-oil")), low.toString());
        // away rules an area out; toward re-ranks
        List<Bridges.Pair> away = Bridges.candidates(store, Bridges.Settings.defaults().with("reach", "high").with("away", "raynaud"), "nutrition--fish-oil", new Random(1), null);
        assertTrue(away.stream().noneMatch(p -> p.c().slug().equals("vascular--raynauds")));
    }

    @Test
    void proposalsAreQuestionsOnTheFrontierAcceptFilesTheRunAndTheLedgerKeepsScore(@TempDir Path home) throws Exception {
        LibraryStore store = seeded(home);
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode().put("op", "run").put("area", "nutrition--fish-oil").put("reach", "high").put("dry", true);
        a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode dry = p.bridges(a);
        assertTrue(dry.path("dry").asBoolean()); assertEquals(0, dry.path("filed").asInt());
        assertTrue(dry.path("proposals").size() >= 1, dry.toString());
        String q = dry.path("proposals").get(0).path("question").asText();
        assertTrue(q.toLowerCase().contains("fish oil") && q.toLowerCase().contains("raynaud"), q);
        assertEquals(0, Frontier.read(store).size(), "dry files nothing");
        // for real: one proposal filed as a question of type bridge, carrying its settings
        ObjectNode run = p.bridges(a.deepCopy().put("dry", false).put("propose", 1));
        assertEquals(1, run.path("filed").asInt(), run.toString());
        List<Frontier.Line> open = Bridges.open(store);
        assertEquals(1, open.size());
        assertEquals("bridge", open.get(0).type());
        assertTrue(open.get(0).kind().contains("reach=high") && open.get(0).kind().contains("a=nutrition--fish-oil") && open.get(0).kind().contains("c=vascular--raynauds"), open.get(0).kind());
        assertFalse(open.get(0).researchable(), "the explorer leaves a bridge proposal for the person");
        assertEquals(0, store.scanFindings().findings().stream().filter(f -> f.state() == Finding.State.draft).count(), "a bridge is a question, never a claim");
        // the same pair is not proposed twice: a second run files the NEXT novel pair, not Raynaud's again
        assertEquals(1, p.bridges(a.deepCopy().put("dry", false).put("propose", 1)).path("filed").asInt());
        List<Frontier.Line> open2 = Bridges.open(store);
        assertEquals(2, open2.size());
        assertEquals(1, open2.stream().filter(l -> l.kind().contains("c=vascular--raynauds")).count(), open2.toString());
        // list, accept one, dismiss the other → a research run; the ledger keeps score
        ObjectNode list = p.bridges(M.createObjectNode().put("op", "list"));
        assertEquals(2, list.path("count").asInt());
        ObjectNode acc = p.bridges(a.deepCopy().put("op", "accept").put("question", open.get(0).text()));
        assertTrue(acc.path("job_id").asText().startsWith("J-"), acc.toString());
        assertEquals(1, Bridges.open(store).size(), "accepted = explored by that run");
        p.bridges(a.deepCopy().put("op", "dismiss").put("question", Bridges.open(store).get(0).text()));
        assertEquals(0, Bridges.open(store).size());
        ObjectNode m = p.bridges(M.createObjectNode().put("op", "measure"));
        assertEquals(2, m.path("proposed").asInt()); assertEquals(1, m.path("kept").asInt()); assertEquals(1, m.path("dismissed").asInt());
        assertEquals(1, m.path("by_settings").get(0).path("kept").asInt()); assertEquals(1, m.path("by_settings").get(0).path("dismissed").asInt());
        // settings per area persist and read back
        ObjectNode set = p.bridges(a.deepCopy().put("op", "settings").put("area", "vascular--raynauds").put("reach", "low").put("strict", false).put("per_night", 1));
        assertTrue(set.path("changed").asBoolean());
        Bridges.Settings s = Bridges.settings(store, "vascular--raynauds");
        assertEquals("low", s.reach()); assertFalse(s.strict()); assertEquals(1, s.perNight());
        assertEquals("medium", Bridges.settings(store, "nutrition--fish-oil").reach(), "another area keeps the defaults");
        assertTrue(Librarian.TOOLS.contains("library_bridges"));
        assertTrue(Frontier.TYPES.contains("bridge"));
    }

    @Test
    void theGraphSensorWalksThroughConceptsTheTermsNeverShare(@TempDir Path home) throws Exception {
        LibraryStore store = seeded(home);
        // a fourth vascular area whose claims never use the words viscosity or platelet, but whose triples reach the same nodes by alias
        Files.writeString(store.subjectsFile(), Files.readString(store.subjectsFile()) + "- vascular--chilblains — chilblains\n");
        store.write(claim("vascular--chilblains", "Chilblains and thick blood", "Chilblains are worse in people whose circulation is sluggish in the cold.", "chilblains", "worsened by", "thick blood"));
        store.write(claim("vascular--chilblains", "Chilblains and clumping", "Small vessels in chilblains show clumping cells on biopsy.", "chilblains", "shows", "clumping cells"));
        Graph.alias(store, "blood viscosity", List.of("thick blood"));
        Graph.alias(store, "platelet aggregation", List.of("clumping cells"));
        // terms alone: nothing joins fish oil to chilblains
        List<Bridges.Pair> terms = Bridges.candidates(store, Bridges.Settings.defaults().with("reach", "high").with("via", "terms").with("loose", ""), "nutrition--fish-oil", new Random(1), null);
        assertTrue(terms.stream().noneMatch(p -> p.c().slug().equals("vascular--chilblains")), terms.toString());
        // the graph: two paths, fish oil → blood viscosity ← chilblains and fish oil → platelet aggregation ← chilblains
        List<Bridges.Pair> graph = Bridges.candidates(store, Bridges.Settings.defaults().with("reach", "high").with("via", "graph"), "nutrition--fish-oil", new Random(1), null);
        Bridges.Pair ch = graph.stream().filter(p -> p.c().slug().equals("vascular--chilblains")).findFirst().orElseThrow(() -> new AssertionError(graph.toString()));
        assertEquals("graph", ch.sensor());
        assertEquals(2, ch.paths().size(), ch.paths().toString());
        assertTrue(ch.paths().stream().anyMatch(x -> x.contains("blood viscosity") && x.contains("chilblains") && x.contains("fish oil")), ch.paths().toString());
        assertTrue(ch.via().contains("→blood viscosity") && ch.via().contains("→platelet aggregation"), ch.via().toString());
        assertEquals(0, ch.coMentions());
        // the model sees the paths; the fallback question names one
        String q = Bridges.question(null, ch);
        assertTrue(q.contains("The map joins them: fish oil") && q.endsWith("names the two together."), q);
        // both sensors together: Raynaud's is found by terms and by the graph
        List<Bridges.Pair> both = Bridges.candidates(store, Bridges.Settings.defaults().with("reach", "high"), "nutrition--fish-oil", new Random(1), null);
        Bridges.Pair ray = both.stream().filter(p -> p.c().slug().equals("vascular--raynauds")).findFirst().orElseThrow();
        assertEquals("both", ray.sensor(), ray.toString());
        assertTrue(ray.score() > ch.score(), "two sensors outrank one");
        // a proposal carries which sensor found it
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode().put("op", "run").put("area", "nutrition--fish-oil").put("reach", "high").put("via", "graph").put("propose", 3);
        a.putObject("patron").put("did", "person").put("name", "keeper").put("runtime", "cli");
        ObjectNode run = p.bridges(a);
        assertTrue(run.path("filed").asInt() >= 1, run.toString());
        assertTrue(Bridges.open(store).stream().allMatch(l -> l.kind().contains("sensor=graph") && l.kind().contains("via=graph")), Bridges.open(store).toString());
    }

    @Test
    void conceptEdgesLetTheGraphCrossWhereTriplesShareNothing(@TempDir Path home) throws Exception {
        LibraryStore store = seeded(home);
        // a knots area: its triples share no node with fish oil's, its words share nothing either; both claims rest on "shear stress"
        Files.writeString(store.subjectsFile(), Files.readString(store.subjectsFile()) + "- craft--knots — knots\n");
        store.write(claim("craft--knots", "Bowline holding under load", "A bowline holds a loaded loop without slipping and unties by hand afterwards.", "bowline", "holds", "loaded loop"));
        store.write(claim("craft--knots", "Rope fibre wear", "Rope fibres wear where the knot bends them sharply.", "rope fibres", "wear at", "sharp bends"));
        // the concepts crew, scripted: what each claim rests on
        Concepts.Outcome o = Concepts.fill(store, claim -> claim.contains("fish oil") || claim.contains("Fish oil") ? "shear stress\nblood flow\nomega-3 fatty acids" : claim.contains("bowline") || claim.contains("Rope") ? "- Shear stress.\n- friction\n- Fibre fatigue" : "NONE", 100);
        assertEquals(13, o.asked()); assertTrue(o.filled() >= 5, o.toString());
        assertEquals(0, Concepts.fill(store, c -> "anything", 100).asked(), "asked once; the note stops the re-ask");
        Finding fo = store.scanFindings().findings().stream().filter(f -> f.title().startsWith("Fish oil lowers")).findFirst().orElseThrow();
        assertEquals(List.of("shear stress", "blood flow", "omega-3 fatty acids"), Concepts.of(fo));
        Finding bow = store.scanFindings().findings().stream().filter(f -> f.title().startsWith("Bowline")).findFirst().orElseThrow();
        assertEquals(List.of("shear stress", "friction", "fibre fatigue"), Concepts.of(bow), "cleaned: bullets and case gone");
        assertEquals(List.of("shear stress"), Concepts.clean("Shear stress\nframework\ncriteria\napproach", bow), "a word every discipline uses is not a concept");
        assertTrue(Bridges.commonplace("analysis") && Bridges.commonplace("structures") && Bridges.commonplace("criteria") && Bridges.commonplace("because"));
        assertFalse(Bridges.commonplace("viscosity") || Bridges.commonplace("chilblains") || Bridges.commonplace("randomness"));
        // the map's own evidence of where two areas come closest: the nearest concept pairs by the embedder. The open
        // mode searches those first and shows them to the model, so the model adds to what the library measured
        // instead of nominating alone (2026-09-15: it named "sediment", "ledger", "memory" while the map held
        // "statistical inference ~ statistical test" at 0.81).
        Bridges.Area fishOil = Bridges.areas(store).stream().filter(x -> x.slug().equals("nutrition--fish-oil")).findFirst().orElseThrow();
        Bridges.Area knots = Bridges.areas(store).stream().filter(x -> x.slug().equals("craft--knots")).findFirst().orElseThrow();
        Embeddings.Embedder alike = new Embeddings.Embedder() {
            @Override public float[] embed(String text) {
                if (text.equals("blood flow") || text.equals("friction")) return new float[]{1f, 0f, 0f};
                int h = Math.floorMod(text.hashCode(), 7) + 1;
                return new float[]{0f, h, 1f / h};
            }
            @Override public String modelId() { return "fake"; }
        };
        List<Bridges.Near> near = Bridges.nearestConcepts(fishOil, knots, alike, 4);
        // "shear stress" sits on both sides (the same string embeds alike), so the planted pair is one of the two closest
        assertEquals(1.0, near.get(0).cosine(), 1e-6);
        assertTrue(near.subList(0, 2).stream().anyMatch(x -> x.a().equals("blood flow") && x.c().equals("friction")), near.toString());
        assertTrue(near.subList(0, 2).stream().anyMatch(x -> x.a().equals("shear stress") && x.c().equals("shear stress")), near.toString());
        List<String> mm = Bridges.mapMiddles(near);
        assertTrue(mm.indexOf("blood flow") < 4 && mm.indexOf("friction") < 4 && mm.indexOf("shear stress") < 4, "the map's closest pairs lead the middles, without repeats: " + mm);
        assertNull(Bridges.nearestConcepts(fishOil, knots, Embeddings.configured(), 4), "no embedder: nothing measured, nothing claimed");
        assertTrue(Bridges.mapMiddles(null).isEmpty());
        // the map has mentions edges now
        Graph g = Graph.build(store);
        assertTrue(g.edges().stream().anyMatch(e -> e.predicate().equals("mentions") && e.to().equals(g.nodeIdOf("shear stress"))), "a mentions edge");
        // a claim with no triple carries its concepts too, hung on its subject: the corrosion claim that named the
        // shared concept was exactly that one (2026-09-15)
        Finding noTriple = new Finding("F-0500-no-triple", "A dispute with no triple", List.of("craft--knots"), Finding.State.accepted,
                Finding.ClaimType.interpretation, Finding.Confidence.medium, "person", "2026-09-15T00:00:00Z", "2026-09-15",
                Finding.Volatility.stable, "", List.of(new Finding.Source("https://example.org/z", "n/a", "seed")), List.of(), null,
                "Whether the fibres fail by shear stress at all is disputed.\n", null,
                List.of(new Finding.Note(Concepts.NOTE, "crew:concepts", "2026-09-15", "shear stress; dispute")));
        store.write(noTriple);
        Graph g2 = Graph.build(store);
        assertTrue(g2.edges().stream().anyMatch(e -> e.predicate().equals("mentions") && e.from().equals("subject:craft--knots") && e.to().equals(g2.nodeIdOf("shear stress"))),
                "a claim with no triple hangs its concepts on its area");
        // and the walk reaches it: fish oil →mentions→ shear stress ←mentions← the knots area itself
        List<Bridges.Pair> viaArea = Bridges.candidates(store, Bridges.Settings.defaults().with("reach", "high").with("via", "graph").with("loose", ""), "nutrition--fish-oil", new Random(1), null);
        Bridges.Pair kn2 = viaArea.stream().filter(p -> p.c().slug().equals("craft--knots")).findFirst().orElseThrow(() -> new AssertionError(viaArea.toString()));
        assertTrue(kn2.paths().stream().anyMatch(x -> x.contains("shear stress")), kn2.paths().toString());
        // terms: nothing; graph: fish oil →mentions→ shear stress ←mentions← bowline
        List<Bridges.Pair> terms = Bridges.candidates(store, Bridges.Settings.defaults().with("reach", "high").with("via", "terms").with("loose", ""), "nutrition--fish-oil", new Random(1), null);
        assertTrue(terms.stream().noneMatch(p -> p.c().slug().equals("craft--knots")), terms.toString());
        List<Bridges.Pair> graph = Bridges.candidates(store, Bridges.Settings.defaults().with("reach", "high").with("via", "graph").with("loose", ""), "nutrition--fish-oil", new Random(1), null);
        Bridges.Pair kn = graph.stream().filter(p -> p.c().slug().equals("craft--knots")).findFirst().orElseThrow(() -> new AssertionError(graph.toString()));
        assertEquals("graph", kn.sensor());
        // strict asks the middle to be specific — a concept a tenth of the library rests on is a commonplace. In this
        // small library "shear stress" is on five claims of thirteen, so it is common HERE and strict passes it over;
        // on a real library (microbial activity, two claims of twenty-one) one such middle carries the pair.
        List<Bridges.Pair> strict = Bridges.candidates(store, Bridges.Settings.defaults().with("reach", "high").with("via", "graph"), "nutrition--fish-oil", new Random(1), null);
        assertTrue(strict.stream().noneMatch(p -> p.c().slug().equals("craft--knots")), strict.toString());
        assertTrue(kn.paths().get(0).contains("shear stress") && kn.paths().get(0).contains("mentions"), kn.paths().toString());
        assertTrue(kn.via().contains("→shear stress"), kn.via().toString());
    }

    @Test
    void anAreasNameMustBeSpecificEnoughToIdentifyIt(@TempDir Path home) throws Exception {
        LibraryStore store = seeded(home);
        // a claim whose triple subject is one ordinary word: "fish oil" identifies the area, "cold" would match any source
        store.write(claim("nutrition--fish-oil", "Fish oil and cold", "Cold changes how fish oil behaves in the blood.", "cold", "changes", "fish oil behaviour"));
        Bridges.Area a = Bridges.areas(store).stream().filter(x -> x.slug().equals("nutrition--fish-oil")).findFirst().orElseThrow();
        assertTrue(a.names().contains(Bridges.norm("fish oil")), a.names().toString());
        assertFalse(a.names().contains(Bridges.norm("cold")), "one short word is not a name for the area: " + a.names());
        // and so a source that merely says "cold" does not count as naming both areas
        RawCapture.capture(store, "https://example.org/weather", "A cold winter raises blood viscosity in the general population.", "Cold and viscosity", "test", "");
        Bridges.Area c = Bridges.areas(store).stream().filter(x -> x.slug().equals("vascular--raynauds")).findFirst().orElseThrow();
        assertEquals(0, Bridges.coMentions(a, c, store.scanFindings().findings(), Bridges.rawTexts(store)), "no source names both areas");
    }

    @Test
    void distanceSaysHowFarTwoAreasAre(@TempDir Path home) throws Exception {
        LibraryStore store = seeded(home);
        ObjectNode d = Bridges.distance(store, "nutrition--fish-oil", "vascular--raynauds");
        assertEquals(2, d.path("hops_on_map").asInt(), d.toString());
        assertFalse(d.path("siblings").asBoolean());
        assertTrue(d.path("shared_terms").toString().contains("viscosity"), d.path("shared_terms").toString());
        assertTrue(d.path("paths").size() >= 1 && d.path("paths").get(0).asText().contains("blood viscosity"), d.path("paths").toString());
        assertEquals(0, d.path("sources_naming_both").asInt());
        assertTrue(d.path("embedder").asText().startsWith("none answering"), "no embedder in tests: " + d.path("embedder").asText());
        assertTrue(d.path("summary").asText().startsWith("fish oil ↔ Raynaud's disease: 2 hop(s) on the map; joined on the map:"), d.path("summary").asText());
        ObjectNode far = Bridges.distance(store, "nutrition--fish-oil", "genealogy--census");
        assertEquals(-1, far.path("hops_on_map").asInt());
        assertTrue(far.path("summary").asText().contains("not connected on the map"), far.path("summary").asText());
        assertThrows(java.io.IOException.class, () -> Bridges.distance(store, "nutrition--fish-oil", "no-such-area"));
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode r = p.bridges(M.createObjectNode().put("op", "distance").put("area", "fish oil").put("other", "raynauds"));
        assertEquals("vascular--raynauds", r.path("c").asText(), "areas by label or slug tail");
    }

    @Test
    void theModelPhrasesAndNeverJudges(@TempDir Path home) throws Exception {
        LibraryStore store = seeded(home);
        List<Bridges.Pair> pairs = Bridges.candidates(store, Bridges.Settings.defaults().with("reach", "high"), "nutrition--fish-oil", new Random(1), null);
        Researcher.Drive asks = new ResearcherTest.ScriptedDrive() {
            @Override public String classify(com.fasterxml.jackson.databind.node.ArrayNode messages, int maxTokens) { return "\"Does the platelet effect of fish oil bear on Raynaud's disease, as blood viscosity links them?\""; }
        };
        assertEquals("Does the platelet effect of fish oil bear on Raynaud's disease, as blood viscosity links them?", Bridges.question(asks, pairs.get(0)));
        // a claim instead of a question, or a refusal, becomes the plain question: the person decides, never the model
        for (String reply : new String[]{"Fish oil cures Raynaud's disease.", "NONE", "NONE — the terms are incidental."}) {
            Researcher.Drive d = new ResearcherTest.ScriptedDrive() { @Override public String classify(com.fasterxml.jackson.databind.node.ArrayNode m, int n) { return reply; } };
            String q = Bridges.question(d, pairs.get(0));
            assertTrue(q.startsWith("Does what the library holds on fish oil bear on Raynaud's disease?"), reply + " → " + q);
        }
    }
}
