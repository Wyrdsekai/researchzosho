package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TranslateTest {
    /** A stand-in model: the glossary one line a term, numbered lines back by their numbers, a text as one piece; {@code f} marks each translation. */
    static Researcher.Drive standIn(java.util.function.UnaryOperator<String> f) {
        return new Researcher.Drive() {
            @Override public ObjectNode chat(ArrayNode m, ArrayNode t, int x, String c) { return null; }
            @Override public String classify(ArrayNode m, int x) {
                String user = m.get(m.size() - 1).path("content").asText(), system = m.get(0).path("content").asText();
                String mark = system.contains("Acme Corp → アクメ社") ? "✓glossary " : "";
                if (user.startsWith("TERMS:\n")) { StringBuilder g = new StringBuilder(); for (String term : user.substring(7).split("\n")) g.append(term).append('\t').append(term.equals("Acme Corp") ? "アクメ社" : "〈" + term + "〉").append('\n'); return g.toString(); }
                if (user.startsWith("LINES:\n")) { StringBuilder o = new StringBuilder(); for (String line : user.substring(7).split("\n")) { int dot = line.indexOf(". "); if (dot > 0) o.append(line, 0, dot + 2).append(mark).append(f.apply(line.substring(dot + 2))).append('\n'); } return o.toString(); }
                return mark + f.apply(user);
            }
            @Override public int contextWindow() { return 8192; }
        };
    }

    @Test
    void aDossierReadInAnotherLanguageIsTheSamePageWithItsPiecesTranslatedAndKeptUntilItChanges(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        CrossLinksTest.report(store, "Who is Ada Example? A full dossier.", "\n## What they do\n\nAda Example builds widgets at Acme Corp [1].\n");
        CrossLinksTest.claim(store, "Ada Example, 2009: joined Acme Corp", "Ada Example joined Acme Corp in Boston in 2009 [1]. Sources: [1] https://example.org/a");
        List<String> pieces = Translate.collect(store, "Ada Example");
        assertTrue(pieces.contains("Ada Example, 2009: joined Acme Corp") && pieces.stream().anyMatch(s -> s.contains("builds widgets")), "the claim titles and the sections are the pieces: " + pieces);
        assertTrue(Translate.terms(String.join("\n", pieces)).contains("Acme Corp"), "a name that recurs is a term of the glossary");
        Translate.Translated t = Translate.dossierIn(store, "Ada Example", "ja", standIn(text -> "【訳】" + text));
        assertTrue(t.fresh() && t.pieces() >= 3 && t.kept() == 0 && t.glossary() >= 1 && t.words().size() == t.pieces(), t.summary() + " pieces=" + pieces + " words=" + t.words());
        assertTrue(t.summary().contains("pieces translated with a glossary of"), t.summary());
        // the page is the same page: the badges, the ids and the links stand, the words are translated
        String page = DossierPage.render(store, "Ada Example", t.lookup(), "ja");
        assertTrue(page.contains("【訳】Ada Example, 2009: joined Acme Corp") && page.contains("✓glossary") && page.contains("class=\"cls") && page.contains("/entry/F-0001") && page.contains("Read in Japanese") && page.contains(">the original</a>"), page);
        assertTrue(t.markdown().contains("【訳】") && t.markdown().contains("F-0001"), t.markdown());
        assertTrue(Files.exists(Translate.file(store, "Ada Example", "ja")) && Files.readString(Translate.file(store, "Ada Example", "ja")).contains("アクメ社"));
        Translate.Translated again = Translate.dossierIn(store, "Ada Example", "ja", null);   // no model needed: the dossier has not changed
        assertFalse(again.fresh()); assertEquals(t.markdown(), again.markdown()); assertTrue(again.summary().contains("has not changed since"), again.summary());
        assertNotNull(Translate.current(store, "Ada Example", "Japanese"));
        // the dossier grows: translated again; a stand-in that drops a cite leaves that piece in the original
        CrossLinksTest.claim(store, "Ada Example, 2011: left Acme Corp", "Ada Example left Acme Corp in 2011 [2]. Sources: [2] https://example.org/b");
        assertNull(Translate.current(store, "Ada Example", "ja"));
        Translate.Translated t2 = Translate.dossierIn(store, "Ada Example", "ja", standIn(text -> text.contains("[1]") ? text.replace("[1]", "") + " 訳" : "【訳】" + text));
        assertTrue(t2.fresh() && t2.kept() >= 1, t2.summary());
        String page2 = DossierPage.render(store, "Ada Example", t2.lookup(), "ja");
        assertTrue(page2.contains("【訳】Ada Example, 2011: left Acme Corp") && page2.contains("Ada Example builds widgets at Acme Corp [1]") && !page2.contains("【訳】Ada Example builds widgets"), "the piece that would lose its cite stays as it was: " + page2);
        // through the protocol, with the library's model stood in for
        Translate.DRIVE = () -> standIn(text -> "EN: " + text);
        try {
            ObjectNode a = new ObjectMapper().createObjectNode(); a.put("subject", "Ada Example"); a.put("in", "English");
            var r = new LibraryProtocol(store).dossier(a);
            assertEquals("en", r.path("language").asText()); assertTrue(r.path("markdown").asText().contains("EN: Ada Example, 2009"), r.path("markdown").asText());
        } finally { Translate.DRIVE = Explain::drive; }
        // the language of a text, and a language as a person names it
        assertEquals("ja", Translate.languageOf("高市早苗について：経歴と現職")); assertEquals("en", Translate.languageOf("Harvard University is governed by two boards.")); assertEquals("zh", Translate.languageOf("北京大学"));
        assertEquals("en", Translate.code("English")); assertEquals("ja", Translate.code("日本語")); assertEquals("ja", Translate.code("JA")); assertEquals("de", Translate.code("de"));
        // a piece never loses a cite unnoticed
        assertTrue(Translate.keeps("a [1] b (F-0001-x) https://x.example/p", "b (F-0001-x) [1] https://x.example/p c")); assertFalse(Translate.keeps("a [1] b", "a b"));
    }
}
