package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the chat reads in a person's own words, file by file: every words file under chrome/words/ has every meaning, its patterns compile,
 * and each of its phrases is read as what it means and as nothing else: a tier as that tier (the longest phrase winning), the notes at
 * the end and no notes as such and never as a yes or a no to the run, yes and no as such, "this file" as the file posted, a language's
 * name as that language, and an ask to translate a file, filled with the file's own words, as that ask. A file dropped in is checked
 * here with no change.
 */
class ChatWordsTest {
    /** The meanings every file must fill: what the chat cannot do without in any language. */
    static final List<String> FILLED = List.of("tier.quick", "tier.checked", "tier.reviewed", "tier.thorough", "notes.end", "notes.off", "yes", "no", "none", "this_file",
            "lang.en", "lang.ja", "lang.zh", "lang.ko", "lang.de", "lang.fr", "lang.es", "lang.pt", "lang.it", "lang.ru", "translate.ask", "translate.mention", "hint.yes_no");

    @Test
    void everyFileIsWholeAndItsPatternsCompile() {
        Map<String, Map<String, List<String>>> files = ChatWords.files();
        assertTrue(files.keySet().containsAll(List.of("en", "ja")), files.keySet().toString());
        assertEquals(ChatWords.CODES, List.copyOf(files.keySet()), "a words file for English and each language the interface may be written in");
        List<String> faults = new ArrayList<>();
        for (var f : files.entrySet()) {
            for (String m : ChatWords.MEANINGS) if (!f.getValue().containsKey(m)) faults.add(f.getKey() + ": no " + m);
            for (String m : FILLED) if (f.getValue().getOrDefault(m, List.of()).isEmpty() && !(m.equals("yes") && !f.getValue().getOrDefault("yes.alone", List.of()).isEmpty())) faults.add(f.getKey() + ": nothing for " + m);   // a language whose yes also opens sentences (да) has it under yes.alone
            for (String m : f.getValue().keySet()) if (!ChatWords.MEANINGS.contains(m)) faults.add(f.getKey() + ": a meaning nothing reads: " + m);
            for (var e : f.getValue().entrySet()) for (String x : e.getValue()) {
                try { ChatWords.pattern(x, Map.of()); if (!ChatWords.isPattern(x)) ChatWords.pattern("re:(?iu)" + ChatWords.phrase(x, TranslateTurn.places()), null); }
                catch (RuntimeException ex) { faults.add(f.getKey() + " " + e.getKey() + ": " + x + " — " + ex.getMessage()); }
            }
        }
        assertTrue(faults.isEmpty(), String.join("\n", faults));
    }

