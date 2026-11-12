package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The chat's own turns in each language the interface has a file for, said in that language's own words and answered in it: the
 * translation of a file (which file, the tier question, the answer with the notes, the reply after filing), a research ask that asks
 * whether to use a field's mode and its answer, the models, the glossary, and /help. Nothing the library says there falls back to
 * English (Chrome.MISSING stays empty), and none of the English sentences these turns used to say in a Japanese chat appears. Japanese
 * now; each language file dropped in is walked the same way with no change here. Stand-ins for the model, the scorers and the servers.
 */
class ChatLanguageTest {
    static final ObjectMapper M = new ObjectMapper();
    @TempDir Path tmp;
    String realHome;
    LibraryStore store;

    @BeforeEach void up() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", Files.createDirectories(tmp.resolve("home")).toString()); Config.invalidate();
        Translation.ANY_MODEL = () -> true; Translation.DRIVE_ANSWERS = url -> true; Translation.SETUP_SAID = () -> "";
        store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
    }

    @AfterEach void down() {
        Chrome.clear();
        Translation.ANY_MODEL = Translation::anyModelNow; Translation.DRIVE_ANSWERS = Crews::driveAnswers;
        Translation.SETUP_SAID = () -> { String a = TranslationScores.labseSetupSaid(), b = TranslationScores.metricxSetupSaid(); return a.isEmpty() ? b : b.isEmpty() ? a : a + "; " + b; };
        System.setProperty("user.home", realHome); Config.invalidate();
    }

    /** What the chat's own turns said in English in a Japanese chat before, and the English made in code that filled its sentences. */
    static final List<String> ENGLISH = List.of("Which file?", "units with words", "characters), written in", "Into Japanese as", "Checked, quick, reviewed or thorough?", "the choice starts it",
            "Translating it now", "This chat says when it is done", " checked, the default; quick ", "a first guess", "under a minute", "about 1 minute", "Taken: ", "The translation J-",
            "Use genealogy mode", "(y/N)", "This looks like family history", "It is researched as ordinary research", "has started; a run usually takes",
            "The default is the entry named default", "Models that can run now", "It is the only entry", "Ask anything about what the library holds", "/family (your family",
            "The glossary is empty", "The passages the checks still doubt", "What the checks still doubt");

    @Test
    void theChatsOwnTurnsInEveryLanguageSayNothingInEnglish() throws Exception {
        List<String> codes = Chrome.available().subList(1, Chrome.available().size());
        assertTrue(codes.contains("ja"));
        Path doc = TranslateTurnTest.notes(Files.createDirectories(tmp.resolve("docs")), "saros notes.md");
        List<String> faults = new ArrayList<>();
        for (String code : codes) {
            Chrome.here(code);
            Chrome c = Chrome.now();
            Map<String, List<String>> w = ChatWords.files().get(code);
            assertNotNull(w, "a words file beside " + code + ".json");
            Chrome.MISSING.clear();
            List<String> said = new ArrayList<>();
            try {
                // the translation of the file posted: asked in the person's words, "Which file?", the file and "this file", the tier question
                Librarian.Session session = Librarian.Session.open(store);
                Librarian lib = new Librarian(store, TranslateTurnTest.noModel(), Librarian.person(), session);
                String ask = translateAsk(code, w);
                String which = lib.say(ask);
                said.add(which);
                assertEquals(c.t(TranslateTurn.WHICH_FILE), which, code + ": " + ask);
                session.att = M.createObjectNode().put("name", "saros notes.md").put("path", doc.toString());
                String asked = lib.say(ChatWordsTest.plain(w, "this_file").get(0));
                said.add(asked);
                assertTrue(asked.contains(c.t(TranslateTurn.TIER_QUESTION)) && asked.contains("J-0001"), code + ": " + asked);
                // the buttons under the question show the tiers as the person reads them; what they post is the name the answer is read by
                String view = Pages.chatView(store, new LibraryProtocol(store), Patrons.Patron.PERSON, session, "");
                for (String tier : List.of(Translation.CHECKED, Translation.QUICK, Translation.REVIEWED, Translation.THOROUGH))
                    assertTrue(view.contains("value=\"" + tier + "\"><button class=\"btn opt\">" + Pages.esc(c.t(tier)) + "</button>") && !view.contains(">" + tier + "</button>"), code + ": " + view);
                // the answer: the tier and the notes at the end, in the person's words; the reply after filing says where the doubts go
                String taken = lib.say(ChatWordsTest.plain(w, "tier.checked").get(0) + (code.equals("ja") || code.equals("zh") ? "、" : ", ") + first(w, "notes.end", code));
                said.add(taken);
                assertTrue(taken.contains(c.t(Translation.NOTES_AT_END_SAID)), code + ": " + taken);
                assertEquals(Translation.NOTES_END, new Jobs(store, j -> "").get("J-0001").path("args").path("notes").asText(), code + ": " + taken);
                var kept = session.messages().get(session.messages().size() - 1);
                assertTrue(kept.path("role").asText().equals("assistant") && kept.path("by").asText().equals("library"), "the reply to the library's own question is the library's, and its mark says so: " + kept);
                // a research ask the model files: the library's question whether to use a field's mode, and the person's no to it
                Librarian.Session s2 = Librarian.Session.open(store);
                Librarian research = new Librarian(store, LibrarianChatTest.scripted(List.of(LibrarianChatTest.tool("library_research", "{\"question\": \"" + GenealogyOfferTest.FAMILY + "\"}"), LibrarianChatTest.say("Filed.")), new ArrayList<>()), Librarian.person(), s2);
                String offer = research.say("great-grandfather");
                said.add(offer.replace("Filed.", ""));   // the model's own words are the model's
                assertTrue(offer.endsWith(c.yesNo(c.t("Use {0} mode for this question?", Fields.said("genealogy")))), code + ": " + offer);
                String no = research.say(ChatWordsTest.plain(w, "no").get(0));
                said.add(no);
                assertTrue(no.startsWith(c.t("It is researched as ordinary research.")), code + ": " + no);
                // the models, asked in the person's words; the glossary; /help
                said.add(lib.say(modelsAsk(code, w)));
                said.add(lib.say(glossaryAsk(code, w)));
                said.add(ChatCommands.help(store, null));
            } finally { Chrome.clear(); }
            for (String s : said) for (String en : ENGLISH) if (s.contains(en)) faults.add(code + ": \"" + en + "\" in: " + s);
            List<String> missing = new ArrayList<>(new TreeSet<>(Chrome.MISSING));
            if (!missing.isEmpty()) faults.add(code + " lacks: " + String.join(" | ", missing));
            store = new LibraryStore(tmp.resolve("lib-" + code)); store.init(); new LibrarianIndex(store, Embeddings.none()).rebuild();   // the next language's own library: its jobs numbered from J-0001
        }
        assertTrue(faults.isEmpty(), String.join("\n", faults));
    }

    /** The plan's clauses are joined as each language joins two clauses, never by a separator fixed in code; English reads as it did. */
    @Test
    void thePlansClausesAreJoinedInTheLanguagesOwnWay() {
        Translation.Plan plan = new Translation.Plan("notes.md", "md", "en", "ja", "md", Translation.CHECKED, 12, 4000, 30, 50, 200, 400, false, "", false, 0, 0, "", false, 0, 0, TranslationScores.METRICX_MISSING_SAID);
        String en = plan.said();
        assertTrue(en.contains("(a first guess: nothing has been timed on this model yet); MetricX-24 is not installed, so the tier runs on LaBSE alone"), en);
        Chrome.MISSING.clear();
        String ja = plan.said(Chrome.in("ja")), one = plan.saidFor(Chrome.in("ja"));
        for (String s : List.of(ja, one)) {
            assertFalse(s.contains("; ") || s.contains(" （"), "no ASCII semicolon and no space before a full-width bracket: " + s);
            assertTrue(s.contains("（目安です。このモデルではまだ時間を計っていません）。MetricX-24 が入っていないので"), "the note closes up to the times and the next clause begins as a sentence: " + s);
        }
        assertTrue(Chrome.MISSING.stream().noneMatch(m -> m.startsWith("ja\t")), Chrome.MISSING.toString());
    }

    /** "Translate this file into Japanese" in a language's own words. */
    static String translateAsk(String code, Map<String, List<String>> w) {
        if (code.equals("ja")) return "このファイルを日本語に翻訳して";
        String phrase = ChatWordsTest.plain(w, "translate.ask").get(0);
        return phrase.replace("{what}", ChatWordsTest.plain(w, "this_file").get(0)).replace("{lang}", ChatWordsTest.plain(w, "lang.ja").get(0));
    }

    /** "Which model is the default?" in a language's own words. */
    static String modelsAsk(String code, Map<String, List<String>> w) {
        if (code.equals("ja")) return "既定のモデルはどれ？";
        return ChatWordsTest.plain(w, "models.model").get(0) + " " + ChatWordsTest.plain(w, "models.default").get(0) + "?";
    }

    /** "Show me the glossary" in a language's own words. */
    static String glossaryAsk(String code, Map<String, List<String>> w) {
        return code.equals("ja") ? "用語集を見せて" : ChatWordsTest.plain(w, "glossary.list").get(0);
    }

    /** The first phrase of a meaning, or the Japanese one written out where the file holds a pattern. */
    static String first(Map<String, List<String>> w, String meaning, String code) {
        List<String> p = ChatWordsTest.plain(w, meaning);
        if (!p.isEmpty()) return p.get(0);
        return meaning.equals("notes.end") ? "訳注は末尾に" : "訳注なし";
    }
}
