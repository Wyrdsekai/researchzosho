package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The pages that link to a page, asked of the encyclopedia itself: the leads a person's own page does not give. */
class MentionsTest {

    @Test
    void thePagesThatLinkHereWithoutTheOnesThatLinkEverywhere() {
        List<String> asked = new ArrayList<>();
        List<String> got = Mentions.of("ja", "森田勇", url -> {
            asked.add(url);
            return url.contains("blcontinue") ? "{\"query\":{\"backlinks\":[{\"title\":\"森田事件\"},{\"title\":\"3月18日\"}]}}"
                    : "{\"continue\":{\"blcontinue\":\"0|123\"},\"query\":{\"backlinks\":[{\"title\":\"宮内大臣\"},{\"title\":\"1852年\"},{\"title\":\"日本の陸軍軍人一覧\"},{\"title\":\"南満洲鉄道\"}]}}";
        });
        assertEquals(List.of("宮内大臣", "南満洲鉄道", "森田事件"), got, "a year, a day and a list link to everybody and tell of nobody");
        assertEquals(2, asked.size(), "the list is asked for until it ends");
        assertTrue(asked.get(0).contains("bltitle=%E6%A3%AE%E7%94%B0%E5%8B%87") && asked.get(0).contains("blnamespace=0"), asked.get(0));
        assertTrue(Mentions.of("en", "Nobody", url -> { throw new IllegalStateException("HTTP 503"); }).isEmpty(), "an encyclopedia that cannot be asked loses nothing that was there before");
    }

    @Test
    void anEncyclopediaPageByItsAddress() {
        assertArrayEquals(new String[]{"ja", "森田勇 (陸軍軍人)"}, Mentions.page("https://ja.wikipedia.org/wiki/%E6%A3%AE%E7%94%B0%E5%8B%87_(%E9%99%B8%E8%BB%8D%E8%BB%8D%E4%BA%BA)"));
        assertArrayEquals(new String[]{"en", "Robert Hale"}, Mentions.page("https://en.wikipedia.org/wiki/Robert_Hale#Life"));
        assertNull(Mentions.page("https://example.org/wiki/Robert_Hale"));
        assertTrue(Mentions.everywhere("List of governors") && Mentions.everywhere("1920s in Japan") && Mentions.everywhere("June 18") && !Mentions.everywhere("Teapot Dome scandal"));
    }

    @Test
    void theModelChoosesAmongTheTitlesItWasShownAndNamesNothingItself() {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < 170; i++) titles.add("人物" + i);
        titles.add(20, "森田事件"); titles.add(160, "南海鉄道");
        List<String> prompts = new ArrayList<>();
        List<String> leads = Mentions.sift("森田勇", "1852-1928", titles, prompt -> {
            prompts.add(prompt);
            return prompt.contains("- 森田事件") ? "Sure: {\"titles\": [\"森田事件\", \"an affair I made up\"]}" : "{\"titles\": [\"南海鉄道\"]}";
        });
        assertEquals(List.of("森田事件", "南海鉄道"), leads, "a title that was not in the list is dropped");
        assertEquals(2, prompts.size(), "172 titles are read in two chunks");
        assertTrue(prompts.get(0).contains("mention 森田勇 (1852-1928)"));
    }
}