    @Test
    void eachPhraseIsReadAsWhatItMeansAndAsNothingElse() {
        List<String> faults = new ArrayList<>();
        for (var f : ChatWords.files().entrySet()) {
            String code = f.getKey();
            Map<String, List<String>> w = f.getValue();
            for (String tier : ChatWords.TIERS) for (String p : plain(w, "tier." + tier)) if (!ChatWords.tier(p).equals(tier)) faults.add(code + ": \"" + p + "\" is read as tier " + ChatWords.tier(p) + ", not " + tier);
            for (String p : plain(w, "notes.end")) {
                if (!TranslateTurn.notesIn(p).equals(Translation.NOTES_END)) faults.add(code + ": \"" + p + "\" is not read as the notes at the end");
                if (!TranslateTurn.withoutNotes(p).isEmpty()) faults.add(code + ": \"" + p + "\" leaves \"" + TranslateTurn.withoutNotes(p) + "\" when the notes are taken out");
            }
            for (String p : plain(w, "notes.off")) {
                if (!TranslateTurn.notesIn(p).equals(Translation.NOTES_OFF)) faults.add(code + ": \"" + p + "\" is not read as no notes");
                if (ChatWords.isNo(p) || ChatWords.isNone(p) || ChatWords.yesWord(p) != null) faults.add(code + ": \"" + p + "\" is read as an answer to the run, not as no notes");
                if (!TranslateTurn.withoutNotes(p).isEmpty()) faults.add(code + ": \"" + p + "\" leaves \"" + TranslateTurn.withoutNotes(p) + "\" when the notes are taken out");
            }
            for (String m : List.of("yes", "yes.alone")) for (String p : plain(w, m)) { if (ChatWords.yesWord(p) == null) faults.add(code + ": \"" + p + "\" is not a yes"); if (ChatWords.isNo(p)) faults.add(code + ": \"" + p + "\" is a no"); }
            // a yes that may be followed by more words is a word nothing else begins with: never a single letter of an alphabet
            for (String p : plain(w, "yes")) if (p.codePointCount(0, p.length()) == 1 && ChatWords.spaced(p.codePointAt(0))) faults.add(code + ": the letter \"" + p + "\" belongs under yes.alone");
            for (String p : plain(w, "no")) { if (!ChatWords.isNo(p)) faults.add(code + ": \"" + p + "\" is not a no"); if (ChatWords.yesWord(p) != null) faults.add(code + ": \"" + p + "\" is a yes"); }
            for (String p : plain(w, "none")) if (!ChatWords.isNone(p)) faults.add(code + ": \"" + p + "\" is not none");
            for (String p : plain(w, "this_file")) if (!TranslateTurn.pointsAtTheFile(p)) faults.add(code + ": \"" + p + "\" does not point at the file");
            for (String lang : Translate.LANGUAGES.keySet()) for (String p : plain(w, "lang." + lang)) if (!ChatWords.language(p).equals(lang)) faults.add(code + ": \"" + p + "\" is read as " + ChatWords.language(p) + ", not " + lang);
            // the hint a yes-or-no question ends with: its first word is a yes, its second a no, in this file's words
            String hint = w.get("hint.yes_no").get(0);
            Matcher h = Pattern.compile("^[(（]([^/／]+)[/／]([^)）]+)[)）]$").matcher(hint);
            if (!h.matches()) faults.add(code + ": the hint " + hint + " is not (yes/no)");
            else { if (ChatWords.yesWord(h.group(1)) == null) faults.add(code + ": " + h.group(1) + " of the hint is not a yes"); if (!ChatWords.isNo(h.group(2))) faults.add(code + ": " + h.group(2) + " of the hint is not a no"); }
            // an ask to translate the file posted, in this language's own phrases, filled with its words for "this file" and for Japanese (English when it is Japanese's own file)
            String target = code.equals("ja") ? "en" : "ja";
            for (String p : plain(w, "translate.ask")) {
                String file = plain(w, "this_file").get(0), name = plain(w, "lang." + target).get(0);
                String said = p.replace("{what}", file).replace("{lang}", name), saidChecked = said + (said.matches("(?s).*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]$") ? "、" : ", ") + plain(w, "tier.checked").get(0) + ", " + plain(w, "notes.end").get(0);
                TranslateTurn.Ask a = TranslateTurn.parse(said);
                if (a == null || !a.lang().equals(target) || !TranslateTurn.pointsAtTheFile(a.what())) { faults.add(code + ": \"" + said + "\" is read as " + a); continue; }
                TranslateTurn.Ask b = TranslateTurn.parse(saidChecked);
                if (b == null || !b.lang().equals(target) || !TranslateTurn.pointsAtTheFile(b.what()) || !b.quality().equals(Translation.CHECKED) || !b.notes().equals(Translation.NOTES_END)) faults.add(code + ": \"" + saidChecked + "\" is read as " + b);
            }
            if (!code.equals("en") && !code.equals("ja") && plain(w, "translate.ask").isEmpty()) faults.add(code + ": no phrase asks for a translation");
            // the language a document is said to be written in, in this file's phrases and its own names for German and for Dutch (a language the
            // program tells apart and does not translate into): read as that language, and never as the language to translate into
            if (plain(w, "translate.from").isEmpty()) faults.add(code + ": no phrase names the language a document is written in");
            Chrome own = Chrome.in(code);
            for (String p : plain(w, "translate.from")) {
                for (String[] named : new String[][] {{"de", plain(w, "lang.de").get(0)}, {"nl", own.word("Dutch") == null ? "Dutch" : own.word("Dutch")}}) {
                    String said = p.replace("{lang}", named[1]);
                    if (!TranslateTurn.fromIn(said).equals(named[0])) faults.add(code + ": \"" + said + "\" is read as written in \"" + TranslateTurn.fromIn(said) + "\", not " + named[0]);
                    if (!TranslateTurn.fromSaid(said).rest().isEmpty()) faults.add(code + ": \"" + said + "\" leaves \"" + TranslateTurn.fromSaid(said).rest() + "\" when it is taken out");
                    for (String q : plain(w, "translate.ask")) {
                        String ask = said + (said.matches("(?s).*[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]$") ? "、" : ", ") + q.replace("{what}", plain(w, "this_file").get(0)).replace("{lang}", plain(w, "lang." + target).get(0));
                        TranslateTurn.Ask a = TranslateTurn.parse(ask);
                        if (a == null || !a.lang().equals(target) || !a.from().equals(named[0]) || !TranslateTurn.pointsAtTheFile(a.what())) faults.add(code + ": \"" + ask + "\" is read as " + a);
                    }
                }
            }
        }
        assertTrue(faults.isEmpty(), faults.size() + " fault(s):\n" + String.join("\n", faults));
    }

