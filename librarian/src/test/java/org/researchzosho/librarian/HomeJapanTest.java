package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.HomeCut.Place;
import org.researchzosho.librarian.HomeCut.Range;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A home search in Japan on stand-ins: a Japanese ask read into the cut without a model, the places (a ward, a city, a ward of a city, a
 * station, in Japanese and in English), the figures in 万円, ㎡, 坪, 築年, 徒歩分 and 階, the municipality's code from the lookup, the
 * verdicts and the summary in the language asked. No web, no model, no map.
 */
class HomeJapanTest {

    final List<String[]> looked = new CopyOnWriteArrayList<>();

    @BeforeEach void standIns() {
        HomeJapan.NEAR = (town, pref, parent, km) -> { looked.add(new String[]{town, pref, parent, String.valueOf(km)}); return new HomeJapan.Lookup("杉並区（東京都）", "杉並区", "Suginami", "13115", 35.6995, 139.6364, List.of("中野区", "武蔵野市", "練馬区")); };
        Career.DRIVE = () -> null;
    }

    @AfterEach void back() { HomeJapan.NEAR = HomeJapan::fromWikidata; Career.DRIVE = Explain::drive; }

    static Home.Listing jp(String address, String tenure, long price, String layout, double m2, int walk, int floor, String kind) {
        String[] a = HomeJapan.split(address);
        Home.Listing.Extra x = new Home.Listing.Extra("JPY", "メゾン例", layout, m2, 2014, "例線", "例駅", walk, floor);
        return new Home.Listing(CareerSources.id(address + layout + floor), "SUUMO", tenure, address, a[1], a[0], "", price, HomeJapan.rooms(layout), 0, (int) Math.round(m2 * HomeCut.SQ_FT_PER_M2), kind, "https://suumo.jp/chintai/jnc_000000000001/", 0, 0, "", "", "", "", x);
    }

    @Test void placesInJapaneseAndInEnglish() {
        assertEquals(List.of(new Place("杉並区", "", "", "")), HomeCut.places("杉並区"));
        for (String said : List.of("東京都杉並区", "杉並区（東京都）", "杉並区, 東京都", "杉並区、東京都", "東京都杉並区の荻窪駅周辺")) {
            List<Place> ps = HomeCut.places(said);
            assertEquals(1, ps.size(), said + ": " + ps);
            assertEquals(List.of("杉並区", "JP-13", "東京都", "prefecture"), List.of(ps.get(0).town(), ps.get(0).region(), ps.get(0).regionName(), ps.get(0).regionKind()), said);
            assertTrue(ps.get(0).japan());
        }
        assertEquals("杉並区, 東京都", HomeCut.places("東京都杉並区").get(0).key());
        assertEquals(HomeCut.places("東京都杉並区"), HomeCut.places(HomeCut.places("東京都杉並区").get(0).key()), "the key reads back as the same place");
        assertEquals("横浜市中区", HomeCut.places("横浜市中区").get(0).town(), "a ward of a city keeps the city");
        assertEquals(List.of("三鷹市", "武蔵野市"), HomeCut.places("三鷹市か武蔵野市").stream().map(Place::town).toList());
        Place pref = HomeCut.places("東京都").get(0);
        assertTrue(pref.regionAlone() && pref.region().equals("JP-13"), "a prefecture alone is anywhere in it");
        assertEquals("荻窪駅", HomeJapan.station("東京都杉並区の荻窪駅周辺"));
        assertEquals("", HomeJapan.station("杉並区で駅徒歩10分以内"), "the station of a walk is no station named");
        Place en = HomeCut.places("Suginami, Tokyo").get(0);
        assertEquals(List.of("Suginami", "JP-13", "Tokyo"), List.of(en.town(), en.region(), en.regionName()));
        assertEquals("Suginami, Tokyo", en.key());
        assertEquals(List.of("Suginami", "JP-13"), List.of(HomeCut.places("Suginami-ku, Tokyo, Japan").get(0).town(), HomeCut.places("Suginami-ku, Tokyo, Japan").get(0).region()));
        assertEquals(List.of("Mitaka", "Musashino"), HomeCut.places("Mitaka or Musashino, Tokyo").stream().map(Place::town).toList());
        assertEquals("Osaka", HomeCut.places("Osaka, Japan").get(0).town(), "a prefecture named alone in English is looked up as the city of its name");
        assertFalse(HomeJapan.looks("Cambridge, Massachusetts"));
        assertEquals("MA", HomeCut.places("Cambridge, Massachusetts").get(0).region(), "an American place is read as before");
        assertEquals(List.of("東京都", "杉並区", "例町３"), List.of(HomeJapan.split("東京都杉並区例町３")));
        assertEquals("例町", HomeJapan.district("例町３丁目５−２"));
        assertEquals("例町", HomeJapan.district("例町３"));
    }

