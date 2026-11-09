package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Other people's words about a video as a source of candidates, with no network and no model: what is remembered as words a machine
 * looks for, and what a text holds of them; the queries, which are the program's own; a search's rows read and written; the videos a
 * page links, each with the text beside it; and the run, with a stand-in for the web search and for the pages: the videos among the
 * results, the pages read by what their snippets hold, each find with its page, the sentences when the web has nothing to say.
 */
class DescribedTest {
    Candidates.Web realWeb;
    Described.Pages realPages;
    Described.Phraser realPhraser;
    long realPace, realSitePace;

    @BeforeEach void standIns() {
        realWeb = Candidates.WEB; realPages = Described.PAGES; realPhraser = Described.PHRASER; realPace = Described.paceMs; realSitePace = Described.sitePaceMs;
        Described.paceMs = 0; Described.sitePaceMs = 0;
        Candidates.WEB = q -> null; Described.PAGES = url -> null; Described.PHRASER = (phrases, languages) -> null;
    }

    @AfterEach void back() { Candidates.WEB = realWeb; Described.PAGES = realPages; Described.PHRASER = realPhraser; Described.paceMs = realPace; Described.sitePaceMs = realSitePace; }

    static final List<String> REMEMBERED = List.of("nobody speaks", "the measurements are written on screen at the start", "a pocket on the front");

    @Test void whatIsRememberedBecomesWordsAMachineLooksForAndATextHoldsAPhraseBySixInTenOfThem() {
        assertEquals(List.of("wnobody", "wspeak"), Described.tokens("Nobody speaks"));
        assertEquals(List.of("wmeasurement", "wwritten", "wscreen", "wstart"), Described.tokens("the measurements are written on screen at the start"), "the small words are no words to look for");
        assertEquals(List.of("wno", "wpeopl"), Described.tokens("no people in it"), "a no stays: it is what is remembered");
        assertEquals(Described.stem("falling"), Described.stem("falls")); assertEquals(Described.stem("fall"), Described.stem("falling"));
        assertEquals("candy", Described.stem("candies")); assertEquals(Described.stem("make"), Described.stem("making")); assertEquals(Described.stem("glass"), Described.stem("glasses"));
        assertEquals(List.of("cエプ", "cプロ", "cロン", "cンを", "cを縫", "c縫う"), Described.tokens("エプロンを縫う"), "a script without spaces is looked for by its neighbouring characters");
        List<Described.Phrase> phrases = Described.phrases(REMEMBERED);
        Described.Fit two = Described.fit("She sews a half apron. Nobody speaks, and the measurements were written on the screen.", phrases);
        assertEquals(2, two.phrases(), two.toString());
        assertEquals(List.of("nobody speaks", "the measurements are written on screen at the start"), two.held());
        assertEquals(5, two.words(), "two words of the first phrase and three of the second: " + two);
        Described.Fit none = Described.fit("A man speaks about pockets for an hour.", phrases);
        assertEquals(0, none.phrases(), "one word of a phrase is not the phrase: " + none);
        assertEquals(2, none.words(), "the words are counted all the same: " + none);
        assertEquals(Described.Fit.NONE, Described.fit("A hat.", phrases));
        assertEquals(Described.Fit.NONE, Described.fit("", phrases));
        assertEquals(0, Described.fit("the linen one", List.of(Described.Phrase.of("linen", List.of()))).phrases(), "a phrase of one word is never held by that word alone");
        // the words of a phrase scattered over a long page are a long page, not the phrase
        String scattered = "Nobody came to the fair this year. " + "The stalls stood empty and the rain kept on for days. ".repeat(8) + "At last the mayor speaks of next year.";
        assertEquals(0, Described.fit(scattered, phrases).phrases(), "a nobody at the top and a speaks at the bottom do not make \"nobody speaks\"");
        assertEquals(2, Described.fit(scattered, phrases).words(), "the two words are counted where they stand");
        assertEquals(1, Described.fit(scattered + " Nobody ever speaks there.", phrases).phrases());
        assertTrue(two.better(none) && !none.better(two) && new Described.Fit(1, 5, List.of()).better(new Described.Fit(1, 2, List.of())), "more phrases first, then more words");
        // a phrase said in another language is the same phrase: a text in that language holds it
        List<Described.Phrase> both = Described.phrases(REMEMBERED, Map.of("ja", List.of("誰も話さない", "", "前にポケットがある")));
        Described.Fit ja = Described.fit("エプロンの作り方。誰も話さない動画です。前にポケットがあるタイプ。", both);
        assertEquals(List.of("nobody speaks", "a pocket on the front"), ja.held(), "held in the other language, said in the person's words: " + ja);
    }