    /** The language a document is written in, said where a person would say it, in the language they chat in; and a phrase that only looks like it. */
    @Test
    void theLanguageADocumentIsWrittenInIsReadInEachLanguage() {
        String[][] said = {
                {"translate this file from German into English", "de", "en"}, {"translate this file from Dutch into Japanese", "nl", "ja"},
                {"このファイルをドイツ語から英語に翻訳して", "de", "en"}, {"このファイルをオランダ語から日本語に翻訳して", "nl", "ja"},
                {"把这个文件从德语翻译成英语", "de", "en"}, {"把这个文件从波兰语翻译成英语", "pl", "en"},
                {"이 파일을 독일어에서 영어로 번역해 주세요", "de", "en"}, {"이 파일을 폴란드어에서 영어로 번역해 주세요", "pl", "en"},
                {"Übersetze diese Datei aus dem Deutschen ins Englische", "de", "en"}, {"Übersetze diese Datei von Polnisch auf Englisch", "pl", "en"},
                {"Übersetze diese Datei aus dem Polnischen ins Englische", "pl", "en"}, {"Übersetze diese Datei vom Niederländischen ins Englische", "nl", "en"},
                {"Traduis ce fichier de l'allemand vers l'anglais", "de", "en"}, {"Traduis ce fichier du polonais vers l'anglais", "pl", "en"},
                {"Traduce este archivo del alemán al inglés", "de", "en"}, {"Traduce este archivo del polaco al inglés", "pl", "en"},
                {"Traduza este arquivo do alemão para o inglês", "de", "en"}, {"Traduza este arquivo do polonês para o inglês", "pl", "en"},
                {"Traduci questo file dal tedesco in inglese", "de", "en"}, {"Traduci questo file dal polacco in inglese", "pl", "en"},
                {"Переведи этот файл с немецкого на английский", "de", "en"}, {"Переведи этот файл на английский, язык оригинала — польский", "pl", "en"},
                {"Переведи этот файл с польского на английский", "pl", "en"}, {"Переведи этот файл с чешского языка на английский", "cs", "en"},
                // said as a sentence of its own, before the ask or after it: the stop it leaves goes with it
                {"원문은 독일어입니다. 이 파일을 영어로 번역해 주세요", "de", "en"}, {"The source language is Dutch. Translate this file into English.", "nl", "en"},
                {"Translate this file into English. The original is in Polish.", "pl", "en"}, {"原文はドイツ語です。このファイルを英語に翻訳して", "de", "en"},
        };
        for (String[] s : said) {
            TranslateTurn.Ask a = TranslateTurn.parse(s[0]);
            assertNotNull(a, s[0]);
            assertEquals(s[1], a.from(), s[0]);
            assertEquals(s[2], a.lang(), s[0]);
            assertTrue(TranslateTurn.pointsAtTheFile(a.what()), s[0] + " → " + a.what());
        }
        assertEquals("", TranslateTurn.fromIn("translate the notes from the meeting into English"), "a word after from that names no language is no language");
        assertEquals("", TranslateTurn.fromIn("translate the letter from Germany into English"), "a country is not a language");
        for (String no : List.of("Traduce el manual de inglés al japonés", "Переведи этот файл на английский с примечаниями в конце", "Übersetze die Notizen vom Montag ins Englische",
                "Übersetze den Bericht vom städtischen Archiv ins Englische", "Переведи письмо с городского сайта на английский"))
            assertEquals("", TranslateTurn.fromIn(no), no);
        // an expression of a words file that matches where no language stands takes nothing out of the words
        assertEquals("städtischen Archiv", ChatWords.without("städtischen Archiv", "tier.quick"));
        assertEquals(TranslateTurn.From.NONE, TranslateTurn.fromSaid("Übersetze den Bericht vom städtischen Archiv ins Englische"));
        // the stem of a declined name is one language's or nobody's
        assertEquals("pl", ChatWords.sourceLanguageByStem("Polnisch"));
        assertEquals("pl", ChatWords.sourceLanguageByStem("польск"));
        assertEquals("", ChatWords.sourceLanguageByStem("ser"), "too short to be a name's stem");
        assertEquals("", ChatWords.sourceLanguageByStem("städtisch"));
        // Korean spaces its words: taking the phrase out leaves the name of the document as it was typed
        assertEquals("회의 메모", TranslateTurn.parse("회의 메모 독일어에서 일본어로 번역해").what());
        assertEquals("de", TranslateTurn.parse("회의 메모 독일어에서 일본어로 번역해").from());
        // a document whose own name holds such a phrase: the words read without it keep the name whole
        assertEquals("Notes from German class", TranslateTurn.parse("translate Notes from German class into Japanese", false).what());
        assertEquals("", TranslateTurn.parse("translate Notes from German class into Japanese", false).from());
    }

