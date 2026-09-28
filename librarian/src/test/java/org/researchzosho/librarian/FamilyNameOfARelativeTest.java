package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.profiles.GenealogyProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A name whose words give it to a relative: "her husband Tom Hart", 妻ハル, "Tom Hart, her brother" name the relative, not the person the
 * model gave the name to. The name comes off the person, the relation is filed where the words say whose relative it is, and the read says
 * so. Words with no such relation word ("she was known as Ann Hart") leave the name where the model put it.
 */
class FamilyNameOfARelativeTest {

    private static FamilyAccount.Read read(String text, String teller, String answer) { return read(text, teller, p -> answer); }

    private static FamilyAccount.Read read(String text, String teller, Function<String, String> answer) {
        return FamilyAccount.read(text, teller, new GenealogyProfile().predicates(), p -> p.contains("Answer with one word") ? "no" : answer.apply(p));
    }

    private static String name(String person, String name, String quote) {
        return "{\"person\": \"" + person + "\", \"name\": \"" + name + "\", \"kind\": \"unknown\", \"quote\": \"" + quote + "\"}";
    }

    private static List<String> names(FamilyAccount.Read r) { return r.names().stream().map(n -> n.person() + " = " + n.name()).toList(); }
    private static List<String> triples(FamilyAccount.Read r) { return r.facts().stream().map(f -> f.subject() + " | " + f.relation() + " | " + f.object()).toList(); }