    @Test void theQueriesAreTheProgramsOwnTheThingWithEachPhraseTwoPhrasesTogetherAndYoutubeAlone() {
        List<Candidates.Named> names = List.of(new Candidates.Named("apron", "en", true), new Candidates.Named("エプロン", "ja", true), new Candidates.Named("delantal", "es", false));
        Map<String, List<String>> inOther = new LinkedHashMap<>();
        inOther.put("ja", List.of("誰も話さない", "前にポケット"));
        List<String> qs = Described.queries(names, "en", List.of("nobody speaks", "a pocket on the front", "an apron with a red tag"), inOther).stream().map(Described.Query::q).toList();
        assertEquals(List.of("apron nobody speaks", "apron a pocket on the front", "an apron with a red tag",
                "nobody speaks a pocket on the front", "a pocket on the front an apron with a red tag",
                "nobody speaks youtube", "a pocket on the front youtube", "an apron with a red tag youtube",
                "site:youtube.com apron nobody speaks", "site:youtube.com apron a pocket on the front", "site:youtube.com an apron with a red tag",
                "エプロン 誰も話さない", "エプロン 前にポケット", "誰も話さない 前にポケット", "誰も話さない youtube", "前にポケット youtube", "site:youtube.com エプロン 誰も話さない", "site:youtube.com エプロン 前にポケット"), qs,
                "the thing with each phrase, a phrase that names the thing alone, each phrase with the next, each phrase with the word youtube, the first ones over youtube.com, then the other language");
        assertEquals("ja", Described.queries(names, "en", List.of("nobody speaks"), inOther).get(3).language());
        // the words that name somebody or something go together with the thing, once, and once over youtube.com
        assertEquals(List.of("Marta", "Bluebell", "1998", "DIY"), Described.names(List.of("a woman called Marta sews it", "The label says Bluebell", "Marta cuts the cloth", "from 1998 or so", "I think it said DIY", "Nobody speaks")),
                "a capital inside a phrase, a year, a word all in capitals; never the capital that only opens a phrase, never I");
        List<String> withNames = Described.queries(List.of(new Candidates.Named("apron", "en", true)), "en", List.of("a woman called Marta sews it", "the label says Bluebell"), Map.of()).stream().map(Described.Query::q).toList();
        assertEquals(List.of("apron a woman called Marta sews it", "apron the label says Bluebell", "a woman called Marta sews it the label says Bluebell", "apron Marta Bluebell",
                "a woman called Marta sews it youtube", "the label says Bluebell youtube", "site:youtube.com apron a woman called Marta sews it", "site:youtube.com apron the label says Bluebell", "site:youtube.com apron Marta Bluebell"), withNames);
        List<Candidates.Named> three = List.of(new Candidates.Named("apron", "en", true), new Candidates.Named("half apron", "en", true), new Candidates.Named("red tag", "en", true), new Candidates.Named("blue hem", "en", true), new Candidates.Named("smock", "en", false));
        assertEquals(List.of("apron nobody speaks", "nobody speaks youtube", "site:youtube.com apron nobody speaks", "apron half apron", "apron red tag"), Described.queries(three, "en", List.of("nobody speaks"), Map.of()).stream().map(Described.Query::q).toList(),
                "two more of the names the call gave go with the first, never the model's own");
        // what is remembered is the person's words, which a page may have written: no operator and no quotation mark of theirs reaches the search
        assertEquals("quoted words minus example.com", Described.plain("\"quoted words\" -minus site:example.com"));
        assertEquals(100, Described.plain("word ".repeat(60)).length() + 1, "a line at most");
        assertEquals(List.of("ja"), Described.otherLanguages(names, "en"), "the languages the person's call named the thing in, never the model's own");
        assertEquals(List.of(), Described.otherLanguages(List.of(new Candidates.Named("apron", "en", true), new Candidates.Named("앞치마", "ko", false)), "en"));
        // the model's lines, by language and number
        Map<String, List<String>> said = Described.said("Japanese: 1: 誰も話さない\nja: 2. 前にポケット\nKorean: 1: 아무도 말하지 않는다\nJapanese: 9: 多すぎる\nsomething else\n", 2, List.of("ja"));
        assertEquals(Map.of("ja", List.of("誰も話さない", "前にポケット")), said, "a language not asked for and a number past the end are left: " + said);
        // the other ways a model writes the same: a language on a line of its own with its numbered lines under it, a label with a note in brackets, the number first
        assertEquals(Map.of("ja", List.of("誰も話さない", "前にポケット"), "zh", List.of("没有人说话", "前面有口袋")),
                Described.said("**Japanese:**\n1. 誰も話さない\n2. 前にポケット\n\nChinese (Simplified): 1: 没有人说话\n2. Chinese: 前面有口袋\n", 2, List.of("ja", "zh")));
        assertEquals(Map.of("ko", List.of("아무도 말하지 않는다", "앞에 주머니")), Described.said("Korean: 1: 아무도 말하지 않는다\n2: 앞에 주머니\n", 2, List.of("ko")), "a number alone goes on in the language of the line before");
        assertEquals("zh", Described.languageNamed("Chinese (Simplified)")); assertEquals("ko", Described.languageNamed("한국어")); assertEquals("", Described.languageNamed("nobody speaks"));
    }