    /** A language the program tells apart and does not translate into, named inside a sentence: with a small letter where the language writes its ten so, as a list shows it elsewhere. */
    @Test
    void aLanguagesNameInsideASentenceIsWrittenAsTheLanguageWritesIt() {
        assertEquals("néerlandais", Chrome.in("fr").languageName("nl"));
        assertEquals("польский", Chrome.in("ru").languageName("pl"));
        assertEquals("Niederländisch", Chrome.in("de").languageName("nl"), "German keeps the capital of a noun");
        assertEquals("Dutch", Chrome.ENGLISH.languageName("nl"));
        assertEquals("オランダ語", Chrome.in("ja").languageName("nl"));
        for (String code : List.of("fr", "es", "pt", "it", "ru"))
            for (String en : Languages.NAMES.values()) {
                String c = Languages.codeOf(en), w = Chrome.in(code).languageName(c);
                if (Translate.LANGUAGES.containsKey(c)) continue;
                assertTrue(Character.isLowerCase(w.codePointAt(0)) || !Character.isLetter(w.codePointAt(0)), code + " names " + en + " inside a sentence as " + w);
            }
    }

    /** A subject of research is not a question about the library's models: physics has a Standard Model in every language. */
    @Test
    void theStandardModelIsASubjectNotASetting() {
        for (String q : List.of("What is the Standard Model?", "Was ist das Standardmodell der Teilchenphysik?", "O que é o Modelo Padrão da física de partículas?",
                "¿Qué es el modelo estándar?", "Qu'est-ce que le modèle standard ?", "Cos'è il modello standard?", "Что такое Стандартная модель?", "什么是标准模型？",
                "標準模型とは何ですか", "표준 모형이란 무엇인가요?"))
            assertFalse(ModelsTurn.about(q), q);
        assertTrue(ModelsTurn.about("What is the default model?"));
        assertTrue(ModelsTurn.about("Welches Modell ist voreingestellt?"), "the default model is still asked about in German");
        assertTrue(ModelsTurn.about("Qual modelo é usado por padrão?"), "and in Portuguese");
    }

