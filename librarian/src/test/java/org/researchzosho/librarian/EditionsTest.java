package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The heads of real captures of 2026-10-15: the dates the library reads from them, and which pairs it takes for editions of one document. */
class EditionsTest {

    static final String H2023 = "Armored Combat Marshal’s Handbook October 2023 Copyright © 1985, 1989, 1992, 1994, 2000, 2002, 2006, 2007, 2008, 2012, 2013, 2015, 2018, 2020, 2021, 2022, 2023 by The Society for Creative Anachronism, Inc. All Rights Reserved. This handbook is an official corporate publication of the Society for Creative Anachronism, Inc., a nonprofit organization dedicated to researching and recreating the customs, combat and courtesy of the Middle Ages. Reproduction for non-profit purposes is permitted; any other use without written permission is expressly forbidden. 0 Revision History Summary of Changes from Previous Version (June 2022) Page Paragraph Number Summary of Change Date Approved 12 Added language 15 October 2023";
    static final String H2026 = "The Society for Creative Anachronism Inc. Armored Combat - Rattan Handbook Approved 23 June 2026 Effective 22 September 2026 Copyright Copyright © 1985, 1989, 1992, 1994, 2000, 2002, 2006, 2007, 2008, 2012, 2013, 2015, 2018, 2020, 2021, 2022, 2023, 2025, 2026 by the Society for Creative Anachronism, Inc, all rights reserved. This handbook is an official corporate publication of the Society for Creative Anachronism, Inc.";
    static final String H2025 = "The Society for Creative Anachronism Inc. Armored Combat - Rattan Handbook June 2025 2 PDF generated: July 16, 2025 Copyright Copyright © 1985, 1989, 2025 by the Society for Creative Anachronism, Inc, all rights reserved. This handbook is an official corporate publication of the Society for Creative Anachronism, Inc., a non-profit organization.";
    static final String DRACHENWALD = "Drachenwald Armored Combat Rules December, 2022 Based upon SCA Marshal’s Handbook, June 2022 December 2022 Page 1 Approved on the 1st day of December, 2022. Kingdom Earl Marshal of Drachenwald King of Drachenwald Queen of Drachenwald Copyright © 1985, 2021 by The Society for Creative Anachronism, Inc. and the Kingdom of Drachenwald.";
    static final String REBATED = "Armoured Combat with Rebated Blades (Armoured Steel Combat) Marshal’s Handbook July 2021 Armoured Combat with Rebated Blades (Armoured Steel Combat), Marshal’s Handbook July 2021 © 2021 Society for Creative Anachronism, Inc. All Rights Reserved/ This handbook is an official corporate publication of the Society for Creative Anachronism, Inc.";
    static final String EK = "Policies of the Heavy Armor Marshal of the East Kingdom Tournament and Melee Combat Updated by Count Wilhelm von Ostenbruke 2024 Policies of the Heavy Armor Marshal of the East Kingdom Contents Part I. General Policies Part II. Rules of the Lists of the SCA, Inc.";
    static final String NEWCOMERS = "A Newcomers Guide to the SCA April 2021 Contents Known World Map About the SCA Geography Getting Started Persona and History Combat and Martial Arts Copyright © 2021 Society for Creative Anachronism, Inc.";

    @Test
    void theDateComesFromTheHeadWithAnEditionWordPreferred() {
        assertEquals("22 September 2026 (effective)", Editions.dateInHead(H2026).text(), "the latest date after an edition word");
        assertEquals(LocalDate.of(2026, 9, 22), Editions.dateInHead(H2026).date());
        assertEquals("October 2023", Editions.dateInHead(H2023).text(), "the title block's date, not the revision history's \"Previous Version (June 2022)\" (run E of 2026-10-15 took that one) nor a change's approval day");
        assertFalse(Editions.dateInHead(H2023).dayKnown());
        assertEquals("June 2025", Editions.dateInHead(H2025).text(), "the edition's month, not the day the PDF was generated");
        assertEquals("1 December 2022 (approved)", Editions.dateInHead(DRACHENWALD).text());
        assertEquals("July 2021", Editions.dateInHead(REBATED).text());
        assertNull(Editions.dateInHead("No date here at all, only the year 1066 and the number 2000 pages."), "a year alone is not a date");
        assertNull(Editions.dateInHead(""));
    }

