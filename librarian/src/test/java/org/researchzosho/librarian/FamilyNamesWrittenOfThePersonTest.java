package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A name is the person's only when the words speak of the person: a sentence about somebody else in which a name stands gives that name to
 * nobody but its own subject; another spelling the model gave a person that is another person of the read, or a name in Latin letters that
 * shares no given name with the person's and stands nowhere in brackets beside it, is not the person's either.
 */
class FamilyNamesWrittenOfThePersonTest {

    private static FamilyAccount.Read read(String text, String teller, String answer) {
        return FamilyAccount.read(text, teller, new GenealogyProfile().predicates(), p -> p.contains("Answer with one word") ? "no" : answer);
    }

    private static String name(String person, String name, String quote) {
        return "{\"person\": \"" + person + "\", \"name\": \"" + name + "\", \"kind\": \"unknown\", \"quote\": \"" + quote + "\"}";
    }

    @Test
    void aNameInASentenceAboutSomebodyElseIsNotThePersons() {
        String text = "For Ellis, and probably for many other Leeds managers who employed Japanese nationals, having loyal staff was a source of pride. "
                + "Nora Lindqvist, Ken Ellis's daughter, recalls how her father lent money to Tom Hale. Dilys, unlike the Hale children, seems to have had many contacts. "
                + "Although John had excelled in sports, life in secondary school, which began for John just before the war, held the same prejudices. "
                + "Kimie Hale's given name is Kimie, but she was known as Kay from then on. My Christian name is Kay.";
        FamilyAccount.Read r = read(text, "Kimie Hale", "{\"people\": [{\"name\": \"Kimie Hale\", \"sex\": \"female\"}, {\"name\": \"Ken Ellis\"}, {\"name\": \"Tom Hale\"}], \"facts\": [], \"names\": ["
                + name("Kimie Hale", "Ellis", "For Ellis, and probably for many other Leeds managers who employed Japanese nationals, having loyal staff was a source of pride.") + ", "
                + name("Kimie Hale", "Nora Lindqvist", "Nora Lindqvist, Ken Ellis's daughter, recalls how her father lent money to Tom Hale.") + ", "
                + name("Kimie Hale", "Dilys", "Dilys, unlike the Hale children, seems to have had many contacts.") + ", "
                + name("Kimie Hale", "John", "Although John had excelled in sports, life in secondary school, which began for John just before the war, held the same prejudices.") + ", "
                + name("Kimie Hale", "Kay", "Kimie Hale's given name is Kimie, but she was known as Kay from then on.") + ", "
                + name("Kimie Hale", "Kay", "My Christian name is Kay.") + "]}");
        assertEquals(List.of("Kimie Hale = Kay"), r.names().stream().map(n -> n.person() + " = " + n.name()).toList(), "the two Kay claims are one name; the rest speak of other people");
        assertEquals(3, r.dropped().stream().filter(d -> d.contains("do not write Kimie Hale, and no word in them stands for Kimie Hale")).count(), "Ellis, Dilys, John: " + r.dropped());
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("Nora Lindqvist of Kimie Hale") && d.contains("Ken Ellis's daughter")), "Nora is Ken's daughter, filed so: " + r.dropped());
        FamilyAccount.Read she = read("Kimie Hale kept the books. She was known as Kay.", "an aunt", "{\"people\": [{\"name\": \"Kimie Hale\"}], \"facts\": [], \"names\": [" + name("Kimie Hale", "Kay", "She was known as Kay.") + "]}");
        assertEquals(1, she.names().size(), "she, with nobody else named before it, stands for the person: " + she.dropped());
    }

    @Test
    void anotherSpellingThatIsSomebodyElseOrSharesNoGivenNameIsLeftOut() {
        String text = "THE HALE FARM. A Memoir. Amos Takeshi Hale; introduced and edited by Tai D. Lindqvist. I am Takeshi Hale (Amos Hale). "
                + "The baptism of Silas Takeo Hale, my son, took place in 1920. Rev. Hale preached at Leeds. The Rev. A. Hale kept the farm.";
        FamilyAccount.Read r = read(text, "Amos Takeshi Hale", "{\"people\": [{\"name\": \"Amos Takeshi Hale\", \"also\": [\"Tai D. Lindqvist\", \"Silas Takeo Hale\", \"Amos Hale\", \"A. Hale\", \"Takeshi Hale\", \"Rev. Hale\"], \"family\": \"Hale\", \"given\": \"Amos Takeshi\", \"sex\": \"male\"}, {\"name\": \"Silas Takeo Hale\", \"sex\": \"male\"}], \"facts\": ["
                + "{\"subject\": \"Amos Takeshi Hale\", \"relation\": \"parent-of\", \"object\": \"Silas Takeo Hale\", \"date\": \"1920\", \"quote\": \"The baptism of Silas Takeo Hale, my son, took place in 1920.\"}]}");
        FamilyAccount.Person amos = r.people().stream().filter(p -> p.name().equals("Amos Takeshi Hale")).findFirst().orElseThrow();
        assertEquals(List.of("Amos Hale", "A. Hale", "Takeshi Hale"), amos.also(), "the editor and the son are other people; the initial and the given names are his; the title with the family name alone is nobody's spelling");
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("\"Tai D. Lindqvist\" was not kept as another spelling") && d.contains("share no given name")), r.dropped().toString());
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("\"Silas Takeo Hale\" was not kept as another spelling")), r.dropped().toString());
        assertTrue(GenealogyProfile.droppedSaid(r.dropped()).contains("did not give"));
        // a spelling in another script, or written in brackets beside the name, stays
        FamilyAccount.Read paired = read("キャロライン・ヒッチ (Caroline Hitch) came to Sendai. Tom Hale (Tommy) kept a shop.", "an aunt",
                "{\"people\": [{\"name\": \"キャロライン・ヒッチ\", \"also\": [\"Caroline Hitch\"]}, {\"name\": \"Tom Hale\", \"also\": [\"Tommy\"]}], \"facts\": []}");
        assertEquals(List.of("Caroline Hitch"), paired.people().stream().filter(p -> p.name().equals("キャロライン・ヒッチ")).findFirst().orElseThrow().also());
        assertEquals(List.of("Tommy"), paired.people().stream().filter(p -> p.name().equals("Tom Hale")).findFirst().orElseThrow().also());
    }
}