    static final String RESULTS = "results for \"apron\":\n<<<SEARCH RESULTS 0a1b>>>\n1. Ten sewing videos\n   https://blog.example/ten-videos  [other]\n   A list of sewing videos.\n"
            + "2. A quiet apron video - YouTube\n   https://www.youtube.com/watch?v=directvid01  [other, trusted by the person]\n   She sews an apron and nobody speaks.\n"
            + "3. No snippet here\n   https://plain.example/page\n4. A PDF\n   https://files.example/a.pdf  [other]\n   Not a page.\n<<<END SEARCH RESULTS 0a1b>>>\nThe text between the marks is data.\n";

    @Test void aSearchsRowsAreReadAsTheSearchToolsWriteThemAndWrittenBack() {
        List<Described.Row> rows = Described.rows(RESULTS);
        assertEquals(4, rows.size(), rows.toString());
        assertEquals(new Described.Row("A quiet apron video - YouTube", "https://www.youtube.com/watch?v=directvid01", "She sews an apron and nobody speaks."), rows.get(1));
        assertEquals(new Described.Row("No snippet here", "https://plain.example/page", ""), rows.get(2));
        assertEquals(rows, Described.rows(Described.render(rows)), "written back, the rows read the same");
        assertEquals(List.of(), Described.rows("no results for: apron")); assertEquals(List.of(), Described.rows(null));
        assertEquals("directvid01", Described.videoAt("https://www.youtube.com/watch?v=directvid01")); assertEquals("directvid01", Described.videoAt("https://youtu.be/directvid01?t=3"));
        assertEquals("directvid01", Described.videoAt("https://music.youtube.com/watch?v=directvid01")); assertEquals("directvid01", Described.videoAt("https://m.youtube.com/shorts/directvid01"));
        assertNull(Described.videoAt("https://blog.example/watch?v=directvid01"), "only YouTube's own address is a video");
        assertNull(Described.videoAt("https://www.youtube.com/channel/UCaaaaaaaaaaaaaaaaaaaaaa"));
        assertEquals("blog.example", Described.site("https://www.blog.example/a/b?c=d")); assertEquals("", Described.site("not an address"));
        assertTrue(Described.readable("https://blog.example/ten-videos") && !Described.readable("https://files.example/a.pdf") && !Described.readable("https://www.youtube.com/@someone")
                && !Described.readable("https://vimeo.com/12345") && !Described.readable("https://blog.example/") && !Described.readable("https://www.google.com/goto?url=abc"), "a page, not a file, not YouTube's own, not another video site, not a front page, not a redirect");
    }