    /** A tier said after the file with no comma, in the order Italian and Spanish put it, is not part of the file's name; an adjective alone may be. */
    @Test
    void aTierAfterTheFileIsNotItsName() {
        TranslateTurn.Ask a = TranslateTurn.parse("traduci in inglese questo file con controllo");
        assertNotNull(a);
        assertTrue(TranslateTurn.pointsAtTheFile(a.what()), a.what());
        assertEquals(Translation.CHECKED, a.quality());
        assertEquals("en", a.lang());
        TranslateTurn.Ask named = TranslateTurn.parse("traduci in inglese la guida rapida");
        assertNotNull(named);
        assertEquals("la guida rapida", named.what(), "one adjective after a name stays with the name");
    }

    /** A clear yes: a sentence that only begins like one starts nothing. These are what a person might type next after the library's question. */
    @Test
    void aWordThatOpensASentenceIsNotAYes() {
        for (String sentence : List.of("Claro que no", "O que eu faço?", "o forse domani", "Si quieres, busca también esto", "si tu veux", "Да что же мне делать",
                "y también esto", "s is the letter I mean", "j ai une question", "Vale la pena?", "Com certeza não", "Certo che no", "Хорошо бы узнать больше"))
            assertNull(ChatWords.yesWord(sentence), sentence);
        assertEquals("yes", ChatWords.yesWord("yes please do that"), "a plain yes may be followed by more words, as it always was");
        assertEquals("oui", ChatWords.yesWord("Oui merci"));
        for (String letter : List.of("y", "Y", "s", "o", "j", "д")) assertEquals(letter.toLowerCase(Locale.ROOT), ChatWords.yesWord(letter), "the hint's letter by itself is a yes");
        assertEquals("y", ChatWords.yesWord("y, and also this"), "before a comma or a stop it is a yes, and the rest follows");
        assertEquals("o", ChatWords.yesWord("o."));
    }

    @Test
    void theLongestPhraseWinsAndTheEnglishIsReadAsItWas() {
        assertEquals(Translation.THOROUGH, ChatWords.tier("全文査読で"));
        assertEquals(Translation.REVIEWED, ChatWords.tier("査読付き"));
        assertEquals(Translation.THOROUGH, ChatWords.tier("전체 검토로"), "전체 검토 holds 검토, and is thorough");
        assertEquals(Translation.CHECKED, ChatWords.tier("checked"));
        assertEquals(Translation.QUICK, ChatWords.tier("quickly please"), "English is read inside a word, as it always was");
        assertEquals(Translation.THOROUGH, ChatWords.tier("reviewed, no, thorough"));
        assertEquals("", ChatWords.tier("a reluctant reader"), "a phrase of another language is a whole word: relu in reluctant is not relu");
        assertEquals(Translation.NOTES_OFF, TranslateTurn.notesIn("without the notes at the end"));
        assertEquals(Translation.NOTES_OFF, TranslateTurn.notesIn("訳注は末尾に載せない"));
        assertEquals("yes", ChatWords.yesWord("Yes, and also this"));
        assertNull(ChatWords.yesWord("yesterday")); assertNull(ChatWords.yesWord("japan"));
        assertTrue(ChatWords.isNo("") && ChatWords.isNo("No.") && ChatWords.isNo("いいえ"));
        assertEquals("ja", ChatWords.languageIn("把这个文件翻译成日语"), "a language's name after a letter, where words are not spaced");
        assertEquals("ja", ChatWords.languageIn("このファイルを日本語に翻訳して")); assertEquals("ko", ChatWords.languageIn("이 파일을 한국어로 번역해 주세요"));
        assertEquals("ja", ChatWords.languageIn("please put it into Japanese")); assertEquals("", ChatWords.languageIn("the unenglished parts"), "inside a word it names nothing");
        assertEquals("ja", ChatWords.language("japonais")); assertEquals("ja", ChatWords.language("Japanisch")); assertEquals("ja", ChatWords.language("японский")); assertEquals("ja", ChatWords.language("日本語"));
    }

