package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two linking rules with invented families ({@link FamilyLinks}). L12: a household written in characters in one source and in Latin
 * letters in another, none of it linked yet, is joined pair by pair when the two have the same shape and every name reads as its pair.
 * L1: a Western given name in brackets beside a Japanese one ("Morita, Noriko (Helen; daughter)") is a name the person carries, so the
 * same source's "Helen Morita" is the same person, and another source's is the same name.
 */
class FamilyLinksHouseholdTest {

    private static final String REGISTER = "file:///family/register-page.txt";
    private static final String BOOK = "file:///family/morita-memoir.txt";
    private static final String LETTERS = "file:///family/letters.txt";

    private static LibraryStore family(Path tmp) throws Exception {
        LibraryStore s = new LibraryStore(tmp.resolve("lib"));
        s.init();
        FamilyPeople.holdsAFamily(s);
        return s;
    }

    private static Finding claim(LibraryStore store, String s, String p, String o, String source) throws Exception { return claim(store, s, p, o, source, s + " " + p.replace('-', ' ') + " " + o + "."); }

    private static Finding claim(LibraryStore store, String s, String p, String o, String source, String quote) throws Exception {
        String sentence = s + " " + p.replace('-', ' ') + " " + o + ".";
        Finding f = new Finding(store.nextFindingId(sentence), sentence, List.of(), Finding.State.draft, Finding.ClaimType.extraction,
                Finding.Confidence.low, "family-account", Instant.now().toString(), LocalDate.now().toString(), Finding.Volatility.stable, "",
                List.of(new Finding.Source(source, "a family paper", "")), List.of(), null, sentence + "\n\nThe account says: \"" + quote + "\"\n",
                new Finding.Triple(s, p, o), List.of());
        store.write(f);
        return f;
    }

    /** A model that reads the names of the Morita household, and nothing else. */
    private static FamilyLinks.Model readsTheMoritas() {
        Map<String, String> r = Map.of("family name 森田", "もりた", "given name 健二", "けんじ", "given name 花子", "はなこ", "given name 一郎", "いちろう", "given name 修", "おさむ");
        return prompt -> {
            for (Map.Entry<String, String> e : r.entrySet()) if (prompt.contains(e.getKey())) return "{\"readings\": [\"" + e.getValue() + "\"]}";
            return "{}";
        };
    }

    private static String entry(LibraryStore store, String name) throws IOException { return FamilyPeople.view(store).nodeIdOf(name); }

    private static boolean one(LibraryStore store, String a, String b) throws IOException { return entry(store, a).equals(entry(store, b)); }