    @Test void theFiguresAreReadFromJapaneseWords() {
        assertEquals(150_000, HomeJapan.yen("15万円"));
        assertEquals(44_800_000, HomeJapan.yen("4,480万円"));
        assertEquals(120_000_000, HomeJapan.yen("1億2000万円"));
        assertEquals(117_000, HomeJapan.yen("11.7万円"));
        assertEquals(100_000_000, HomeJapan.yen("１億円"));
        assertEquals(8_500, HomeJapan.yen("8,500円"));
        assertEquals("15万円", HomeJapan.yenWords(150_000));
        assertEquals("11.7万円", HomeJapan.yenWords(117_000));
        assertEquals("4,480万円", HomeJapan.yenWords(44_800_000));
        assertEquals("1億2,000万円", HomeJapan.yenWords(120_000_000));
        assertEquals("1億円", HomeJapan.yenWords(100_000_000));
        assertEquals(new Range(0, 150_000), HomeCut.range("家賃15万円以下", true));
        assertEquals(new Range(0, 50_000_000), HomeCut.range("5000万円まで", true));
        assertEquals(new Range(40_000_000, 60_000_000), HomeCut.range("4000万〜6000万円", true));
        assertEquals(new Range(40_000_000, 60_000_000), HomeCut.range("4000万円から6000万円", true));
        assertEquals(new Range(127_500, 172_500), HomeCut.range("15万円前後", true), "fifteen per cent either way");
        assertEquals(new Range(0, 120_000_000), HomeCut.range("1億2000万円以下", true));
        assertEquals(new Range(50_000_000, 0), HomeCut.range("5000万円以上", true));
        assertEquals(new Range(0, 60_000_000), HomeCut.range("under 60 million yen", true));
        assertEquals(new Range(0, 150_000), HomeCut.range("¥150,000 a month", true));
        assertEquals(new Range(2, 0), HomeJapan.roomsRange("2LDK以上"));
        assertEquals(new Range(0, 1), HomeJapan.roomsRange("1LDK以下"));
        assertEquals(new Range(2, 3), HomeJapan.roomsRange("2LDK〜3LDK"));
        assertEquals(new Range(3, 0), HomeJapan.roomsRange("3部屋以上"));
        assertEquals(1, HomeJapan.rooms("ワンルーム"));
        assertEquals(1, HomeJapan.rooms("1K"));
        assertEquals(3, HomeJapan.rooms("3SLDK"));
        assertEquals(0, HomeJapan.rooms("マンション"));
        assertEquals(Math.round(50 * HomeCut.SQ_FT_PER_M2), HomeCut.area("50㎡以上").min(), "kept in square feet for the one judge");
        assertEquals(0, HomeCut.area("50㎡以上").max());
        assertEquals(Math.round(66.1 * HomeCut.SQ_FT_PER_M2), HomeCut.area("20坪以上").min(), "a 坪 is 3.3 ㎡");
        int year = LocalDate.now().getYear();
        assertEquals(new Range(year - 10, 0), HomeCut.years("築10年以内"));
        assertEquals(new Range(0, year - 20), HomeCut.years("築20年以上"));
        assertEquals(new Range(year - 1, 0), HomeCut.years("新築"));
        assertEquals(new Range(2000, 0), HomeCut.years("2000年以降"));
        assertEquals(new Range(0, 10), HomeJapan.walk("駅徒歩10分以内"));
        assertEquals(new Range(0, 7), HomeJapan.walk("徒歩7分"));
        assertEquals(new Range(0, 10), HomeJapan.walk("within 10 minutes of a station"));
        assertNull(HomeJapan.walk("a yard"));
        assertEquals(new Range(2, 0), HomeJapan.floor("2階以上"));
        assertEquals(new Range(2, 0), HomeJapan.floor("1階以外"));
        assertEquals(new Range(0, 3), HomeJapan.floor("3階以下"));
        assertEquals(new Range(5, 5), HomeJapan.floor("5階"));
        assertNull(HomeJapan.floor("3階建"), "the building's storeys are no floor wanted");
        assertEquals(new Range(2, 0), HomeJapan.floor("2nd floor or higher"));
        HomeCut.PerArea per = HomeCut.perAreaOf("㎡単価80万円以下");
        assertEquals(new Range(0, 800_000), per.range()); assertEquals(HomeCut.SQ_M, per.unit());
        assertEquals(Math.round(3_000_000 / HomeJapan.TSUBO_M2), HomeCut.perAreaOf("坪単価300万円以下").range().max(), "a price per 坪 is made per ㎡");
        assertEquals("", HomeCut.withoutPerArea("㎡単価80万円以下"), "a price per ㎡ is never a budget");
        assertEquals(List.of("マンション"), HomeCut.kindWords("中古マンション"));
        assertEquals(List.of("一戸建て"), HomeCut.kindWords("戸建て"));
        assertTrue(HomeCut.kindIs("賃貸マンション", "マンション") && HomeCut.kindIs("中古一戸建て", "一戸建て") && HomeCut.kindIs("賃貸マンション", "apartment") && HomeCut.kindIs("中古一戸建て", "house"));
        assertFalse(HomeCut.kindIs("賃貸アパート", "一戸建て"));
        assertEquals("rent", HomeCut.tenureOf("賃貸")); assertEquals("sale", HomeCut.tenureOf("購入"));
    }