    @Test
    void aNameBesideARelationWordIsTheRelativesAndTheRelationIsFiledInstead() {
        String text = "Ann Hale was born in Leeds in 1900. Her husband Tom Hart kept a shop, and their son Ned Hart was born in 1930. "
                + "Ann Hale's daughter Ruth taught in York. Isamu Morita, her brother, went to Sendai. Mary Ellis, wife of Ken Ellis, kept the books. "
                + "森田ハルは1905年に生まれた。夫の森田健二は津で商店を営んだ。長女まりは学校に通った。";
        FamilyAccount.Read r = read(text, "an aunt", "{\"people\": [{\"name\": \"Ann Hale\", \"sex\": \"female\"}, {\"name\": \"森田ハル\", \"sex\": \"female\"}, {\"name\": \"Ken Ellis\", \"sex\": \"male\"}], "
                + "\"facts\": [{\"subject\": \"Ann Hale\", \"relation\": \"born-in\", \"object\": \"Leeds\", \"date\": \"1900\", \"quote\": \"Ann Hale was born in Leeds in 1900.\"},"
                + " {\"subject\": \"Ken Ellis\", \"relation\": \"occupation\", \"object\": \"farmer\", \"date\": \"\", \"quote\": \"Mary Ellis, wife of Ken Ellis, kept the books.\"}], "
                + "\"names\": [" + name("Ann Hale", "Tom Hart", "Her husband Tom Hart kept a shop, and their son Ned Hart was born in 1930.") + ", "
                + name("Ann Hale", "Ned Hart", "Her husband Tom Hart kept a shop, and their son Ned Hart was born in 1930.") + ", "
                + name("Ann Hale", "Ruth", "Ann Hale's daughter Ruth taught in York.") + ", "
                + name("Ann Hale", "Isamu Morita", "Isamu Morita, her brother, went to Sendai.") + ", "
                + name("Ken Ellis", "Mary Ellis", "Mary Ellis, wife of Ken Ellis, kept the books.") + ", "
                + name("森田ハル", "森田健二", "夫の森田健二は津で商店を営んだ。") + ", "
                + name("森田ハル", "まり", "長女まりは学校に通った。") + "]}");
        assertEquals(List.of(), names(r), "every name belongs to a relative: " + names(r));
        List<String> t = triples(r);
        assertTrue(t.contains("Ann Hale | parent-of | Ruth"), "her own daughter, from her name before the 's: " + t);
        assertTrue(t.contains("Ken Ellis | married-to | Mary Ellis"), "wife of Ken Ellis: " + t);
        assertTrue(t.stream().noneMatch(x -> x.startsWith("Ann Hale | married-to")), "\"her husband\" says whose only by a pronoun: the name comes off, no relation is guessed: " + t);
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("The name Tom Hart of Ann Hale " + FamilyAccount.OF_A_RELATIVE) && d.contains("call Tom Hart her husband, and this text makes Ann Hale a woman")), r.dropped().toString());
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("The name Ruth of Ann Hale " + FamilyAccount.OF_A_RELATIVE) && d.contains("wrote down \"Ann Hale is a parent of Ruth\" instead")), r.dropped().toString());
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("The name 森田健二 of 森田ハル ") && d.contains("call 森田健二 a 夫")), r.dropped().toString());
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("The name まり of 森田ハル ") && d.contains("call まり a 長女")), r.dropped().toString());
        assertTrue(GenealogyProfile.droppedSaid(r.dropped()).contains("because the words say the name belongs to a relative, or to another person of the text"));
    }

    @Test
    void aNameWithNoRelationWordAndANameTheRelationWordMayGiveThePersonStay() {
        String text = "Ann Hale married Tom Hart in 1925, and she was known as Ann Hart from then on. His wife Ann Hart kept the shop after him. "
                + "Their daughter Ruth Hart was born in 1930. Ruth Hart, their daughter, taught in York.";
        FamilyAccount.Read r = read(text, "an aunt", "{\"people\": [{\"name\": \"Ann Hale\", \"sex\": \"female\"}, {\"name\": \"Ruth Hart\", \"sex\": \"female\"}], "
                + "\"facts\": [{\"subject\": \"Ann Hale\", \"relation\": \"married-to\", \"object\": \"Tom Hart\", \"date\": \"1925\", \"quote\": \"Ann Hale married Tom Hart in 1925, and she was known as Ann Hart from then on.\"}], "
                + "\"names\": [" + name("Ann Hale", "Ann Hart", "Ann Hale married Tom Hart in 1925, and she was known as Ann Hart from then on.") + ", "
                + name("Ann Hale", "Mrs Hart", "His wife Ann Hart kept the shop after him.") + ", "
                + name("Ruth Hart", "Ruth", "Ruth Hart, their daughter, taught in York.") + "]}");
        List<String> kept = names(r);
        assertTrue(kept.contains("Ann Hale = Ann Hart"), "known as: hers: " + kept);
        assertTrue(kept.stream().noneMatch(x -> x.equals("Ann Hale = Tom Hart")), kept.toString());
        assertTrue(r.dropped().stream().noneMatch(d -> d.contains("Ann Hart of Ann Hale " + FamilyAccount.OF_A_RELATIVE)), "\"his wife Ann Hart\" may be Ann herself, a woman: " + r.dropped());
        assertTrue(r.dropped().stream().noneMatch(d -> d.contains("Ruth of Ruth Hart " + FamilyAccount.OF_A_RELATIVE)), "her own given name beside \"their daughter\" is hers: " + r.dropped());
    }

    @Test
    void anotherPersonsFactsMayComePiecesAfterTheNameAndStillTakeItOff() {
        // the front matter names the husband in words with no relation word; his own life comes many pages later, in another piece
        String front = "Acknowledgments\n\nAlso changed is Tom (John) Hart's address at the time of writing, from Leeds to York.\n\n" + "The farm grew year by year. ".repeat(230);
        String later = "\n\nThis was the case of a young man, Tom Hart, who in 1956 graduated from the seminary in York and served a church in Leeds.\n";
        String text = front + later;
        assertTrue(FamilyAccount.pieces(text).size() >= 2, "two pieces");
        FamilyAccount.Read r = read(text, "Ann Hale", p -> p.contains("Also changed") ? "{\"people\": [{\"name\": \"Ann Hale\", \"sex\": \"female\"}], \"facts\": [], "
                + "\"names\": [" + name("Ann Hale", "Tom (John) Hart", "Also changed is Tom (John) Hart's address at the time of writing, from Leeds to York.") + "]}"
                : p.contains("young man") ? "{\"people\": [{\"name\": \"Tom (John) Hart\", \"also\": [\"Tom Hart\"], \"sex\": \"male\"}], \"facts\": ["
                + "{\"subject\": \"Tom Hart\", \"relation\": \"life-event\", \"object\": \"graduated from the seminary\", \"date\": \"1956\", \"quote\": \"This was the case of a young man, Tom Hart, who in 1956 graduated from the seminary in York and served a church in Leeds.\"}], \"names\": []}"
                : "{\"people\": [], \"facts\": [], \"names\": []}");
        assertEquals(List.of(), names(r), "the husband's name is his, not hers: " + names(r));
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("Tom (John) Hart of Ann Hale " + FamilyAccount.OF_A_RELATIVE) && d.contains("another person in this text, with facts of their own")), r.dropped().toString());
        assertTrue(r.people().stream().anyMatch(p -> p.name().equals("Tom (John) Hart")), "he stays a person of the read with his own facts");
    }

    @Test
    void aNameThatIsAnotherPersonOfTheTextOrThatAFactRelatesToThePersonIsNotThePersons() {
        String text = "Ann Hale kept a shop in Leeds. Tom Hart was a printer in York, and in 1925 Ann Hale married Tom Hart. Also changed is Tom Hart's address.";
        FamilyAccount.Read r = read(text, "an aunt", "{\"people\": [{\"name\": \"Ann Hale\", \"sex\": \"female\"}, {\"name\": \"Tom Hart\", \"sex\": \"male\"}], "
                + "\"facts\": [{\"subject\": \"Tom Hart\", \"relation\": \"occupation\", \"object\": \"printer\", \"date\": \"\", \"quote\": \"Tom Hart was a printer in York, and in 1925 Ann Hale married Tom Hart.\"},"
                + " {\"subject\": \"Ann Hale\", \"relation\": \"married-to\", \"object\": \"Tom Hart\", \"date\": \"1925\", \"quote\": \"Tom Hart was a printer in York, and in 1925 Ann Hale married Tom Hart.\"}], "
                + "\"names\": [" + name("Ann Hale", "Tom Hart", "Also changed is Tom Hart's address.") + "]}");
        assertEquals(List.of(), names(r), names(r).toString());
        assertTrue(r.dropped().stream().anyMatch(d -> d.contains("Tom Hart of Ann Hale " + FamilyAccount.OF_A_RELATIVE) && (d.contains("another person in this text") || d.contains("two people"))), r.dropped().toString());
        // the same two written as one person by the text stay one: the name in brackets after the other
        String paired = "Ann Hale (Ann Hart) kept a shop in Leeds. Ann Hart was a printer's widow.";
        FamilyAccount.Read same = read(paired, "an aunt", "{\"people\": [{\"name\": \"Ann Hale\", \"also\": [\"Ann Hart\"]}], "
                + "\"facts\": [{\"subject\": \"Ann Hart\", \"relation\": \"occupation\", \"object\": \"widow\", \"date\": \"\", \"quote\": \"Ann Hart was a printer's widow.\"}], "
                + "\"names\": [" + name("Ann Hale", "Ann Hart", "Ann Hale (Ann Hart) kept a shop in Leeds.") + "]}");
        assertTrue(same.dropped().stream().noneMatch(d -> d.contains(FamilyAccount.OF_A_RELATIVE)), same.dropped().toString());
    }
}