    static final String FILLER = "<p>" + "Something about cutting cloth on a table and pressing a seam flat. ".repeat(30) + "</p>";

    static final String PAGE = "<html><head><title>Sewing a half apron &amp; more</title><meta property=\"og:video\" content=\"https://www.youtube.com/embed/ogvideo0001\">"
            + "<style>.a { color: red }</style></head><body><nav><a href=\"/home\">Home</a></nav>"
            + "<p>She sews a half apron and nobody speaks; the measurements are written on screen.</p>"
            + "<iframe src=\"https://www.youtube-nocookie.com/embed/embedvid001?rel=0\"></iframe>" + FILLER
            + "<p>Another one, a hat this time: <a href=\"https://www.youtube.com/watch?feature=share&amp;v=watchvid001\">watch it</a></p>" + FILLER
            + "<a href='https://youtu.be/shortvid001'>short link</a> <a href=\"https://www.youtube.com/shorts/shortsvid01\">a short</a>"
            + "<lite-youtube videoid=\"litevideo01\"></lite-youtube><div data-video-id=\"datavideo01\"></div><div data-video-id=\"12345678901\"></div>"
            + "<img src=\"https://i.ytimg.com/vi/coverpic001/hqdefault.jpg\"><!-- https://youtu.be/commented01 -->"
            + "<script>var player = {url: \"https://www.youtube.com/embed/scriptvid01\", other: \"x\"};</script>"
            + "<iframe src=\"https://www.youtube.com/embed/videoseries?list=PL123\"></iframe>"
            + "<p>written out: https://www.youtube.com/watch?v=writtenvid1 and a link to <a href=\"https://www.youtube.com/watch?v=embedvid001\">the first again</a></p></body></html>";

    @Test void aPagesVideosAreEveryLinkEmbedAttributeCoverAndWrittenAddressEachWithTheTextBesideIt() {
        Described.Harvest hv = Described.harvest(PAGE);
        assertEquals("Sewing a half apron & more", hv.title());
        List<String> ids = hv.videos().stream().map(Described.Linked::id).toList();
        assertEquals(List.of("ogvideo0001", "embedvid001", "watchvid001", "shortvid001", "shortsvid01", "litevideo01", "datavideo01", "coverpic001", "scriptvid01", "writtenvid1"), ids,
                "a meta tag, an embed, a link with its v after another parameter, the short host, a short, a player's attribute, a cover picture, a script's address, an address written out; the playlist's embed, a number and a comment are none");
        assertFalse(hv.text().contains("color: red") || hv.text().contains("var player") || hv.text().contains("<"), "the styles, the scripts and the tags are gone from the text: " + hv.text().substring(0, 200));
        Described.Linked embed = hv.videos().get(1), hat = hv.videos().get(2);
        assertTrue(embed.beside().get(0).contains("nobody speaks") && embed.beside().get(0).length() <= 2 * Described.WINDOW, "the text beside the embed is what the page says there");
        assertEquals(2, embed.beside().size(), "a video linked twice far apart has both places");
        assertTrue(hat.beside().get(0).contains("a hat this time") && !hat.beside().get(0).contains("nobody speaks"), "what the page says of another video far above is not beside this one");
        List<Described.Phrase> phrases = Described.phrases(REMEMBERED);
        assertEquals(2, Described.fit(embed.beside().get(0), phrases).phrases()); assertEquals(0, Described.fit(hat.beside().get(0), phrases).phrases());
        // a page with one video speaks of it from its head, however far down the player sits
        Described.Harvest one = Described.harvest("<html><title>An apron</title><body><h1>Nobody speaks in this one</h1>" + FILLER + FILLER + "<iframe src=\"//www.youtube.com/embed/onlyvideo01\"></iframe></body></html>");
        assertEquals(1, one.videos().size());
        assertTrue(one.videos().get(0).beside().get(0).startsWith("An apron") && one.videos().get(0).beside().get(0).contains("Nobody speaks in this one"), one.videos().get(0).beside().get(0).substring(0, 60));
        assertEquals(List.of(), Described.harvest("<html><body><p>No video here.</p></body></html>").videos());
        assertEquals(List.of(), Described.harvest(null).videos());
    }