    @Test void aJapaneseAskIsReadWithoutAModel() {
        Map<String, String> r = HomeJapan.read("杉並区で2LDK以上、家賃15万円以下、駅徒歩10分以内の賃貸");
        assertEquals("杉並区", r.get("place"));
        assertEquals("賃貸", r.get("tenure"));
        assertEquals("家賃15万円以下", r.get("budget"));
        assertEquals("2LDK以上", r.get("bedrooms"));
        assertTrue(r.get("walk").contains("徒歩10分以内"), r.toString());
        assertFalse(r.containsKey("floor"), r.toString());
        Map<String, String> b = HomeJapan.read("東京都三鷹市で中古マンションを購入したい。予算は5000万円まで、60㎡以上、築20年以内、3LDK、2階以上、荻窪駅から徒歩8分以内");
        assertEquals("東京都三鷹市、荻窪駅", b.get("place"));
        assertEquals("購入", b.get("tenure"));
        assertEquals("予算は5000万円まで", b.get("budget"));
        assertEquals("60㎡以上", b.get("size"));
        assertEquals("築20年以内", b.get("built"));
        assertEquals("3LDK", b.get("bedrooms"));
        assertEquals("2階以上", b.get("floor"));
        assertEquals("マンション", b.get("kind"));
        assertTrue(b.get("walk").contains("徒歩8分以内"));
        assertTrue(HomeJapan.read("a house to buy in Somerville, MA").isEmpty(), "English words are the model's");
    }