    @Test
    void theTitleIsWhatComesBeforeTheDateWithThePublisherTakenOut() {
        assertEquals(List.of("armored", "combat", "rattan", "handbook"), List.copyOf(Editions.describe("u", H2026).titleWords()), "the publisher's name, which the text repeats, is not the title");
        assertTrue(Editions.describe("u", H2023).titleWords().containsAll(List.of("armored", "combat", "handbook")));
        assertEquals("handbook", Editions.describe("u", H2026).kind()); assertEquals("rules", Editions.describe("u", DRACHENWALD).kind()); assertEquals("policies", Editions.describe("u", EK).kind());
    }

    @Test
    void editionsOfOneDocumentAreTakenTogetherAndOtherDocumentsOfTheSameKindAreNot() {
        Editions.Doc d2023 = Editions.describe("2023", H2023), d2025 = Editions.describe("2025", H2025), d2026 = Editions.describe("2026", H2026);
        Editions.Doc drachenwald = Editions.describe("dw", DRACHENWALD), rebated = Editions.describe("rb", REBATED), ek = Editions.describe("ek", EK), newcomers = Editions.describe("nc", NEWCOMERS);
        assertTrue(Editions.editions(d2023, d2026), "the retitled 2023 handbook and the 2026 one");
        assertTrue(Editions.editions(d2025, d2026), "the 2025 and 2026 handbooks");
        assertTrue(Editions.editions(d2023, d2025));
        assertFalse(Editions.editions(drachenwald, d2026), "a kingdom's rules are not an edition of the society's handbook: another kind of document");
        assertFalse(Editions.editions(rebated, d2023), "the rebated-blades handbook is its own document: four words of its own");
        assertFalse(Editions.editions(rebated, d2026));
        assertFalse(Editions.editions(ek, d2026)); assertFalse(Editions.editions(newcomers, d2026));
        assertFalse(Editions.editions(d2026, d2026), "the same date is not two editions");
    }

    @Test
    void theWriterAndTheReferencesAreToldWhichIsNewer(@TempDir Path dir) throws Exception {
        LibraryStore store = new LibraryStore(dir);
        RawCapture.capture(store, "https://a.example/2023.pdf", H2023, "Microsoft Word - old", "test", null, "");
        RawCapture.capture(store, "https://a.example/2026.pdf", H2026, "The Society for Creative", "test", null, "");
        RawCapture.capture(store, "https://dw.example/rules.pdf", DRACHENWALD, "A&C DW Rules", "test", null, "");
        String block = Editions.block(store, List.of("https://a.example/2023.pdf", "https://a.example/2026.pdf", "https://dw.example/rules.pdf", "https://none.example/missing"));
        assertTrue(block.startsWith("DATED SOURCES"), block);
        assertTrue(block.contains("https://a.example/2023.pdf — October 2023") && block.contains("https://a.example/2026.pdf — 22 September 2026 (effective)"), block);
        assertTrue(block.contains("https://a.example/2023.pdf (October 2023) looks like an earlier edition of https://a.example/2026.pdf (22 September 2026 (effective))"), block);
        assertFalse(block.contains("dw.example/rules.pdf (1 December 2022 (approved)) looks like"), "the kingdom's rules are not paired: " + block);
        Map<String, Editions.Doc> docs = Editions.describe(store, List.of("https://a.example/2023.pdf", "https://a.example/2026.pdf"));
        assertEquals(Map.of("https://a.example/2023.pdf", "[2]"), Editions.olderEditions(docs, Map.of("https://a.example/2023.pdf", 1, "https://a.example/2026.pdf", 2)));
        assertEquals("", Editions.block(store, List.of("https://none.example/missing")), "nothing dated, nothing said");
    }
}
