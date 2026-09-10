package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A report's leftovers are filed once and parked; the list carries the facets a person sorts a long queue by. */
class FrontierFiltersTest {

    static final ObjectMapper M = new ObjectMapper();
    static ObjectNode args(String json) throws Exception { return (ObjectNode) M.readTree(json); }

    static List<Frontier.Line> open(LibraryStore store) throws Exception {
        List<Frontier.Line> out = new ArrayList<>();
        for (Frontier.Line l : Frontier.read(store)) if (l.open()) out.add(l);
        return out;
    }

    static Finding finding(String id, String title, Finding.State state, String body, String... subjects) {
        return new Finding(id, title, List.of(subjects), state, Finding.ClaimType.extraction, Finding.Confidence.medium, "crew:explorer",
                "2026-09-01T10:00:00Z", "2026-09-01", Finding.Volatility.stable, "",
                List.of(new Finding.Source("https://example.org/p", "n/a", "src")), List.of(), null, body);
    }

    @Test
    void aReportsLeftoversAreFiledOnceAndParked(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        List<String> left = List.of("What is the role of the KL term in the loss? [Machine Learning Researcher]", "Short? no", "Does sampling mimic neural population codes during perceptual decisions? [Neuroscientist]");
        assertEquals(2, Frontier.fromReport(store, "I-0016-vae", left), "one was too short to file");
        // the run filed them; the review files the same list again — a no-op; a cut-short copy of one is not a new question either
        assertEquals(0, Frontier.fromReport(store, "I-0016-vae", left));
        assertEquals(0, Frontier.fromReport(store, "I-0016-vae", List.of("What is the role of the KL term in the loss? [Machine Learning…")));
        List<Frontier.Line> lines = open(store);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).parked() && lines.get(0).type().equals("report") && "I-0016-vae".equals(lines.get(0).origin()), lines.get(0).toString());
        assertFalse(lines.get(0).researchable(), "parked: the explorer leaves it until a person unparks it");
        // a different report may leave the same words: that is its own line
        assertEquals(1, Frontier.fromReport(store, "I-0017-other", List.of("What is the role of the KL term in the loss? [Machine Learning Researcher]")));
        // the switch: RESEARCHZOSHO_REPORT_QUESTIONS=queued puts them straight in the queue
        org.researchzosho.Config.set("RESEARCHZOSHO_REPORT_QUESTIONS", "queued");
        try {
            Frontier.fromReport(store, "I-0018-x", List.of("Where were the night scenes shot, beyond Akasaka?"));
            Frontier.Line q = open(store).get(3);
            assertFalse(q.parked()); assertTrue(q.researchable());
        } finally { org.researchzosho.Config.set("RESEARCHZOSHO_REPORT_QUESTIONS", ""); }
    }

    @Test
    void tidyRemovesTheSecondCopies(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Files.createDirectories(store.frontierFile().getParent());
        Files.writeString(store.frontierFile(), String.join("\n",
                "# Frontier",
                "- 2026-09-08 [report] What are the computational trade-offs of training a VAE compared to a GAN for large-scale datasets, in… (left open by I-0016-vae)",
                "- 2026-09-08 [report] Does generating new samples imply creativity? [Philosopher] (left open by I-0016-vae)",
                "- 2026-09-09 [report] What are the computational trade-offs of training a VAE compared to a GAN for large-scale datasets, in practice? [Data Engineer] (from I-0016-vae)",
                "- 2026-09-09 [report] Does generating new samples imply creativity? [Philosopher] (from I-0016-vae)",
                "- 2026-09-09 [report] Does generating new samples imply creativity? [Philosopher] (from I-0099-another) ⇒ explored 2026-09-09 I-0100",
                "- 2026-09-09 [person] Who cut the gears?",
                "- 2026-09-09 [person] who cut the gears?",
                "- 2026-09-09 [person] Who cut the gears?") + "\n");
        assertEquals(4, Frontier.duplicates(store).size());
        assertEquals(4, Frontier.tidy(store), "an identical line twice over is two copies to remove");
        List<String> texts = open(store).stream().map(Frontier.Line::text).toList();
        assertEquals(3, texts.size(), texts.toString());
        assertTrue(texts.get(0).startsWith("Does generating") && texts.get(2).equals("Who cut the gears?"), texts.toString());
        assertTrue(texts.get(1).contains("in practice? [Data Engineer]"), "the full text wins over the cut-short copy: " + texts.get(1));
        assertEquals(0, Frontier.tidy(store), "nothing left to remove");
        assertTrue(Files.readString(store.frontierFile()).contains("⇒ explored 2026-09-09 I-0100"), "closed lines are untouched");
    }

    @Test
    void perspectiveLanguageAndAlikeQuestions() {
        assertEquals("Historian of Science", Frontier.perspective("How did 2013 shift the field? [Historian of Science] (left open by I-0016-vae)"));
        assertNull(Frontier.perspective("How did 2013 shift the field? (left open by I-0016-vae)"));
        assertEquals("How did 2013 shift the field?", Frontier.bare("How did 2013 shift the field? [Historian of Science] (left open by I-0016-vae)"));
        assertEquals("english", Frontier.language("When was lead paint banned?"));
        assertEquals("japanese", Frontier.language("東京の撮影現場はどこでしたか？"));
        assertEquals("japanese", Frontier.language("In Japanese-language sources: what was it like on the set? — what do sources written in Japanese say?"));
        assertEquals("korean", Frontier.language("한국어 자료에서는 무엇이라고 하는가"));
        assertEquals("french", Frontier.language("In French-language sources: AFP fact-checking practice"));
        List<Frontier.Line> lines = List.of(
                new Frontier.Line("2026-09-09", "report", "What evidence did the CPSC cite to justify the 1978 lead paint ban? [Public Health Epidemiologist] (left open by I-0025-x)", null),
                new Frontier.Line("2026-09-09", "report", "Which epidemiological evidence did the CPSC cite to justify the 1978 lead paint ban as a public health intervention? [Epidemiologist] (from I-0025-x)", null),
                new Frontier.Line("2026-09-09", "person", "Where were Tokyo Vice's night scenes shot?", null));
        Map<String, List<Frontier.Line>> alike = Frontier.similar(lines);
        assertEquals(1, alike.size());
        assertEquals(2, alike.get(lines.get(0).text()).size(), "the two CPSC questions read alike; Tokyo Vice stands alone");
    }

    @Test
    void theListCarriesTheFacetsAndTheFiltersUseThem(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Files.writeString(store.subjectsFile(), "# Subjects\n\n- lead-paint — lead-based paint and its regulation | also: lead-based paint\n- vae — variational autoencoders | also: VAE, variational autoencoder\n");
        store.write(finding("F-0001-ban", "Lead paint was banned in 1978", Finding.State.accepted, "The CPSC banned lead-based paint for residential use in 1978, at 0.06 % lead by weight. https://example.org/p\n", "lead-paint"));
        store.write(finding("F-0002-kl", "The KL term regularises the latent space", Finding.State.draft, "The KL divergence term keeps the posterior near the prior. https://example.org/p\n", "vae"));
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        store.write(new Investigation("I-0025-lead", "when and why was lead paint banned", Finding.State.accepted, "crew:explorer", "2026-09-09T00:00:00Z", List.of("F-0001-ban"), List.of(), ""));
        store.write(new Investigation("I-0016-vae", "what is a variational autoencoder", Finding.State.accepted, "crew:explorer", "2026-09-09T00:00:00Z", List.of("F-0002-kl"), List.of(), ""));
        Frontier.fromReport(store, "I-0025-lead", List.of("What year was lead paint banned for residential use, and at what lead content? [Regulatory Compliance Specialist]", "Did the ban reduce childhood lead poisoning? [Public Health Epidemiologist]"));
        Frontier.fromReport(store, "I-0016-vae", List.of("Does generating new samples imply creativity? [Philosopher of Mind]"));
        store.frontier("person", "日本語の資料では東京の撮影について何と言っていますか");
        LibraryProtocol p = new LibraryProtocol(store);
        List<ObjectNode> all = p.frontierList(true);
        assertEquals(4, all.size());
        ObjectNode ban = all.get(0);
        assertEquals("I-0025-lead", ban.path("report").asText());
        assertEquals("when and why was lead paint banned", ban.path("report_title").asText());
        assertEquals("kept", ban.path("report_fate").asText(), "a claim of that report was accepted");
        assertEquals("waiting", all.get(2).path("report_fate").asText(), "that report's claim is still a draft in the inbox");
        assertEquals("Regulatory Compliance Specialist", ban.path("perspective").asText());
        assertEquals("lead-paint", ban.path("subjects").get(0).asText(), "the report's claims carry the subject");
        assertEquals("english", ban.path("language").asText());
        assertEquals("japanese", all.get(3).path("language").asText());
        assertTrue(ban.hasNonNull("answered") && ban.path("answered").path("id").asText().equals("F-0001-ban"), "the shelves already answer it: " + ban);
        assertFalse(all.get(3).hasNonNull("answered"));
        assertTrue(all.get(0).path("parked").asBoolean() && !all.get(3).path("parked").asBoolean());
        // the filters, as the page, the CLI and library_frontier apply them
        assertTrue(LibraryProtocol.matches(ban, Map.of("show", "all", "report", "I-0025")));
        assertFalse(LibraryProtocol.matches(ban, Map.of("show", "queued")), "parked lines are not in the queue view");
        assertTrue(LibraryProtocol.matches(ban, Map.of("show", "parked", "fate", "kept", "who", "regulatory", "subject", "lead-paint", "language", "english", "q", "lead banned")));
        assertFalse(LibraryProtocol.matches(ban, Map.of("show", "all", "q", "lead tokyo")), "every word must appear");
        var listed = p.frontier(args("{\"op\":\"list\",\"fate\":\"waiting\"}"));
        assertEquals(1, listed.get("questions").size());
        assertEquals("Philosopher of Mind", listed.get("questions").get(0).path("perspective").asText());
        assertEquals(2, p.frontier(args("{\"op\":\"list\",\"report\":\"I-0025\"}")).get("questions").size(), "list shows parked lines too by default");
        assertEquals(1, p.frontier(args("{\"op\":\"list\",\"show\":\"queued\"}")).get("questions").size());
        Patrons.set(store, "did:key:zA", "A", Patrons.Level.write);
        assertEquals(0, p.frontier(args("{\"op\":\"tidy\",\"patron\":{\"did\":\"did:key:zA\"}}")).path("removed").asInt());
    }
}