    @Test void theCutFromAJapaneseAskAndTheVerdictsInJapanese(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Home.Search s = Home.start(store, "引っ越し");
        String said = Home.tell(store, s, "杉並区で2LDK以上、家賃15万円以下、駅徒歩10分以内の賃貸");
        assertTrue(said.startsWith("あなたの言葉から読み取りました：場所：杉並区"), said);
        assertEquals("杉並区", Home.wantsOf(store, s).get("place"));
        assertEquals("ja", Home.lang(store, s));
        HomeCut.Cut c = HomeCut.current(store, s);
        assertTrue(c.inJapan()); assertEquals("ja", c.lang()); assertEquals("JPY", c.currency()); assertTrue(c.metric());
        assertEquals("13115", c.japan().code()); assertEquals("杉並区", c.japan().name()); assertEquals("東京都", c.japan().pref());
        assertEquals(List.of("杉並区"), c.places());
        assertEquals(List.of("中野区", "武蔵野市", "練馬区"), c.nearby());
        assertEquals(10, c.nearKm()); assertEquals(35.6995, c.lat(), 1e-6);
        assertEquals("rent", c.tenure());
        assertEquals(150_000, c.maxPrice());
        assertEquals(new Range(2, 0), c.beds()); assertEquals(List.of("2LDK以上"), c.japan().layouts());
        assertEquals(new Range(0, 10), c.japan().walk());
        assertEquals(List.of("杉並区", "", "", "10"), List.of(looked.get(0)), "looked up by its name, with no prefecture said, at the home's radius");
        String sum = HomeCut.summary(c);
        assertTrue(sum.contains("賃貸") && sum.contains("杉並区、または10km以内の3市区町村") && sum.contains("家賃15万円以下") && sum.contains("2LDK以上（2部屋以上）") && sum.contains("駅徒歩10分以内"), sum);
        assertEquals(c.basis(), HomeCut.current(store, s).basis(), "kept while nothing changed");
        Home.Listing fits = jp("東京都杉並区例町３", "rent", 140_000, "2LDK", 55.2, 8, 3, "賃貸マンション");
        assertTrue(HomeCut.judge(c, fits).passes(), HomeCut.judge(c, fits).why());
        assertEquals(0, HomeCut.tier(c, fits));
        assertEquals("家賃が上限の15万円を超えています", HomeCut.judge(c, jp("東京都杉並区例町３", "rent", 180_000, "2LDK", 55.2, 8, 3, "賃貸マンション")).why());
        assertEquals("間取りが1LDKで、ご希望は2LDK以上です", HomeCut.judge(c, jp("東京都杉並区例町３", "rent", 120_000, "1LDK", 40, 8, 3, "賃貸マンション")).why());
        assertEquals("駅まで徒歩15分で、ご希望の10分以内を超えています", HomeCut.judge(c, jp("東京都杉並区例町３", "rent", 120_000, "3LDK", 60, 15, 3, "賃貸マンション")).why());
        assertEquals("売買物件ですが、ご希望は賃貸です", HomeCut.judge(c, jp("東京都杉並区例町３", "sale", 50_000_000, "3LDK", 60, 5, 3, "中古マンション")).why());
        assertEquals("ご希望の場所にありません", HomeCut.judge(c, jp("東京都世田谷区例町１", "rent", 120_000, "2LDK", 50, 5, 2, "賃貸マンション")).why());
        assertTrue(HomeCut.judge(c, jp("東京都武蔵野市例町１", "rent", 120_000, "2LDK", 50, 5, 2, "賃貸マンション")).passes(), "a town within the radius counts");
        Home.Listing bare = jp("東京都杉並区例町３", "rent", 0, "", 0, 0, 0, "");
        assertTrue(HomeCut.judge(c, bare).passes(), "what a listing does not say never drops it");
        List<String> unknown = HomeCut.unknown(c, bare);
        assertTrue(unknown.contains("価格の記載がありません") && unknown.contains("間取りの記載がありません") && unknown.contains("駅までの徒歩分数の記載がありません"), unknown.toString());
        assertEquals(1, HomeCut.tier(c, bare));
        assertEquals("メゾン例（東京都杉並区例町３） · 14万円/月 · 2LDK · 55.2㎡ · 築" + (LocalDate.now().getYear() - 2014) + "年（2014年） · 例線 例駅 徒歩8分 · 3階 · 賃貸マンション", fits.line());
        // a floor wanted, said and judged
        Home.setWant(store, s, "floor", "decided", "2階以上", "you", "");
        HomeCut.Cut c2 = HomeCut.current(store, s);
        assertEquals(new Range(2, 0), c2.japan().floor());
        assertEquals("1階で、ご希望の2階以上ではありません", HomeCut.judge(c2, jp("東京都杉並区例町３", "rent", 120_000, "2LDK", 50, 5, 1, "賃貸マンション")).why());
        assertTrue(HomeCut.summary(c2).contains("2階以上"), HomeCut.summary(c2));
        assertTrue(Home.standing(store, s).startsWith("検索「引っ越し」："), Home.standing(store, s));
        // the search filed says what a search in Japan does, in Japanese, and has no next page
        BooleanSupplier was = Share.serviceRunning; Share.serviceRunning = () -> true;
        try {
            assertTrue(HomeFind.file(store, s, "person", true).contains("次のページはありません"), "no next page in Japan");
            String filed = HomeFind.file(store, s, "person");
            assertTrue(filed.startsWith("検索はSUUMOの一覧を、決めた条件で絞ってすべてのページを読み") && filed.contains("実行J-") && filed.contains("件のカードを作ります"), filed);
        } finally { Share.serviceRunning = was; }
    }

