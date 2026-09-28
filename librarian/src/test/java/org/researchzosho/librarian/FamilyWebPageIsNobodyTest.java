package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A web page is nobody's "I": its title and host, with the owner's note beside its address, are where its facts come from, never a person
 * in them. A fact the reading gives the page itself is the person the title names, when the page writes that name; else it is left out.
 */
class FamilyWebPageIsNobodyTest {

    static final String PAGE = "Tom Hale - Wikipedia (en.wikipedia.org)";
    static final String TEXT = "Tom Hale (1880–1950) was a teacher in Leeds. He married Ann Ellis in 1905. Their son Ned Hale was born in 1907.";

    private static FamilyAccount.Read read(String page, String text, String answer, List<String> prompts) {
        return FamilyAccount.read(text, "the writer of the page " + page, new GenealogyProfile().predicates(), p -> { prompts.add(p); return answer; },
                List.of(), null, null, FamilyAccount.Voice.page(page));
    }

    @Test
    void thePageIsTheSourceAndAFactOfThePageIsThePersonItsTitleNames() {
        List<String> prompts = new ArrayList<>();
        FamilyAccount.Read r = read(PAGE, TEXT, """
                {"people": [{"name": "Tom Hale - Wikipedia (en.wikipedia.org) — the person who keeps this library notes: \\"my great grandfather\\"", "sex": "male"}], "facts": [
                 {"subject": "Tom Hale - Wikipedia (en.wikipedia.org) — the person who keeps this library notes: \\"my great grandfather\\"", "relation": "married-to", "object": "Ann Ellis", "date": "1905", "quote": "He married Ann Ellis in 1905."},
                 {"subject": "Ned Hale", "relation": "child-of", "object": "Tom Hale - Wikipedia", "date": "", "quote": "Their son Ned Hale was born in 1907."},
                 {"subject": "Tom Hale", "relation": "occupation", "object": "teacher", "date": "", "quote": "Tom Hale (1880–1950) was a teacher in Leeds."}]}""", prompts);
        assertTrue(prompts.get(0).startsWith("Below is part of a web page, " + PAGE + ". List the people in it"), prompts.get(0).substring(0, 200));
        assertTrue(prompts.get(0).contains("the person is the writer of the page " + PAGE), "the page's I is whoever wrote the page");
        List<String> triples = r.facts().stream().map(f -> f.subject() + " | " + f.relation() + " | " + f.object()).toList();
        assertTrue(triples.contains("Tom Hale | married-to | Ann Ellis"), triples.toString());
        assertTrue(triples.contains("Ned Hale | child-of | Tom Hale"), triples.toString());
        assertTrue(r.people().stream().noneMatch(p -> p.name().contains("Wikipedia")), r.people().toString());
        assertTrue(triples.stream().noneMatch(t -> t.contains("Wikipedia") || t.contains("the person who keeps")), triples.toString());
    }

    @Test
    void aPageWhoseTitleNamesNoPersonGivesNoPersonForItself() {
        String page = "The Hale family history - Leeds Society (leeds.example.org)";
        FamilyAccount.Read r = read(page, "The Hale family came to Leeds in 1850. Tom Hale was a teacher.", """
                {"people": [], "facts": [
                 {"subject": "The Hale family history - Leeds Society (leeds.example.org)", "relation": "lived-in", "object": "Leeds", "date": "1850", "quote": "The Hale family came to Leeds in 1850."},
                 {"subject": "Tom Hale", "relation": "occupation", "object": "teacher", "date": "", "quote": "Tom Hale was a teacher."}]}""", new ArrayList<>());
        List<String> triples = r.facts().stream().map(f -> f.subject() + " | " + f.relation() + " | " + f.object()).toList();
        assertEquals(List.of("Tom Hale | occupation | teacher"), triples);
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("makes the page itself a person")), r.dropped().toString());
        assertFalse(FamilyAccount.personLike("The Hale family history"));
        assertTrue(FamilyAccount.personLike("森田健二") && FamilyAccount.personLike("Tom Hale"));
    }

    @Test
    void aFamilysOwnAccountIsReadAsBefore() {
        String p = FamilyAccount.prompt("text", "Kimie Hale", new GenealogyProfile().predicates());
        assertTrue(p.startsWith("Below is part of a family's own account, told by Kimie Hale."), p.substring(0, 120));
        assertEquals(p, FamilyAccount.prompt("text", "Kimie Hale", new GenealogyProfile().predicates(), ""));
    }
}
