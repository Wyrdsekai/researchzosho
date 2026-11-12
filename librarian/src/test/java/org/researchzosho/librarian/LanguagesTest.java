package org.researchzosho.librarian;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The language of a document, worked out from its own text: a paragraph of each language the program tells apart, a German paper with an
 * English abstract, a short title the words cannot settle, a file's own tag where the text agrees or is too short and not where it plainly
 * says another, the person's word over all of it; the refusal "already in …" only when the answer is clear, and the plan saying what the
 * program took when it is not. The sentences are made up here.
 */
class LanguagesTest {
    static final Map<String, String> PARAGRAPHS = new LinkedHashMap<>();
    static {
        PARAGRAPHS.put("en", "The committee met again on Tuesday and decided that the new rules would apply to all of the schools in the region from the start of the next year.");
        PARAGRAPHS.put("de", "Der Ausschuss hat sich am Dienstag noch einmal getroffen und beschlossen, dass die neuen Regeln vom Beginn des nächsten Jahres an für alle Schulen der Region gelten.");
        PARAGRAPHS.put("fr", "Le comité s'est réuni de nouveau mardi et a décidé que les nouvelles règles s'appliqueront à toutes les écoles de la région dès le début de l'année prochaine.");
        PARAGRAPHS.put("es", "El comité se reunió de nuevo el martes y decidió que las nuevas normas se aplicarán a todas las escuelas de la región desde el comienzo del año próximo.");
        PARAGRAPHS.put("pt", "O comitê voltou a se reunir na terça-feira e decidiu que as novas regras serão aplicadas a todas as escolas da região a partir do início do próximo ano.");
        PARAGRAPHS.put("it", "Il comitato si è riunito di nuovo martedì e ha deciso che le nuove regole si applicheranno a tutte le scuole della regione dall'inizio del prossimo anno.");
        PARAGRAPHS.put("nl", "De commissie is dinsdag opnieuw bijeengekomen en heeft besloten dat de nieuwe regels vanaf het begin van het volgende jaar voor alle scholen in de regio gelden.");
        PARAGRAPHS.put("pl", "Komisja zebrała się ponownie we wtorek i zdecydowała, że nowe zasady będą obowiązywać we wszystkich szkołach w regionie od początku przyszłego roku.");
        PARAGRAPHS.put("sv", "Kommittén träffades igen på tisdagen och beslutade att de nya reglerna ska gälla för alla skolor i regionen från början av nästa år, och det är inte klart än.");
        PARAGRAPHS.put("da", "Udvalget mødtes igen om tirsdagen og besluttede, at de nye regler skal gælde for alle skoler i regionen fra begyndelsen af næste år, og at de også skal gælde for de private, hvad der blev sagt efter mødet.");
        PARAGRAPHS.put("no", "Komiteen møttes igjen på tirsdag og bestemte at de nye reglene skal gjelde for alle skolene i regionen fra begynnelsen av neste år, og at de også skal gjelde for de private, etter det som ble sagt mellom dem.");
        PARAGRAPHS.put("fi", "Valiokunta kokoontui uudelleen tiistaina ja päätti, että uudet säännöt koskevat kaikkia alueen kouluja ensi vuoden alusta alkaen, ja se on myös tärkeää.");
        PARAGRAPHS.put("cs", "Výbor se v úterý znovu sešel a rozhodl, že nová pravidla budou platit pro všechny školy v regionu od začátku příštího roku, a to i pro ty soukromé.");
        PARAGRAPHS.put("ro", "Comitetul s-a întrunit din nou marți și a decis că noile reguli se vor aplica tuturor școlilor din regiune de la începutul anului viitor, care este aproape.");
        PARAGRAPHS.put("hu", "A bizottság kedden újra ülésezett, és úgy döntött, hogy az új szabályok a következő év elejétől a régió minden iskolájára vonatkoznak, de ez még nem végleges.");
        PARAGRAPHS.put("tr", "Komite salı günü yeniden toplandı ve yeni kuralların gelecek yılın başından itibaren bölgedeki bütün okullar için geçerli olacağına karar verdi, ama bu daha kesin değil.");
        PARAGRAPHS.put("id", "Komite bertemu lagi pada hari Selasa dan memutuskan bahwa aturan yang baru akan berlaku untuk semua sekolah di wilayah ini mulai awal tahun depan.");
        PARAGRAPHS.put("vi", "Ủy ban đã họp lại vào thứ Ba và quyết định rằng các quy định mới sẽ được áp dụng cho tất cả các trường học trong khu vực từ đầu năm sau.");
        PARAGRAPHS.put("ru", "Комитет снова собрался во вторник и решил, что новые правила будут действовать для всех школ региона с начала следующего года, и это уже известно.");
        PARAGRAPHS.put("uk", "Комітет знову зібрався у вівторок і вирішив, що нові правила діятимуть для всіх шкіл регіону з початку наступного року, і це вже відомо.");
        PARAGRAPHS.put("bg", "Комитетът се събра отново във вторник и реши, че новите правила ще се прилагат за всички училища в региона от началото на следващата година.");
        PARAGRAPHS.put("sr", "Комитет се поново састао у уторак и одлучио да ће нова правила важити за све школе у региону од почетка следеће године, што је већ познато.");
        PARAGRAPHS.put("hr", "Odbor se ponovno sastao u utorak i odlučio da će nova pravila vrijediti za sve škole u regiji od početka sljedeće godine, što je već poznato.");
        PARAGRAPHS.put("ja", "委員会は火曜日に再び集まり、新しい規則を来年の初めから地域のすべての学校に適用することを決めた。");
        PARAGRAPHS.put("zh", "委员会周二再次开会，决定从明年年初起对该地区所有学校适用新的规定。");
        PARAGRAPHS.put("ko", "위원회는 화요일에 다시 모여 새로운 규칙을 내년 초부터 지역의 모든 학교에 적용하기로 결정했다.");
        PARAGRAPHS.put("ar", "اجتمعت اللجنة مرة أخرى يوم الثلاثاء وقررت أن تطبق القواعد الجديدة على جميع المدارس في المنطقة من بداية العام المقبل.");
        PARAGRAPHS.put("fa", "کمیته روز سه‌شنبه دوباره تشکیل جلسه داد و تصمیم گرفت که قوانین جدید از آغاز سال آینده برای همه مدارس منطقه اجرا شود.");
        PARAGRAPHS.put("he", "הוועדה התכנסה שוב ביום שלישי והחליטה שהכללים החדשים יחולו על כל בתי הספר באזור מתחילת השנה הבאה.");
        PARAGRAPHS.put("th", "คณะกรรมการประชุมอีกครั้งในวันอังคารและตัดสินใจว่ากฎใหม่จะใช้กับโรงเรียนทุกแห่งในภูมิภาคตั้งแต่ต้นปีหน้า");
        PARAGRAPHS.put("hi", "समिति मंगलवार को फिर से मिली और तय किया कि नए नियम अगले साल की शुरुआत से क्षेत्र के सभी स्कूलों पर लागू होंगे।");
        PARAGRAPHS.put("el", "Η επιτροπή συνεδρίασε ξανά την Τρίτη και αποφάσισε ότι οι νέοι κανόνες θα ισχύουν για όλα τα σχολεία της περιοχής από την αρχή του επόμενου έτους.");
    }