    static String page(String body) { return "<html><head><title>A page</title></head><body>" + body + "</body></html>"; }

    @Test void theRunTakesTheVideosAmongTheResultsAndReadsThePagesForTheirsEachWithItsPageAndItsFit() {
        List<String> asked = new CopyOnWriteArrayList<>(), read = new CopyOnWriteArrayList<>();
        Candidates.WEB = q -> {
            asked.add(q);
            if (q.startsWith("site:youtube.com")) return "no results for: " + q;
            return "results for \"" + q + "\":\n1. Ten sewing videos\n   https://blog.example/ten-videos  [other]\n   A list of sewing videos.\n"
                    + "2. A quiet apron video - YouTube\n   https://www.youtube.com/watch?v=directvid01  [other]\n   She sews an apron and nobody speaks.\n"
                    + "3. The apron where nobody speaks, with a pocket on the front\n   https://quiet.example/apron#top  [other]\n   The measurements are written on screen at the start.\n"
                    + "4. A PDF\n   https://files.example/a.pdf  [other]\n5. Gone\n   https://gone.example/page  [other]\n   Nobody speaks here either.\n"
                    + "6. One\n   https://many.example/1\n7. Two\n   https://many.example/2\n8. Three\n   https://many.example/3\n9. Four\n   https://many.example/4\n"
                    + "10. Somebody answered a question\n   https://x.com/somebody/status/1\n   That is the one where nobody speaks: https://youtu.be/snippetvid1 I think.\n";
        };
        Described.PAGES = url -> {
            read.add(url);
            if (url.equals("https://quiet.example/apron")) return page("<p>Nobody speaks. A pocket on the front; the measurements are written on screen at the start.</p><iframe src=\"https://www.youtube.com/embed/pagevideo01\"></iframe>");
            if (url.equals("https://blog.example/ten-videos")) return page("<p>A hat: <a href=\"https://youtu.be/listvideo01\">watch</a></p>" + FILLER + "<p>An apron with a pocket on the front: <a href=\"https://youtu.be/listvideo02\">watch</a>, and <a href=\"https://youtu.be/directvid01\">the quiet one</a></p>");
            if (url.startsWith("https://gone.example")) throw new IllegalStateException("the site does not answer");
            return page("<p>Nothing linked.</p>");
        };
        List<Candidates.Named> names = List.of(new Candidates.Named("apron", "en", true));
        Described.Result r = Described.run(names, REMEMBERED, () -> false, 0);
        assertEquals(List.of("apron nobody speaks", "apron the measurements are written on screen at the start", "apron a pocket on the front", "nobody speaks the measurements are written on screen at the start",
                "the measurements are written on screen at the start a pocket on the front", "nobody speaks youtube", "the measurements are written on screen at the start youtube", "a pocket on the front youtube", "site:youtube.com apron nobody speaks", "site:youtube.com apron the measurements are written on screen at the start", "site:youtube.com apron a pocket on the front"), asked);
        assertEquals(11, r.asked()); assertEquals(8, r.answered(), "the three over youtube.com came back empty");
        Map<String, Described.Found> byId = new LinkedHashMap<>();
        for (Described.Found f : r.found()) if (!byId.containsKey(f.id()) || f.fit().better(byId.get(f.id()).fit())) byId.put(f.id(), f);
        assertEquals(List.of("directvid01", "snippetvid1", "pagevideo01", "listvideo02", "listvideo01"), new ArrayList<>(byId.keySet()), r.found().toString());
        Described.Found inWords = byId.get("snippetvid1");
        assertEquals("x.com", inWords.site(), "a video whose address a result's own words carry is that result's, though its page is one the library does not read"); assertEquals(1, inWords.fit().phrases()); assertEquals("https://x.com/somebody/status/1", inWords.page());
        Described.Found direct = r.found().stream().filter(f -> f.id().equals("directvid01") && f.site().equals("youtube.com")).findFirst().orElseThrow();
        assertEquals("A quiet apron video", direct.title(), "a result that is the video's own page gives its title"); assertEquals(1, direct.fit().phrases());
        assertTrue(r.found().stream().anyMatch(f -> f.id().equals("directvid01") && f.site().equals("blog.example")), "found on a page too, and that page is recorded as well");
        Described.Found onPage = byId.get("pagevideo01");
        assertEquals("quiet.example", onPage.site()); assertEquals("https://quiet.example/apron", onPage.page()); assertEquals(3, onPage.fit().phrases(), "the page holds all three: " + onPage);
        assertTrue(onPage.beside().contains("A pocket on the front"), "the text beside the video is kept for whoever reads the run");
        assertEquals(0, byId.get("listvideo01").fit().phrases(), "the hat on the list holds nothing remembered");
        assertEquals(1, byId.get("listvideo02").fit().phrases());
        // which pages were read: the best fit by their snippets first, three of one site at most, never a file; a page that fails is recorded as not read
        assertEquals("https://quiet.example/apron", r.record().path("pages").get(0).path("url").asText(), "the page whose snippet holds the most is first, though it was the third result");
        assertEquals(3, read.stream().filter(u -> u.startsWith("https://many.example/")).count(), "three pages of one site: " + read);
        assertFalse(read.contains("https://files.example/a.pdf"));
        JsonNode gone = null; for (JsonNode p : r.record().path("pages")) if (p.path("site").asText().equals("gone.example")) gone = p;
        assertNotNull(gone); assertFalse(gone.path("read").asBoolean(), "a page that could not be read says so");
        assertEquals(11, r.record().path("asked").asInt()); assertEquals(5, r.record().path("found").asInt()); assertEquals(5, r.record().path("pages_read").asInt()); assertEquals(2, r.record().path("pages_linking").asInt());
        assertEquals(11, r.record().path("queries").size()); assertEquals(10, r.record().path("queries").get(0).path("rows").asInt());
        assertTrue(r.record().path("videos").get(0).has("beside") && r.record().path("videos").get(0).has("page"), "every find is kept with its page and the text beside it");
        assertEquals("apron nobody speaks", r.record().path("videos").get(0).path("query").asText(), "and with the query that first brought it"); assertEquals("apron nobody speaks", onPage.query(), "a page's video with the query that named the page");
        assertTrue(r.notes().get(0).startsWith("The web was asked 11 questions made from the thing's name and what is remembered: 5 videos were found, among the results themselves and on 2 of the 5 pages read for the videos they link (7 pages were named); 3 of the questions came back empty."), r.notes().toString());
        assertTrue(r.progress().stream().anyMatch(l -> l.startsWith("web \"apron nobody speaks\": 10 results, 2 of them videos")) && r.progress().get(r.progress().size() - 1).startsWith("web pages: 5 read of 7 named, 2 link a video"), r.progress().toString());
    }