    @Test
    void theAsksOfOtherLanguagesReachTheirTurns() {
        // a French person's ask reaches the translation turn: the file, the language, the tier, the notes
        TranslateTurn.Ask fr = TranslateTurn.parse("traduis ce fichier en japonais, vérifié, avec les notes à la fin");
        assertNotNull(fr); assertEquals("ja", fr.lang()); assertTrue(TranslateTurn.pointsAtTheFile(fr.what()), fr.what()); assertEquals(Translation.CHECKED, fr.quality()); assertEquals(Translation.NOTES_END, fr.notes());
        TranslateTurn.Ask ru = TranslateTurn.parse("переведи этот файл на японский, тщательно, без примечаний");
        assertNotNull(ru); assertEquals("ja", ru.lang()); assertEquals(Translation.THOROUGH, ru.quality()); assertEquals(Translation.NOTES_OFF, ru.notes());
        TranslateTurn.Ask zh = TranslateTurn.parse("把这个文件翻译成日语，带检查");
        assertNotNull(zh); assertEquals("ja", zh.lang()); assertTrue(TranslateTurn.pointsAtTheFile(zh.what())); assertEquals(Translation.CHECKED, zh.quality());
        TranslateTurn.Ask de = TranslateTurn.parse("übersetze diese Datei ins Japanische als PDF");
        assertNotNull(de); assertEquals("ja", de.lang()); assertEquals("pdf", de.as()); assertTrue(TranslateTurn.pointsAtTheFile(de.what()), de.what());
        // the format, said in the ask, leaves the file's words
        TranslateTurn.Ask es = TranslateTurn.parse("traduce este archivo en pdf al japonés");
        assertNotNull(es); assertEquals("pdf", es.as()); assertTrue(TranslateTurn.pointsAtTheFile(es.what()), es.what());
        // a report read in a language, the pictures, the models: each turn's asks in another language
        assertEquals("ja", ReadInTurn.parse("lis ce rapport en japonais").lang());
        assertEquals("ja", ReadInTurn.parse("この報告書を日本語で読む").lang());
        assertNotNull(ImagesTurn.find("trouve des images de la tour Eiffel")); assertEquals("la tour Eiffel", ImagesTurn.find("trouve des images de la tour Eiffel").get("what"));
        assertNotNull(ImagesTurn.find("Eiffel塔の画像を探して"));
        assertTrue(ImagesTurn.describes("qu'est-ce que c'est ?") && ImagesTurn.describes("was ist das"));
        assertEquals("person", ImagesTurn.kind("personne")); assertEquals("place", ImagesTurn.kind("Gebäude"));
        assertTrue(ModelsTurn.about("quel est le modèle par défaut ?"), "a French question about the default model is the models turn's");
        assertEquals("kimi", ModelsTurn.changeTarget("mets kimi par défaut"));
        assertFalse(ModelsTurn.about("what is the standard model of physics"), "a thing's model is not the library's");
        // a glossary line
        assertNotNull(ChatWords.ask("glossary.add", "glossaire : Acme Corp → Acmé (japonais)", TranslateTurn.places()));
    }

    /** A meaning's phrases in one file, its regular expressions left out. */
    static List<String> plain(Map<String, List<String>> file, String meaning) {
        List<String> out = new ArrayList<>();
        for (String x : file.getOrDefault(meaning, List.of())) if (!ChatWords.isPattern(x)) out.add(x);
        return out;
    }
}