    @Test void aParagraphOfEachLanguage() {
        StringBuilder wrong = new StringBuilder();
        for (var e : PARAGRAPHS.entrySet()) {
            Languages.Guess g = Languages.of(e.getValue());
            if (!g.code().equals(e.getKey())) wrong.append(e.getKey()).append(" read as ").append(g).append('\n');
        }
        assertEquals("", wrong.toString());
        // the script alone is enough where it is one language's; the words are needed where several share it
        assertEquals(Languages.Guess.SCRIPT, Languages.of(PARAGRAPHS.get("ko")).by());
        assertEquals(Languages.Guess.WORDS, Languages.of(PARAGRAPHS.get("de")).by());
        assertTrue(Languages.of(PARAGRAPHS.get("de")).clear() && Languages.of(PARAGRAPHS.get("en")).clear() && Languages.of(PARAGRAPHS.get("pl")).clear());
    }

    @Test void theSharedGuessKeepsEnglishWhereTheWordsAreNotClear() {
        assertEquals("de", Translate.languageOf(PARAGRAPHS.get("de")), "a German paragraph is German, no longer English");
        assertEquals("ru", Translate.languageOf(PARAGRAPHS.get("ru")));
        assertEquals("en", Translate.languageOf("Homebox"), "a word that says nothing stays English, as before");
        assertEquals("ja", Translate.languageOf("高市早苗について：経歴と現職")); assertEquals("zh", Translate.languageOf("北京大学"));
    }

    static LibraryStore store(Path tmp) throws IOException { LibraryStore s = new LibraryStore(tmp.resolve("lib")); s.init(); return s; }

