package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Suggestions (0.6.6): the shelves' events as candidates, classed now/next/hold, filed once, marked by the person, scored. */
class SuggestionsTest {
    @Test
    void theShelvesEventsBecomeSuggestionsClassedFiledOnceMarkedAndScored(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertTrue(Suggestions.text(Suggestions.refresh(store, null), Suggestions.all(store)).startsWith("Nothing to suggest right now"));
        // a forecast that came due
        String i1 = store.nextInvestigationId("Corporate history of Acme Corp.: a full dossier.");
        Investigation inv = new Investigation(i1, "Corporate history of Acme Corp.: a full dossier.", Finding.State.accepted, "patron:test", Instant.now().toString(), List.of(), List.of(),
                "## Question\n\nAcme\n\n## Answer\n\nx [1]\n\n## Forecasts\n\nF1. By 2020-01: Acme lists on an exchange. Likelihood: likely (60%). Confidence: low, because x.\nF2. By 2099-01: Acme is acquired. Likelihood: unlikely (20%). Confidence: low, because y.\n\n## References\n\n[1] https://a.example/\n");
        store.write(inv);
        Forecasts.file(store, inv, "Acme Corp", Analysis.forecasts(inv.body()));
        // a story the shelves have grown past (five claims, a story that cited none)
        for (int i = 0; i < 5; i++) { String id = store.nextFindingId("Acme Corp, 20" + (10 + i) + ": event " + i); store.write(new Finding(id, "Acme Corp, 20" + (10 + i) + ": event " + i, List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.medium, "patron:test", Instant.now().toString(), "20" + (10 + i), Finding.Volatility.stable, "", List.of(new Finding.Source("https://a.example/" + i, "n/a", "x")), List.of(), null, "Acme Corp did a thing in 20" + (10 + i) + ".\n")); }
        String s1 = store.nextInvestigationId("The story of Acme Corp");
        store.write(new Investigation(s1, "The story of Acme Corp", Finding.State.draft, "librarian:story", Instant.now().toString(), List.of(), List.of(), "# The story of Acme Corp\n\n## 2010s\n\nx\n"));
        // a question of the person's that a claim answers, and one asked twice
        LibrarianIndex index = new LibrarianIndex(store);
        String fid = store.nextFindingId("Acme Corp was founded in Needham in 1999");
        Finding answer = new Finding(fid, "Acme Corp was founded in Needham in 1999", List.of(), Finding.State.accepted, Finding.ClaimType.extraction, Finding.Confidence.high, "patron:test", Instant.now().toString(), "1999", Finding.Volatility.stable, "", List.of(new Finding.Source("https://a.example/f", "n/a", "x")), List.of(), null, "Acme Corp was founded in Needham in 1999.\n");
        store.write(answer); index.upsert(answer);
        assertTrue(Frontier.demand(store, "When and where was Acme Corp founded?", "patron:test"));
        assertTrue(Frontier.demand(store, "How does FastAPI's TestClient use an in-memory database for the Acme Corp tests?", "patron:test"), "words in common with no claim: not answered");
        assertFalse(Frontier.demand(store, "Now add a hit counter to Acme Corp: every redirect increments a count and the tests pass.", "patron:test"), "a task is not filed as a question at all");
        assertTrue(Frontier.looksLikeATask("Update the tests.") && Frontier.looksLikeATask("Please add a hit counter to the redirects") && Frontier.looksLikeATask("テストを追加してください"), "tasks");
        assertFalse(Frontier.looksLikeATask("高市早苗の生年月日は？") || Frontier.looksLikeATask("Who owns Acme?") || Frontier.looksLikeATask("zebra crossings on the moon in 1740") || Frontier.looksLikeATask("高市早苗について教えてください"), "a question, a bare topic and an ask to be told are wants");
        // a watch's arrivals this week
        Serials.add(store, "dossier-acme-corp", "Acme Corp", 7);
        java.nio.file.Files.createDirectories(Serials.arrivalsFile(store).getParent());
        java.nio.file.Files.writeString(Serials.arrivalsFile(store), "# Arrival table\n\n- " + java.time.LocalDate.now() + " [dossier-acme-corp] https://news.example/acme-round — Acme raises a round\n- 2020-01-01 [dossier-acme-corp] https://old.example/x — old\n", java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(Frontier.demand(store, "Who owns the widget patents of Acme Corp today?", "patron:test"));
        assertFalse(Frontier.demand(store, "Who owns the widget patents of Acme Corp today?", "patron:test"), "the same question again bumps the demand");
        List<Suggestions.Entry> open = Suggestions.refresh(store, null);
        List<String> kinds = open.stream().map(Suggestions.Entry::kind).toList();
        assertTrue(kinds.contains("forecast-due") && kinds.contains("story-grown") && kinds.contains("question-answered") && kinds.contains("asked-again") && kinds.contains("new-report") && kinds.contains("watched-arrival"), kinds.toString());
        assertTrue(open.stream().anyMatch(e -> e.kind().equals("watched-arrival") && e.text().startsWith("1 new page arrived for the watch on Acme Corp: Acme raises a round")), "this week's arrival, not the old one: " + open);
        assertTrue(Interview.questions(store, "Acme Corp").contains("What the library does not know about Acme Corp") && Interview.questions(store, "Acme Corp").contains("I file it as told"), Interview.questions(store, "Acme Corp"));
        assertFalse(open.stream().anyMatch(e -> e.text().contains("Acme is acquired")), "a forecast whose date has not passed is not due");
        assertFalse(open.stream().anyMatch(e -> e.text().contains("FastAPI")), "a question no claim covers is not answered: " + open);
        assertFalse(open.stream().anyMatch(e -> e.text().contains("hit counter")), "a task is not a question the person waits on: " + open);
        assertEquals(1, open.stream().filter(e -> e.kind().equals("question-answered")).count(), open.toString());
        assertEquals("now", open.get(0).klass(), "the ones for now come first: " + open);
        Suggestions.Entry due = open.stream().filter(e -> e.kind().equals("forecast-due")).findFirst().orElseThrow();
        assertTrue(due.text().contains("Forecast P-0001 came due: Acme lists on an exchange") && due.action().startsWith("researchzosho forecast P-0001") && due.pTake() == 0.9 && due.judge().equals("rules"), due.toString());
        Suggestions.Entry grown = open.stream().filter(e -> e.kind().equals("story-grown")).findFirst().orElseThrow();
        assertTrue(grown.text().matches("The story of Acme Corp is \\d+ claims behind the shelves") && grown.klass().equals("next"), grown.toString());
        int n = Suggestions.all(store).size();
        Suggestions.refresh(store, null);
        assertEquals(n, Suggestions.all(store).size(), "a second refresh files nothing new");
        // marks and the score
        assertNull(Suggestions.brier(Suggestions.all(store)));
        Suggestions.Entry taken = Suggestions.mark(store, due.id(), "taken", "scored it");
        assertEquals("taken", taken.outcome()); assertEquals("scored it", taken.note());
        Suggestions.mark(store, grown.id(), "dismissed", "");
        assertEquals(((0.9 - 1) * (0.9 - 1) + (0.6 - 0) * (0.6 - 0)) / 2, Suggestions.brier(Suggestions.all(store)), 1e-9);
        assertFalse(Suggestions.open(store).stream().anyMatch(e -> e.id().equals(due.id())), "a marked suggestion leaves the open list");
        // a suggestion whose candidate is gone is withdrawn: the asked-again question gets its run filed
        Suggestions.Entry again = open.stream().filter(e -> e.kind().equals("asked-again")).findFirst().orElseThrow();
        for (Frontier.Line l : Frontier.read(store)) if (l.text().startsWith("Who owns the widget patents")) Frontier.drop(store, l.text(), "patron:test");
        Suggestions.refresh(store, null);
        assertEquals("withdrawn", Suggestions.all(store).stream().filter(e -> e.id().equals(again.id())).findFirst().orElseThrow().outcome(), "the question is gone from the frontier, so the suggestion is withdrawn");
        assertThrows(IllegalArgumentException.class, () -> Suggestions.mark(store, due.id(), "maybe", ""));
        assertNull(Suggestions.mark(store, "S-9999", "taken", ""));
        String text = Suggestions.text(Suggestions.open(store), Suggestions.all(store));
        assertTrue(text.contains("[now] A question of yours may be answered: When and where was Acme Corp founded?") && text.contains("Brier score over your marks:"), text);
        // the protocol
        LibraryProtocol p = new LibraryProtocol(store);
        assertEquals(Suggestions.open(store).size(), p.suggest(new ObjectMapper().createObjectNode()).path("count").asInt(), "the protocol lists the open ones");
        ObjectNode m = new ObjectMapper().createObjectNode(); m.put("op", "mark"); m.put("id", open.stream().filter(e -> e.kind().equals("asked-again")).findFirst().orElseThrow().id()); m.put("outcome", "later");
        assertEquals("later", p.suggest(m).path("outcome").asText());
    }
}