    /** A father, a mother and their son in characters in a register, and the same three in Latin letters in a memoir; nothing links any of them. */
    private static LibraryStore threeAndThree(Path tmp, String mother) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "森田一郎", "child-of", "森田健二", REGISTER);
        claim(store, "森田一郎", "child-of", "森田花子", REGISTER);
        claim(store, "Ichiro Morita", "child-of", "Kenji Morita", BOOK);
        claim(store, "Ichiro Morita", "child-of", mother, BOOK);
        return store;
    }

    // ── L12: a household across scripts ───────────────────────────────────────────────────────────────────────────────

    @Test
    void aChildAndBothParentsInCharactersAndInLatinLettersAreOneHouseholdAndEachPairIsJoined(@TempDir Path tmp) throws Exception {
        LibraryStore store = threeAndThree(tmp, "Hanako Morita");
        FamilyLinks.update(store, readsTheMoritas(), x -> { });
        assertTrue(one(store, "森田健二", "Kenji Morita"), "the father");
        assertTrue(one(store, "森田花子", "Hanako Morita"), "the mother");
        assertTrue(one(store, "森田一郎", "Ichiro Morita"), "the son");
        List<FamilyLinks.Link> l12 = FamilyLinks.current(store).links().stream().filter(l -> l.rule().equals("L12")).toList();
        assertEquals(3, l12.size(), l12.toString());
        assertTrue(l12.stream().allMatch(l -> l.grade() == FamilyLinks.Grade.probable), "probable: the readings are the model's");
        assertTrue(l12.get(0).why().contains("child of") && l12.get(0).why().contains("reads as"), l12.get(0).why());
    }

    @Test
    void aHusbandAndWifeWithTwoChildrenAreOneHouseholdToo(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "森田健二", "married-to", "森田花子", REGISTER);
        claim(store, "森田一郎", "child-of", "森田健二", REGISTER);
        claim(store, "森田修", "child-of", "森田花子", REGISTER);
        claim(store, "Kenji Morita", "married-to", "Hanako Morita", BOOK);
        claim(store, "Ichiro Morita", "child-of", "Kenji Morita", BOOK);
        claim(store, "Osamu Morita", "child-of", "Hanako Morita", BOOK);
        FamilyLinks.update(store, readsTheMoritas(), x -> { });
        assertTrue(one(store, "森田健二", "Kenji Morita") && one(store, "森田花子", "Hanako Morita"), "the couple");
        assertTrue(one(store, "森田一郎", "Ichiro Morita") && one(store, "森田修", "Osamu Morita"), "and both sons");
    }

    @Test
    void twoPeopleAndOneRelationAreNotEnough(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "森田一郎", "child-of", "森田健二", REGISTER);
        claim(store, "Ichiro Morita", "child-of", "Kenji Morita", BOOK);
        FamilyLinks.update(store, readsTheMoritas(), x -> { });
        assertFalse(one(store, "森田健二", "Kenji Morita"));
        assertFalse(one(store, "森田一郎", "Ichiro Morita"), "a father and a son whose names read alike are too common a pair to be one household");
    }

    @Test
    void aNameThatDoesNotReadAsItsPairJoinsNoneOfTheHousehold(@TempDir Path tmp) throws Exception {
        LibraryStore store = threeAndThree(tmp, "Hanae Morita");
        FamilyLinks.update(store, readsTheMoritas(), x -> { });
        assertFalse(one(store, "森田健二", "Kenji Morita"), "花子 does not read Hanae: the shape is not the same household");
        assertFalse(one(store, "森田一郎", "Ichiro Morita"));
    }

    @Test
    void aBlockOnAnyPairJoinsNoneOfTheHousehold(@TempDir Path tmp) throws Exception {
        LibraryStore store = threeAndThree(tmp, "Hanako Morita");
        claim(store, "森田花子", "born-on", "1850", REGISTER);
        claim(store, "Hanako Morita", "born-on", "1901", BOOK);
        FamilyLinks.update(store, readsTheMoritas(), x -> { });
        assertFalse(one(store, "森田花子", "Hanako Morita"), "born fifty years apart");
        assertFalse(one(store, "森田健二", "Kenji Morita"), "and the rest of the household is not joined either");
        assertFalse(one(store, "森田一郎", "Ichiro Morita"));
    }

    @Test
    void twoHouseholdsInLatinLettersThatBothFitJoinNeither(@TempDir Path tmp) throws Exception {
        LibraryStore store = threeAndThree(tmp, "Hanako Morita");
        // the letters write another household of the same names, family name first: two households in Latin letters fit the register's
        claim(store, "Morita Ichiro", "child-of", "Morita Kenji", LETTERS);
        claim(store, "Morita Ichiro", "child-of", "Morita Hanako", LETTERS);
        FamilyLinks.update(store, readsTheMoritas(), x -> { });
        assertTrue(FamilyLinks.current(store).links().stream().noneMatch(l -> l.rule().equals("L12")), "the register's household fits two: it is joined to neither " + FamilyLinks.current(store).links().stream().map(FamilyLinks::sentence).toList());
    }

    // ── L1: a Western given name in brackets ──────────────────────────────────────────────────────────────────────────

    @Test
    void theBracketsReadAWesternGivenNameBesideAJapaneseOneAndNotARomanisedOne() {
        assertEquals(List.of("Noriko", "Helen"), List.of(FamilyNameHistory.westernInBrackets("Morita, Noriko (Helen; daughter), 1931-2010").get(0)));
        assertEquals(List.of("Noriko", "Helen"), List.of(FamilyNameHistory.westernInBrackets("Noriko (Helen) Morita came to Leeds in 1956.").get(0)));
        assertTrue(FamilyNameHistory.westernInBrackets("Morita, Kenji (Kenzo), the elder son").isEmpty(), "a romanised Japanese name is not a Western one");
    }

    @Test
    void aWesternNameInAnIndexsBracketsMakesTheSameSourcesNameOfItThePerson(@TempDir Path tmp) throws Exception {
        LibraryStore store = family(tmp);
        claim(store, "Noriko Morita", "child-of", "Kenji Morita", BOOK, "Morita, Noriko (Helen; daughter of Kenji), 12, 45, 88");
        claim(store, "Helen Morita", "occupation", "nurse", BOOK, "Helen Morita trained as a nurse in Leeds.");
        FamilyLinks.update(store);
        assertTrue(one(store, "Noriko Morita", "Helen Morita"), "the index gives her both names, and the memoir writes one of them elsewhere");
        FamilyLinks.Link l = FamilyLinks.current(store).links().stream().filter(x -> x.rule().equals("L1") && x.why().contains("Helen Morita")).findFirst().orElseThrow();
        assertEquals(FamilyLinks.Grade.probable, l.grade(), "one source names one person so: " + l.why());
    }

    @Test
    void theWesternNameBesideTwoPeopleOrFromAnotherSourceJoinsNobody(@TempDir Path tmp) throws Exception {
        LibraryStore two = family(tmp.resolve("two"));
        claim(two, "Noriko Morita", "child-of", "Kenji Morita", BOOK, "Morita, Noriko (Helen; daughter of Kenji), 12, 45");
        claim(two, "Emiko Morita", "married-to", "Kenji Morita", BOOK, "Morita, Emiko (Helen; wife of Kenji), 9");
        claim(two, "Helen Morita", "occupation", "nurse", BOOK, "Helen Morita trained as a nurse in Leeds.");
        FamilyLinks.update(two);
        assertFalse(one(two, "Noriko Morita", "Helen Morita"), "the book writes Helen beside two people's names: it says neither is Helen Morita");
        assertFalse(one(two, "Emiko Morita", "Helen Morita"));

        LibraryStore other = family(tmp.resolve("other"));
        claim(other, "Noriko Morita", "child-of", "Kenji Morita", BOOK, "Morita, Noriko (Helen; daughter of Kenji), 12, 45");
        claim(other, "Helen Morita", "occupation", "nurse", LETTERS, "Helen Morita trained as a nurse in Leeds.");
        FamilyLinks.update(other);
        assertFalse(one(other, "Noriko Morita", "Helen Morita"), "another source's Helen Morita is only the same name, with nothing else known to agree");
        assertTrue(FamilyLinks.current(other).links().stream().anyMatch(x -> x.rule().equals("L1") && x.grade() == FamilyLinks.Grade.possible && x.why().contains("Helen Morita")),
                "so it is shown as a possible link: " + FamilyLinks.current(other).links().stream().map(FamilyLinks::sentence).toList());
    }
}