    static Path md(Path dir, String name, String text) throws IOException { Files.createDirectories(dir); Path f = dir.resolve(name); Files.writeString(f, text, StandardCharsets.UTF_8); return f; }

    static final String GERMAN_PAPER = "# Die Ordnung der Schulen\n\n"
            + "Abstract: This paper describes how the schools of the region were ordered after the reform, and what the teachers said about it.\n\n"
            + "Die Schulen der Region wurden nach der Reform neu geordnet, und die Lehrer haben sich dazu in mehreren Briefen geäußert, die hier zum ersten Mal gelesen werden.\n\n"
            + "In den ersten Jahren war die Zahl der Klassen kleiner als vorher, weil viele Familien in die Stadt gezogen sind und die Dörfer leer wurden.\n\n"
            + "Die Lehrer schrieben, dass sie mit der neuen Ordnung nicht zufrieden waren, aber sie haben die Arbeit trotzdem gut gemacht.\n\n"
            + "Am Ende wird gezeigt, wie die Schulen sich bis heute verändert haben und was von der Reform noch übrig ist.\n";

    @Test void aGermanPaperWithAnEnglishAbstractIsGermanAndGoesIntoEnglish(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path in = md(tmp.resolve("docs"), "schulen.md", GERMAN_PAPER);
        DocUnits.Doc d = DocUnits.cut(Files.readAllBytes(in), "schulen.md");
        assertEquals("de", d.lang()); assertTrue(d.guess().clear(), d.guess().toString());
        Translation.Plan plan = Translation.plan(store, in, "English", "", "quick", "stand-in", "");
        assertEquals("de", plan.from(), "no longer refused as already English");
        assertEquals("", plan.languageSaid(Chrome.ENGLISH), "clear: the plan need not say it");
        // the same paper into German: refused, since the words say plainly it is German
        Translation.Refused r = assertThrows(Translation.Refused.class, () -> Translation.plan(store, in, "German", "", "quick", "stand-in", ""));
        assertEquals("\"Die Ordnung der Schulen\" is written in German already.", r.getMessage());
        assertTrue(r.in(Chrome.in("ja")).contains("ドイツ語"), r.in(Chrome.in("ja")));
    }

    @Test void aShortTitleIsNotRefusedAndThePlanSaysWhatItTook(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path in = md(tmp.resolve("docs"), "titel.md", "# Die Verwandlung\n");
        DocUnits.Doc d = DocUnits.cut(Files.readAllBytes(in), "titel.md");
        assertFalse(d.guess().clear(), "two words cannot settle it: " + d.guess());
        Translation.Plan intoEnglish = Translation.plan(store, in, "English", "", "quick", "stand-in", "");
        assertTrue(intoEnglish.saidFor().contains("it is taken to be written in " + Chrome.ENGLISH.languageName(intoEnglish.from())) && intoEnglish.saidFor().contains("(if it is in another language, name it: --from <language> on the command line, \"from <language>\" in the chat)"), intoEnglish.saidFor());
        Translation.Plan intoGerman = assertDoesNotThrow(() -> Translation.plan(store, in, "German", "", "quick", "stand-in", ""), "never refused on a guess");
        assertNotEquals("de", intoGerman.from(), "the person asking for German is the evidence: the runner-up is taken");
        assertTrue(intoGerman.said().contains("it is taken to be written in"), intoGerman.said());
        assertTrue(intoGerman.said(Chrome.in("ja")).contains("で書かれているものとして扱いますが"), intoGerman.said(Chrome.in("ja")));
    }

    @Test void thePersonsWordWins(@TempDir Path tmp) throws Exception {
        LibraryStore store = store(tmp);
        Path in = md(tmp.resolve("docs"), "schulen.md", GERMAN_PAPER);
        Translation.Plan dutch = Translation.plan(store, in, "English", "", "quick", "stand-in", "", "", "Dutch");
        assertEquals("nl", dutch.from()); assertEquals("", dutch.languageSaid(Chrome.ENGLISH));
        assertDoesNotThrow(() -> Translation.plan(store, in, "German", "", "quick", "stand-in", "", "", "Dutch"), "said to be Dutch, it is not refused as German");
        assertThrows(Translation.Refused.class, () -> Translation.plan(store, in, "Dutch", "", "quick", "stand-in", "", "", "nl"), "said to be Dutch, into Dutch is refused");
        // the run takes the person's word: the note says so, and the pair is theirs
        TranslationTest.Script sc = new TranslationTest.Script();
        Translation.Outcome o = Translation.run(store, in, "en", "", "quick", tmp.resolve("out.en.md"), null, TranslationTest.seats(sc), null, () -> false, Checkpoint.NONE, "", "", "Dutch");
        assertEquals("nl", o.from());
        assertTrue(Files.readString(o.note()).contains("(md, written in Dutch, as the person said)"), Files.readString(o.note()));
        // the chat reads it, in English and in Japanese; the command line's flag is --from
        assertEquals("de", TranslateTurn.parse("translate this file from German into English").from());
        assertEquals("this file", TranslateTurn.parse("translate this file from German into English").what());
        assertEquals("nl", TranslateTurn.fromIn("このファイルをオランダ語から英語に翻訳して"));
        assertEquals("", TranslateTurn.fromIn("translate the notes from the meeting into English"), "a word after from that names no language is no language");
        assertEquals("nl", Translation.args(in, "schulen.md", "en", "", "quick", null, null, "cli", "", "", "", "", "Dutch").path("from").asText());
        assertTrue(TranslateCli.USAGE.contains("[--from <language>]"));
    }

