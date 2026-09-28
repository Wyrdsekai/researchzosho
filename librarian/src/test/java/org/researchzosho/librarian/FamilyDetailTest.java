package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A claim's reading, kept as one note beside its words: it reads back as written, the newest note counts, and a key nobody knows is kept. */
class FamilyDetailTest {

    private static Finding claim(List<Finding.Note> notes) {
        return new Finding("F-0001-kenji-has-name", "森田健二 was named 遠藤健二 at birth (1905).", List.of(), Finding.State.draft, Finding.ClaimType.extraction, Finding.Confidence.low,
                "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "", List.of(new Finding.Source("file:///family/book.txt", "as told by an aunt", "the family's own account")),
                List.of(), null, "森田健二 was named 遠藤健二 at birth (1905).\n", new Finding.Triple("森田健二", "has-name", "name: 遠藤健二"), notes);
    }

    @Test
    void aValueWithASemicolonAnEqualsSignOrAPercentReadsBackAsWritten() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("family", "遠藤");
        d.put("said", "entered; as 婿養子 = adopted and married, 100% so");
        d.put("forms", "遠藤健二@ja-Hani,Endō Kenji@ja-Latn");
        d.put("event", "");
        d.put("odd", "a%3Bb");
        Finding f = claim(List.of(FamilyDetail.note(d, "family-account")));
        Finding back = Finding.parse(f.format());
        assertEquals(d, FamilyDetail.of(back), "the note reads back key for key, in its order, through the claim's file: " + back.notes());
        assertEquals("", FamilyDetail.get(back, "to"), "a key it does not have is empty");
        assertEquals(f.contentHash(), back.contentHash());
        assertEquals(claim(List.of()).contentHash(), f.contentHash(), "the reading is outside what a person approves, as the triple is");
    }

    @Test
    void theNewestNoteCountsAndAKeyNobodyKnowsIsKept() {
        Finding f = claim(List.of(FamilyDetail.note(Map.of("kind", "unknown"), "family-account"),
                new Finding.Note("source-added", "family-account", "2026-09-24", "also said by tree.ged"),
                FamilyDetail.note(new LinkedHashMap<>(Map.of("kind", "mukoyoshi", "house-number", "3")), "person")));
        Map<String, String> d = FamilyDetail.of(f);
        assertEquals("mukoyoshi", d.get("kind"), "the newest reading counts");
        assertEquals("3", d.get("house-number"), "a key a later version wrote is kept");
        assertTrue(FamilyDetail.of(claim(List.of())).isEmpty());
        assertEquals(Map.of("how", "birth"), FamilyDetail.parse("how=birth; broken; =nothing"), "a part without a key is left out");
    }
}