    @Test void anEnglishAskAboutAPlaceInJapanIsAnEnglishCutInYen(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Home.Search s = Home.start(store, "Tokyo flat");
        Home.setWant(store, s, "place", "decided", "Suginami, Tokyo", "you", "");
        Home.setWant(store, s, "tenure", "decided", "buy", "you", "");
        Home.setWant(store, s, "budget", "decided", "under 60 million yen", "you", "");
        Home.setWant(store, s, "bedrooms", "decided", "2 or more", "you", "");
        Home.setWant(store, s, "kind", "decided", "a condo", "you", "");
        HomeCut.Cut c = HomeCut.current(store, s);
        assertTrue(c.inJapan()); assertEquals("en", c.lang()); assertEquals("13115", c.japan().code());
        assertEquals(List.of("Suginami, Tokyo"), c.places());
        assertEquals(60_000_000, c.maxPrice());
        assertEquals("Suginami", looked.get(0)[0]); assertEquals("東京都", looked.get(0)[1]);
        String sum = HomeCut.summary(c);
        assertTrue(sum.contains("to buy; in Suginami, Tokyo, or one of 3 towns within 10 km; up to ¥60,000,000; at least 2 bedrooms; a condo"), sum);
        Home.Listing fits = jp("東京都杉並区例町３", "sale", 44_800_000, "2LDK", 55.2, 8, 3, "中古マンション");
        assertTrue(HomeCut.judge(c, fits).passes(), HomeCut.judge(c, fits).why());
        assertEquals("it costs more than your budget of ¥60,000,000", HomeCut.judge(c, jp("東京都杉並区例町３", "sale", 70_000_000, "2LDK", 55.2, 8, 3, "中古マンション")).why());
        assertEquals("it has fewer bedrooms than the 2 you want", HomeCut.judge(c, jp("東京都杉並区例町３", "sale", 40_000_000, "1LDK", 40, 8, 3, "中古マンション")).why());
        assertEquals("it is a 中古一戸建て, not the kind you want", HomeCut.judge(c, jp("東京都杉並区例町３", "sale", 40_000_000, "3LDK", 90, 8, 0, "中古一戸建て")).why());
        assertEquals("it is not in a place you want", HomeCut.judge(c, jp("東京都世田谷区例町１", "sale", 40_000_000, "2LDK", 50, 5, 2, "中古マンション")).why());
        assertTrue(fits.line().contains("4,480万円 · 81.2万円/㎡ · 2LDK · 55.2㎡"), fits.line());
    }

    @Test void aLookupThatDidNotAnswerIsSaidApartFromANameThatIsNobodysAndIsAskedAgain(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        HomeJapan.NEAR = (town, pref, parent, km) -> { looked.add(new String[]{town, pref, parent, String.valueOf(km)}); return HomeJapan.Lookup.SILENT; };
        Home.Search s = Home.start(store, "Mitaka flat");
        Home.setWant(store, s, "place", "decided", "Mitaka, Tokyo", "you", "");
        HomeCut.Cut c = HomeCut.current(store, s);
        assertTrue(c.inJapan() && c.japan().code().isEmpty());
        String log = String.join("\n", Home.lately(store, s, 10));
        assertTrue(log.contains("Wikidata did not answer the lookup of Mitaka; search again in a minute.") && !log.contains("No municipality called"), log);
        assertEquals(1, looked.size());
        HomeJapan.lookup(store, "Mitaka", "東京都", 10);
        assertEquals(2, looked.size(), "a silent answer is not kept: the next search asks again");
        HomeJapan.NEAR = (town, pref, parent, km) -> HomeJapan.Lookup.NONE;
        Home.setWant(store, s, "place", "decided", "Nowhereville, Tokyo", "you", "");
        HomeCut.current(store, s);
        assertTrue(String.join("\n", Home.lately(store, s, 3)).contains("No municipality called Nowhereville was found in Japan's records"), String.join("\n", Home.lately(store, s, 3)));
        // a cut made while the lookup was silent, with no code, is made again at the next search, with nothing of the person's changed
        HomeJapan.NEAR = (town, pref, parent, km) -> HomeJapan.Lookup.SILENT;
        Home.setWant(store, s, "place", "decided", "Mitaka, Tokyo", "you", "");
        assertTrue(HomeCut.current(store, s).japan().code().isEmpty());
        HomeJapan.NEAR = (town, pref, parent, km) -> new HomeJapan.Lookup("三鷹市（東京都）", "三鷹市", "Mitaka", "13204", 35.68, 139.56, List.of("武蔵野市"));
        assertEquals("13204", HomeCut.current(store, s).japan().code(), "made again, since the lookup is kept once it answers");
        assertEquals("13204", HomeCut.current(store, s).japan().code(), "and then kept");
    }
}