    @Test void aFilesOwnTagIsTakenWhenTheTextAgreesOrCannotSay(@TempDir Path tmp) throws Exception {
        // a Word file tagged German, its text a title: German, from the file
        DocUnits.Doc docx = DocUnits.docx(docx("de-DE", "Die Verwandlung"), "verwandlung");
        assertEquals("de", docx.lang()); assertEquals(Languages.Guess.FILE, docx.guess().by()); assertTrue(docx.guess().clear());
        // an EPUB tagged English whose chapters are plainly German: the text wins over a template's tag
        DocUnits.Doc epub = DocUnits.epub(epub("en", GERMAN_PAPER.replaceAll("(?m)^Abstract:.*$", "")), "schulen");
        assertEquals("de", epub.lang(), epub.guess().toString()); assertEquals(Languages.Guess.WORDS, epub.guess().by());
        // a PDF whose catalogue names German, its text a short title
        DocUnits.Doc pdf = DocUnits.cut(pdf("de-DE", "Die Verwandlung"), "verwandlung.pdf", PdfReading.Options.preflightOnly());
        assertEquals("de", pdf.lang(), pdf.guess().toString()); assertEquals(Languages.Guess.FILE, pdf.guess().by());
        assertEquals("de", Languages.declared("ger")); assertEquals("pt", Languages.declared("pt_BR")); assertEquals("", Languages.declared("x-unknown"));
    }

    static byte[] docx(String language, String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            put(z, "[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>");
            put(z, "word/document.xml", "<?xml version=\"1.0\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>" + text + "</w:t></w:r></w:p></w:body></w:document>");
            put(z, "docProps/core.xml", "<?xml version=\"1.0\"?><cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:language>" + language + "</dc:language></cp:coreProperties>");
        }
        return out.toByteArray();
    }

    static byte[] epub(String language, String markdown) throws IOException {
        StringBuilder body = new StringBuilder();
        for (String p : markdown.split("\n\n")) { String t = p.strip(); if (t.isEmpty()) continue; body.append(t.startsWith("# ") ? "<h1>" + t.substring(2) + "</h1>" : "<p>" + t + "</p>"); }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            put(z, "mimetype", "application/epub+zip");
            put(z, "META-INF/container.xml", "<?xml version=\"1.0\"?><container><rootfiles><rootfile full-path=\"OPS/book.opf\"/></rootfiles></container>");
            put(z, "OPS/book.opf", "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Schulen</dc:title><dc:language>" + language + "</dc:language></metadata><manifest><item id=\"c1\" href=\"c1.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"c1\"/></spine></package>");
            put(z, "OPS/c1.xhtml", "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><body>" + body + "</body></html>");
        }
        return out.toByteArray();
    }

    static byte[] pdf(String language, String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4); doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText(); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18); cs.newLineAtOffset(72, 750); cs.showText(text); cs.endText();
            }
            doc.getDocumentCatalog().setLanguage(language);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); doc.save(out); return out.toByteArray();
        }
    }

    static void put(ZipOutputStream z, String name, String text) throws IOException { z.putNextEntry(new ZipEntry(name)); z.write(text.getBytes(StandardCharsets.UTF_8)); z.closeEntry(); }

    @BeforeEach void scorers() { TranslationScores.SCORERS = () -> new TranslationScores.Scorers(TranslationTest.labseStandIn(), null); Translation.SETUP_SAID = () -> ""; }
    @AfterEach void back() { TranslationScores.SCORERS = TranslationScores::configured; }
}