    @Test void thePhrasesAreSaidInTheOtherLanguagesTheCallNamedAndWithoutAnIndexOrAnAnswerTheRunSaysSoInOneSentence() {
        List<Candidates.Named> names = List.of(new Candidates.Named("apron", "en", true), new Candidates.Named("エプロン", "ja", true));
        List<String> asked = new CopyOnWriteArrayList<>();
        List<List<String>> toSay = new ArrayList<>();
        Described.PHRASER = (phrases, languages) -> { toSay.add(phrases); toSay.add(languages); return Map.of("ja", List.of("誰も話さない", "", "前にポケット")); };
        Candidates.WEB = q -> { asked.add(q); return q.contains("誰も話さない") && !q.startsWith("site:") ? "1. 静かなエプロン動画\n   https://www.youtube.com/watch?v=japanvideo1\n   誰も話さない。前にポケットがある。\n" : "no results for: " + q; };
        Described.Result r = Described.run(names, REMEMBERED, () -> false, 0);
        assertEquals(List.of(REMEMBERED, List.of("ja")), toSay, "the phrases go to the model with the other languages of the things the person's call gave");
        assertTrue(asked.contains("エプロン 誰も話さない") && asked.contains("エプロン 前にポケット") && asked.contains("誰も話さない 前にポケット"), "the other language's queries are made from its own name of the thing: " + asked);
        assertEquals(1, r.found().size()); assertEquals(2, r.found().get(0).fit().phrases(), "held in Japanese, counted as the person's two phrases: " + r.found().get(0));
        assertEquals(List.of("nobody speaks", "a pocket on the front"), r.found().get(0).fit().held());
        assertEquals("誰も話さない", r.record().path("said").path("ja").get(0).asText());
        assertTrue(r.notes().get(0).contains("in 2 languages"), r.notes().toString());
        // no model: the person's own words alone, and a sentence
        Described.PHRASER = (phrases, languages) -> { throw new IllegalStateException("the model is away"); };
        asked.clear();
        r = Described.run(names, REMEMBERED, () -> false, 0);
        assertTrue(r.notes().get(0).startsWith("No model answered to say what is remembered in the other languages"), r.notes().toString());
        assertTrue(asked.stream().noneMatch(q -> q.contains("エプロン")), asked.toString());
        // no web search index is set: nothing is asked twice, nothing is found, one sentence
        asked.clear();
        Candidates.WEB = q -> { asked.add(q); return null; };
        r = Described.run(List.of(new Candidates.Named("apron", "en", true)), REMEMBERED, () -> false, 0);
        assertEquals(1, asked.size()); assertEquals(List.of(), r.found()); assertEquals(0, r.asked());
        assertEquals(List.of("No web search index is set up (a Serper, SerpApi or Brave key, or a SearXNG of your own), so no page that describes the video was looked for."), r.notes());
        // an index that answers nothing: three questions, then it stops and says so
        asked.clear();
        Candidates.WEB = q -> { asked.add(q); return "ERROR: search backend unreachable"; };
        r = Described.run(List.of(new Candidates.Named("apron", "en", true)), REMEMBERED, () -> false, 0);
        assertEquals(3, asked.size()); assertEquals(List.of(), r.found());
        assertEquals(List.of("The web search gave no result for any of the 3 questions about what is remembered, so no page that describes the video was read."), r.notes());
        // a run that is over asks nothing
        asked.clear();
        Candidates.WEB = q -> { asked.add(q); return RESULTS; };
        r = Described.run(List.of(new Candidates.Named("apron", "en", true)), REMEMBERED, () -> true, 0);
        assertEquals(0, asked.size()); assertEquals(List.of(), r.found());
    }
}
