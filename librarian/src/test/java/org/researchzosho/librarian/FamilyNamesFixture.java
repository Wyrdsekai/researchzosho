package org.researchzosho.librarian;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One family for the display tests, invented names only: 森田健二 was born 遠藤健二 in 1905 into the 遠藤 family of 広島県安芸郡, son of 遠藤正一,
 * and entered the 森田 family in 1932 as 婿養子 of its head 森田勇, marrying his daughter 森田ハル. A book calls him Morita Kenji at school in
 * 1920. 森田勇's son 森田正二 left the 森田 family in 1940, adopted as heir into the 髙橋 family, and became 髙橋正二. A second, unrelated
 * 遠藤 family has its seat in 山口県大島郡.
 */
final class FamilyNamesFixture {

    private FamilyNamesFixture() { }

    static final String Q_MUKO = "In 1932 he entered the Morita family (森田家), whose head was Morita Isamu, as mukoyōshi (婿養子) of Isamu, and married Isamu's daughter Haru.";
    static final String Q_SCHOOL = "Endo's son, Morita Kenji, went to the village school in 1920.";
    static final String Q_BORN = "健二は1905年に遠藤家に生まれた。";
    static final String Q_HEAD = "森田勇は1907年に森田家の家督を相続した。";
    static final String Q_SHOJI = "Isamu's son Morita Shōji (森田正二) left the Morita family in 1940, when he was adopted as heir into the Takahashi family (髙橋家), and became Takahashi Shōji (髙橋正二).";
    static final String Q_SHOJI_BORN = "正二は1912年に森田家に生まれた。";

    static FamilyAccount.Fact fact(String s, String r, String o, String date, String quote) { return new FamilyAccount.Fact(s, r, o, date, quote); }

    static FamilyAccount.Fact fact(String s, String r, String o, String date, String quote, Map<String, String> detail) { return new FamilyAccount.Fact(s, r, o, date, quote, detail); }

    static FamilyAccount.NameRead name(String person, String name, String family, String given, String kind, String date, String quote, String... forms) {
        return new FamilyAccount.NameRead(person, name, family, given, List.of(forms), kind, "", date, quote);
    }

    static LibraryStore family(Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        new LibrarianIndex(store, Embeddings.none()).rebuild();
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                        fact("森田健二", "adopted-by", "森田勇", "1932", Q_MUKO, Map.of("kind", "mukoyoshi")),
                        fact("森田健二", "married-to", "森田ハル", "1932", Q_MUKO),
                        fact("森田健二", "member-of", "森田家", "1932", Q_MUKO, Map.of("how", "mukoyoshi", "from", "1932")),
                        fact("森田勇", "member-of", "森田家", "1907", Q_HEAD, Map.of("how", "succession", "role", "head", "from", "1907")),
                        fact("森田健二", "born-on", "1905", "", Q_BORN),
                        fact("森田ハル", "child-of", "森田勇", "", Q_MUKO),
                        fact("森田ハル", "born-on", "1910", "", "ハルは1910年に生まれた。"),
                        fact("森田ハル", "sex", "female", "", Q_MUKO), fact("森田健二", "sex", "male", "", Q_MUKO), fact("森田勇", "sex", "male", "", Q_MUKO),
                        fact("森田正二", "child-of", "森田勇", "", Q_SHOJI),
                        fact("森田正二", "born-on", "1912", "", Q_SHOJI_BORN),
                        fact("森田正二", "member-of", "森田家", "1912", Q_SHOJI_BORN + " " + Q_SHOJI, Map.of("how", "birth", "from", "1912", "to", "1940", "left", "adoption-out")),
                        fact("森田正二", "member-of", "髙橋家", "1940", Q_SHOJI, Map.of("how", "adoption", "role", "heir", "from", "1940"))),
                List.of(), List.of(),
                List.of(name("森田健二", "遠藤健二", "遠藤", "健二", "birth", "1905", Q_BORN, "えんどう けんじ"),
                        name("森田健二", "森田健二", "森田", "健二", "mukoyoshi", "1932", Q_MUKO, "もりた けんじ", "Morita Kenji"),
                        name("森田正二", "森田正二", "森田", "正二", "birth", "1912", Q_SHOJI_BORN),
                        name("森田正二", "髙橋正二", "髙橋", "正二", "adoptive", "1940", Q_SHOJI, "Takahashi Shōji")),
                List.of(new FamilyAccount.FamilyRead("森田", "森田家", "", Q_MUKO), new FamilyAccount.FamilyRead("髙橋", "髙橋家", "", Q_SHOJI))),
                "an aunt", f -> List.of("file:///family/book.txt"), f -> List.of());
        // the book's narrative writes him by his later name for 1920: filed once his names and their forms are known
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(fact("Morita Kenji", "life-event", "went to the village school", "1920", Q_SCHOOL)), List.of()),
                "an aunt", f -> List.of("file:///family/book.txt"), f -> List.of());
        String q4 = "遠藤正一は広島の遠藤家の人である。健二は正一の子である。";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                        fact("遠藤正一", "member-of", "遠藤家", "", q4),
                        fact("森田健二", "child-of", "遠藤正一", "", q4),
                        fact("遠藤正一", "sex", "male", "", q4),
                        fact("遠藤正一", "born-on", "1875", "", "遠藤正一は1875年に生まれた。"),
                        fact("遠藤正一", "died-on", "1930", "", "遠藤正一は1930年に亡くなった。")),
                List.of(), List.of(), List.of(), List.of(new FamilyAccount.FamilyRead("遠藤", "遠藤家", "広島県安芸郡", q4))),
                "an uncle", f -> List.of("file:///family/register.txt"), f -> List.of());
        String q5 = "遠藤勇は山口の遠藤家の人である。";
        FamilyAccount.fileAsRead(store, new FamilyAccount.Read(List.of(), List.of(
                        fact("遠藤勇", "member-of", "遠藤家", "", q5),
                        fact("遠藤勇", "born-on", "1880", "", "遠藤勇は1880年に生まれた。")),
                List.of(), List.of(), List.of(), List.of(new FamilyAccount.FamilyRead("遠藤", "遠藤家", "山口県大島郡", q5))),
                "an uncle", f -> List.of("file:///family/yamaguchi.txt"), f -> List.of());
        return store;
    }
}
