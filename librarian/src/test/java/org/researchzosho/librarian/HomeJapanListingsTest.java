package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.HomeCut.Cut;
import org.researchzosho.librarian.HomeCut.Japan;
import org.researchzosho.librarian.HomeCut.Range;
import org.researchzosho.librarian.HomeSources.Yield;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SUUMO's lists on invented pages: the search's conditions become the site's own query, each room and each home is read with its own page,
 * and the enumeration goes on past the first page to the last, or to the cap. No web.
 */
class HomeJapanListingsTest {

    final Map<String, String> pages = new HashMap<>();
    final List<String> fetched = new CopyOnWriteArrayList<>();

    @BeforeEach void standIns() { HomeJapanListings.GET = u -> { fetched.add(u); return pages.get(u); }; }
    @AfterEach void back() { HomeJapanListings.GET = CareerSources::get; }

    static Cut cut(String tenure, Range price, Range beds, Range walk, List<String> kinds, Range sqft, Range built) {
        Japan jp = new Japan(true, "13115", "杉並区", "Suginami", "東京都", walk, Range.OPEN, List.of(), "");
        return new Cut(List.of("杉並区, 東京都"), List.of(), 10, 35.7, 139.6, tenure, price, Range.OPEN, HomeCut.SQ_M, beds, Range.OPEN, sqft, Range.OPEN, built, kinds, List.of(), List.of(), "", "", "ja", jp);
    }

    /** One building with its rooms, in the shape SUUMO's rent list writes. */
    static String building(String name, String address, String station, int age, String... rooms) {
        StringBuilder b = new StringBuilder("<div class=\"cassetteitem\"><div class=\"cassetteitem-detail\"><div class=\"cassetteitem_content\"><div class=\"cassetteitem_content-label\"><span class=\"ui-pct ui-pct--util1\">賃貸マンション</span></div>"
                + "<div class=\"cassetteitem_content-title\">" + name + "</div><ul class=\"cassetteitem_detail\"><li class=\"cassetteitem_detail-col1\">" + address + "</li>"
                + "<li class=\"cassetteitem_detail-col2\"><div class=\"cassetteitem_detail-text\">" + station + "</div><div class=\"cassetteitem_detail-text\">例線/次例駅 歩15分</div></li>"
                + "<li class=\"cassetteitem_detail-col3\"><div>築" + age + "年</div><div>5階建</div></li></ul></div></div><div class=\"cassetteitem-item\"><table class=\"cassetteitem_other\"><tbody>");
        for (String r : rooms) b.append(r);
        return b.append("</tbody></table></div></div>").toString();
    }

    /** One room: {id, floor, rent, admin, layout, area}. */
    static String room(String id, String floor, String rent, String admin, String layout, String area) {
        return "<tr class=\"js-cassette_link\"><td class=\"cassetteitem_other-checkbox\"><input type=\"checkbox\" name=\"bc\" value=\"" + id + "\"></td><td><div class=\"casssetteitem_other-thumbnail\" data-imgs=\"x\"></div></td>"
                + "<td> " + floor + "</td><td><ul><li><span class=\"cassetteitem_price cassetteitem_price--rent\"><span class=\"cassetteitem_other-emphasis ui-text--bold\">" + rent + "</span></span></li><li><span class=\"cassetteitem_price cassetteitem_price--administration\">" + admin + "</span></li></ul></td>"
                + "<td><ul><li><span class=\"cassetteitem_price cassetteitem_price--deposit\">" + rent + "</span></li></ul></td><td><ul><li><span class=\"cassetteitem_madori\">" + layout + "</span></li><li><span class=\"cassetteitem_menseki\">" + area + "m<sup>2</sup></span></li></ul></td>"
                + "<td></td><td class=\"js-property\"></td><td class=\"ui-text--midium ui-text--bold\"><a href=\"/chintai/jnc_" + id + "/?bc=" + id + "\" target=\"_blank\" class=\"js-cassette_link_href cassetteitem_other-linktext\">詳細を見る</a></td></tr>";
    }

    static String rentPage(int total, int page, int last, String... buildings) {
        StringBuilder b = new StringBuilder("<html><body>");
        for (String x : buildings) b.append(x);
        b.append("<div class=\"pagination_set\"><div class=\"pagination_set-hit\"> ").append(String.format("%,d", total)).append("<span>件</span></div><div class=\"pagination pagination_set-nav\"><ol class=\"pagination-parts\">");
        for (int p = 1; p <= last; p++) b.append(p == page ? "<li class=\"pagination-current\">" + p + "</li>" : "<li><a href=\"/jj/chintai/ichiran/FR301FC001/?page=" + p + "\">" + p + "</a></li>");
        return b.append("</ol></div></div></body></html>").toString();
    }

    static String unit(String id, String name, String price, String address, String station, String land, String floorArea, String layout, String built) {
        return "<div class=\"property_unit property_unit--osusume\"><div class=\"property_unit-content\"><div class=\"property_unit-header\"><h2 class=\"property_unit-title\"><a href=\"/chukoikkodate/tokyo/sc_suginami/nc_" + id + "/\" target=\"_blank\">" + name + "</a></h2></div>"
                + "<div class=\"property_unit-body\"><div class=\"dottable dottable--cassette\"><div class=\"dottable-line\"><dl><dt class=\"dottable-vm\">物件名</dt><dd class=\"dottable-vm\">" + name + "</dd></dl></div>"
                + "<div class=\"dottable-line\"><dl><dt class=\"dottable-vm\">販売価格</dt><dd class=\"dottable-vm\"><span class=\"dottable-value\">" + price + "</span></dd></dl></div>"
                + "<div class=\"dottable-line\"><dl><dt>所在地</dt><dd>" + address + "</dd></dl><dl><dt>沿線・駅</dt><dd>" + station + "</dd></dl></div>"
                + "<div class=\"dottable-line\"><table class=\"dottable-fix\"><tbody><tr><td><dl><dt>土地面積</dt><dd>" + land + "m<sup>2</sup>（17.50坪）</dd></dl></td><td><dl><dt>間取り</dt><dd>" + layout + "</dd></dl></td></tr></tbody></table></div>"
                + "<div class=\"dottable-line\"><table class=\"dottable-fix\"><tbody><tr><td><dl><dt>建物面積</dt><dd>" + floorArea + "m<sup>2</sup>（18.67坪）</dd></dl></td><td><dl><dt>築年月</dt><dd>" + built + "</dd></dl></td></tr></tbody></table></div></div></div></div></div>";
    }

    static String buyPage(int total, int page, int last, String... units) {
        StringBuilder b = new StringBuilder("<html><body>");
        for (String x : units) b.append(x);
        b.append("<div class=\"pagination_set\"><div class=\"pagination_set-hit\"> ").append(total).append("<span>件</span></div><div class=\"pagination pagination_set-nav\"><ol class=\"pagination-parts\">");
        for (int p = 1; p <= last; p++) b.append(p == page ? "<li class=\"pagination-current\">" + p + "</li>" : "<li><a href=\"/jj/bukken/ichiran/JJ012FC001/?page=" + p + "\">" + p + "</a></li>");
        return b.append("</ol></div></div></body></html>").toString();
    }

    @Test void theSearchIsSuumosOwnQuery() {
        Cut rent = cut("rent", new Range(0, 150_000), new Range(2, 0), new Range(0, 10), List.of("マンション"), new Range(Math.round(40 * HomeCut.SQ_FT_PER_M2), 0), new Range(LocalDate.now().getYear() - 15, 0));
        String u = HomeJapanListings.rentUrl(rent, 2);
        assertTrue(u.startsWith("https://suumo.jp/jj/chintai/ichiran/FR301FC001/?ar=030&bs=040&ta=13&sc=13115&"), u);
        assertTrue(u.contains("&cb=0.0&ct=15.0&") && u.contains("&et=10&") && u.contains("&cn=15&") && u.contains("&mb=40&mt=9999999"), u);
        assertTrue(u.contains("&md=05&md=06&md=07&md=08&md=09&md=10&md=11&md=12&md=13&md=14") && !u.contains("md=04"), "two rooms or more: " + u);
        assertTrue(u.contains("&ts=1&") && u.endsWith("&pc=50&page=2"), u);
        Cut open = cut("rent", Range.OPEN, Range.OPEN, Range.OPEN, List.of(), Range.OPEN, Range.OPEN);
        String o = HomeJapanListings.rentUrl(open, 1);
        assertTrue(o.contains("&cb=0.0&ct=9999999&et=9999999&cn=9999999&mb=0&mt=9999999&pc=50&page=1") && !o.contains("md=") && !o.contains("ts="), o);
        Cut buy = cut("sale", new Range(30_000_000, 50_000_000), new Range(2, 0), Range.OPEN, List.of(), Range.OPEN, Range.OPEN);
        List<HomeJapanListings.BuyKind> kinds = HomeJapanListings.buyKinds(buy);
        assertEquals(List.of("011", "021"), kinds.stream().map(HomeJapanListings.BuyKind::bs).toList(), "flats and houses when no kind is named");
        String area = HomeJapanListings.areaUrl(buy, kinds.get(0), "suginami");
        assertEquals("https://suumo.jp/ms/chuko/tokyo/sc_suginami/", area, "the area page, which robots allows; the sale search list it does not");
        assertEquals("https://suumo.jp/chukoikkodate/tokyo/sc_suginami/", HomeJapanListings.areaUrl(buy, kinds.get(1), "suginami"));
        String b = HomeJapanListings.buyUrl(buy, kinds.get(0), area, 1);
        assertTrue(b.startsWith("https://suumo.jp/ms/chuko/tokyo/sc_suginami/?kb=3000&kt=5500&"), "the ceiling kept in, kt being 'under': " + b);
        assertTrue(b.contains("&md=2&md=3&md=4&md=5") && b.endsWith("&pc=100&page=1"), b);
        assertEquals(List.of("030"), HomeJapanListings.buyKinds(cut("sale", Range.OPEN, Range.OPEN, Range.OPEN, List.of("土地"), Range.OPEN, Range.OPEN)).stream().map(HomeJapanListings.BuyKind::bs).toList());
        assertEquals(List.of("011"), HomeJapanListings.buyKinds(cut("sale", Range.OPEN, Range.OPEN, Range.OPEN, List.of("condo"), Range.OPEN, Range.OPEN)).stream().map(HomeJapanListings.BuyKind::bs).toList(), "a kind asked in English names SUUMO's list");
        assertEquals(List.of("021"), HomeJapanListings.buyKinds(cut("sale", Range.OPEN, Range.OPEN, Range.OPEN, List.of("house"), Range.OPEN, Range.OPEN)).stream().map(HomeJapanListings.BuyKind::bs).toList());
        List<String[]> links = HomeJapanListings.portalLinks(null, rent, "rent");
        assertEquals(3, links.size());
        assertTrue(links.get(0)[0].startsWith("SUUMO") && links.get(0)[1].contains("sc=13115") && !links.get(0)[1].contains("page="), links.get(0)[1]);
        assertEquals("https://www.athome.co.jp/chintai/tokyo/suginami-city/list/", links.get(1)[1]);
        // the prefecture's page names the municipalities as SUUMO writes them: 群馬 is gumma, and Wikidata's Latin names are not asked
        pages.put("https://suumo.jp/ms/chuko/tokyo/", prefPage());
        Map<String, String> slugs = HomeJapanListings.parseSlugs(prefPage(), "tokyo");
        assertEquals("suginami", slugs.get("杉並区")); assertEquals("mitaka", slugs.get("三鷹市")); assertEquals(2, slugs.size(), slugs.toString());
        assertEquals("gumma", HomeJapan.PREFECTURES.get(9).suumoSlug()); assertEquals("hokkaido_", HomeJapan.PREFECTURES.get(0).suumoSlug()); assertEquals("tokyo", HomeJapan.PREFECTURES.get(12).suumoSlug());
    }

    /** A prefecture's page in the shape SUUMO's writes, with two municipalities. */
    static String prefPage() {
        return "<html><body><ul><li><a href=\"/ms/chuko/tokyo/sc_suginami/\" class=\"x\">杉並区</a></li><li><a href=\"/ms/chuko/tokyo/sc_mitaka/\">三鷹市</a></li><li><a href=\"/ms/chuko/tokyo/sc_suginami/eki/\">駅から探す</a></li></ul></body></html>";
    }

    @Test void theMunicipalitysNameOnSuumoIsReadOffThePrefecturesPageAndKept(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        pages.put("https://suumo.jp/ms/chuko/tokyo/", prefPage());
        Cut buy = cut("sale", Range.OPEN, Range.OPEN, Range.OPEN, List.of("condo"), Range.OPEN, Range.OPEN);
        assertEquals("suginami", HomeJapanListings.slug(store, buy));
        assertEquals(1, fetched.size());
        assertEquals("suginami", HomeJapanListings.slug(store, buy));
        assertEquals(1, fetched.size(), "kept with the library: the page is read once");
        Japan jp = new Japan(true, "13204", "三鷹市", "Mitaka", "東京都", Range.OPEN, Range.OPEN, List.of(), "");
        Cut mitaka = new Cut(List.of("Mitaka, Tokyo"), List.of(), 10, 35.7, 139.6, "sale", Range.OPEN, Range.OPEN, HomeCut.SQ_M, Range.OPEN, Range.OPEN, Range.OPEN, Range.OPEN, Range.OPEN, List.of(), List.of(), List.of(), "", "", "en", jp);
        assertEquals("mitaka", HomeJapanListings.slug(store, mitaka));
        Japan unknown = new Japan(true, "13999", "架空市", "Kaku", "東京都", Range.OPEN, Range.OPEN, List.of(), "");
        assertEquals("", HomeJapanListings.slug(store, new Cut(List.of("架空市, 東京都"), List.of(), 10, 0, 0, "sale", Range.OPEN, Range.OPEN, HomeCut.SQ_M, Range.OPEN, Range.OPEN, Range.OPEN, Range.OPEN, Range.OPEN, List.of(), List.of(), List.of(), "", "", "ja", unknown)), "a name not on the page: no guess");
        List<String[]> links = HomeJapanListings.portalLinks(store, buy, "sale");
        assertEquals(3, links.size()); assertTrue(links.get(0)[1].startsWith("https://suumo.jp/ms/chuko/tokyo/sc_suginami/?kb=1&kt=9999999&"), links.get(0)[1]);
    }

    @Test void roomsAndHomesAreReadWithTheirOwnPages() {
        String html = rentPage(2, 1, 1, building("メゾン例", "東京都杉並区例町３", "例線/例駅 歩8分", 12, room("000000000001", "3階", "15万円", "5000円", "2LDK", "55.2"), room("000000000002", "1階", "11.7万円", "-", "1K", "25")),
                building("コーポ見本", "東京都杉並区見本町１", "見本線/見本駅 歩3分", 42, room("000000000003", "2階", "13万円", "-", "3DK", "63")));
        List<Home.Listing> rooms = HomeJapanListings.rentListings(html);
        assertEquals(3, rooms.size());
        Home.Listing r = rooms.get(0);
        assertEquals("https://suumo.jp/chintai/jnc_000000000001/?bc=000000000001", r.url());
        assertEquals(List.of("東京都杉並区例町３", "杉並区", "東京都"), List.of(r.address(), r.city(), r.state()));
        assertEquals(150_000, r.price()); assertEquals(2, r.beds()); assertEquals("2LDK", r.extra().layout()); assertEquals(55.2, r.extra().areaM2(), 1e-9);
        assertEquals(Math.round(55.2 * HomeCut.SQ_FT_PER_M2), r.sqft());
        assertEquals(LocalDate.now().getYear() - 12, r.extra().built());
        assertEquals(List.of("例線", "例駅", 8, 3), List.of(r.extra().line(), r.extra().station(), r.extra().walk(), r.extra().floor()));
        assertEquals("賃貸マンション", r.kind()); assertEquals("メゾン例", r.extra().name()); assertEquals("JPY", r.extra().currency()); assertTrue(r.japan());
        assertEquals("管理費 5000円", r.note());
        assertEquals("rent", r.tenure());
        assertEquals(117_000, rooms.get(1).price()); assertEquals(1, rooms.get(1).extra().floor()); assertEquals("", rooms.get(1).note());
        assertEquals(130_000, rooms.get(2).price()); assertEquals("コーポ見本", rooms.get(2).extra().name());
        assertEquals(3, rooms.stream().map(Home.Listing::id).distinct().count(), "each room is its own listing, by its figures");
        assertEquals(rooms.get(0).id(), HomeJapanListings.rentListings(rentPage(1, 1, 1, building("メゾン例", "東京都杉並区例町３", "例線/例駅 歩8分", 12, room("000000000009", "3階", "15万円", "5000円", "2LDK", "55.2")))).get(0).id(), "the same room from another agent's page is the same listing");
        // the nearest of the stations a building names, as SUUMO's own walk filter measures it: the second line here is 15 minutes, the first 8, so 8 stands
        assertEquals(List.of("例線", "例駅", "8"), List.of(HomeJapanListings.nearestStation("<div class=\"cassetteitem_detail-text\">例線/例駅 歩8分</div><div class=\"cassetteitem_detail-text\">例線/次例駅 歩15分</div>")));
        assertEquals(List.of("見本線", "見本駅", "4"), List.of(HomeJapanListings.nearestStation("<div class=\"cassetteitem_detail-text\">例線/例駅 歩12分</div><div class=\"cassetteitem_detail-text\">見本線/見本駅 歩4分</div>")), "the nearer second line wins");
        assertEquals(List.of("例線", "例駅", ""), List.of(HomeJapanListings.nearestStation("<div class=\"cassetteitem_detail-text\">例線/例駅 バス10分</div>")), "no minutes on foot: the first named, with no walk");
        assertTrue(r.ownPage() && r.link().equals(r.url()), "a room's link is its own page");
        String buy = buyPage(3, 1, 1, unit("21943395", "杉並区例町２丁目", "4480万円", "東京都杉並区例町２", "ＪＲ例線「例」徒歩19分", "57.87", "61.74", "4DK", "1984年10月"),
                unit("21943396", "見本町の家", "1億2000万円", "東京都杉並区見本町５", "見本線「見本」徒歩3分", "120", "150.5", "5LDK", "2020年3月"),
                unit("21943397", "杉並区例町２丁目", "4480万円", "東京都杉並区例町２", "ＪＲ例線「例」バス10分停歩2分", "57.87", "61.74", "4DK", "1984年10月"));
        List<Home.Listing> homes = HomeJapanListings.buyListings(buy, HomeJapanListings.BUY_KINDS.get(1));
        assertEquals(3, homes.size());
        assertEquals(homes.get(0).id(), homes.get(2).id(), "the same home from two agents is one listing, by its figures");
        assertNotEquals(homes.get(0).id(), homes.get(1).id());
        assertEquals(List.of("JR例線", "例駅", 0), List.of(homes.get(2).extra().line(), homes.get(2).extra().station(), homes.get(2).extra().walk()), "a station in brackets reached by bus: named, no minutes on foot");
        Home.Listing h = homes.get(0);
        assertEquals("https://suumo.jp/chukoikkodate/tokyo/sc_suginami/nc_21943395/", h.url());
        assertEquals(44_800_000, h.price()); assertEquals("東京都杉並区例町２", h.address()); assertEquals("杉並区", h.city());
        assertEquals(61.74, h.extra().areaM2(), 1e-9); assertEquals("4DK", h.extra().layout()); assertEquals(4, h.beds()); assertEquals(1984, h.extra().built());
        assertEquals(List.of("JR例線", "例駅", 19), List.of(h.extra().line(), h.extra().station(), h.extra().walk()), "the line's letters made ASCII, the address kept as written");
        assertEquals("中古一戸建て", h.kind()); assertEquals("sale", h.tenure()); assertEquals("", h.extra().name(), "a name that is the address is no name");
        assertEquals("土地面積 57.87m2（17.50坪）", h.note());
        assertEquals(120_000_000, homes.get(1).price()); assertEquals("見本町の家", homes.get(1).extra().name()); assertEquals(2020, homes.get(1).extra().built());
        assertEquals("72.6万円/㎡", h.perM2Text());
        assertTrue(h.line().contains("4,480万円 · 72.6万円/㎡ · 4DK · 61.74㎡ · 築" + (LocalDate.now().getYear() - 1984) + "年（1984年） · JR例線 例駅 徒歩19分 · 中古一戸建て"), h.line());
    }

    @Test void theEnumerationNeverStopsAtPageOne(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Cut rent = cut("rent", new Range(0, 150_000), Range.OPEN, Range.OPEN, List.of(), Range.OPEN, Range.OPEN);
        pages.put(HomeJapanListings.rentUrl(rent, 1), rentPage(5, 1, 3, building("メゾン例", "東京都杉並区例町３", "例線/例駅 歩8分", 12, room("000000000001", "3階", "15万円", "-", "2LDK", "55.2"), room("000000000002", "1階", "11.7万円", "-", "1K", "25"))));
        pages.put(HomeJapanListings.rentUrl(rent, 2), rentPage(5, 2, 3, building("コーポ見本", "東京都杉並区見本町１", "見本線/見本駅 歩3分", 42, room("000000000003", "2階", "13万円", "-", "3DK", "63"), room("000000000004", "4階", "14万円", "-", "2DK", "45"))));
        pages.put(HomeJapanListings.rentUrl(rent, 3), rentPage(5, 3, 3, building("ハイツ例", "東京都杉並区例町９", "例線/例駅 歩12分", 3, room("000000000005", "2階", "9.8万円", "-", "1LDK", "35"))));
        Yield y = HomeJapanListings.suumo(store, rent, "rent", null);
        assertEquals("SUUMO", y.name()); assertEquals("list page", y.kind());
        assertEquals(5, y.listings().size(), "every page: " + y.note());
        assertEquals(3, fetched.size());
        assertTrue(fetched.get(1).endsWith("&page=2") && fetched.get(2).endsWith("&page=3"), fetched.toString());
        assertTrue(y.note().contains("賃貸：3ページから5件（SUUMOの掲載件数 5件）"), y.note());
        assertTrue(y.listings().get(0).note().contains("seen on " + HomeJapanListings.rentUrl(rent, 1)), "the list page it was seen on: " + y.listings().get(0).note());
        assertTrue(y.listings().get(4).note().contains("&page=3"));
        assertEquals(HomeJapanListings.rentUrl(rent, 1), y.address());
        // a list that always names a next page stops at the cap, and says so
        fetched.clear();
        HomeJapanListings.GET = u -> { fetched.add(u); int p = Integer.parseInt(u.replaceAll(".*page=", "")); return rentPage(9_999, p, p + 1, building("ビル" + p, "東京都杉並区例町" + p, "例線/例駅 歩8分", 10, room(String.format("%012d", p), "2階", "10万円", "-", "1K", "20"))); };
        Yield capped = HomeJapanListings.suumo(store, rent, "rent", null);
        assertEquals(HomeJapanListings.PAGE_CAP, capped.listings().size());
        assertEquals(HomeJapanListings.PAGE_CAP, fetched.size());
        assertTrue(capped.note().contains("ページで打ち切り"), capped.note());
        // for sale: both kinds' lists, each whole
        fetched.clear();
        Cut buy = cut("sale", Range.OPEN, Range.OPEN, Range.OPEN, List.of(), Range.OPEN, Range.OPEN);
        HomeJapanListings.GET = u -> { fetched.add(u); if (u.equals("https://suumo.jp/ms/chuko/tokyo/")) return prefPage(); int p = Integer.parseInt(u.replaceAll(".*page=", "")); boolean flats = u.startsWith("https://suumo.jp/ms/chuko/tokyo/sc_suginami/?"); assertTrue(flats || u.startsWith("https://suumo.jp/chukoikkodate/tokyo/sc_suginami/?"), "the area pages, never the sale search list: " + u); if (p > 2) return buyPage(2, p, 2); return buyPage(2, p, 2, unit((flats ? "1" : "2") + "000" + p, flats ? "見本" : "見本の家", "3000万円", "東京都杉並区見本町" + p, "見本線「見本」徒歩5分", "50", "60", "2LDK", "2001年1月")); };
        Yield sale = HomeJapanListings.suumo(store, buy, "sale", null);
        assertEquals(4, sale.listings().size(), sale.note());
        assertEquals(5, fetched.size(), "the prefecture's page once, then two pages of each kind: " + fetched);
        assertTrue(sale.note().contains("中古マンション：2ページから2件") && sale.note().contains("中古一戸建て：2ページから2件"), sale.note());
        assertEquals(List.of("中古マンション", "中古一戸建て"), sale.listings().stream().map(Home.Listing::kind).distinct().toList());
        // a page that does not answer ends the list without a word lost
        fetched.clear();
        HomeJapanListings.GET = u -> { fetched.add(u); return u.endsWith("page=1") ? rentPage(50, 1, 2, building("メゾン例", "東京都杉並区例町３", "例線/例駅 歩8分", 12, room("000000000001", "3階", "15万円", "-", "2LDK", "55.2"))) : null; };
        Yield cutShort = HomeJapanListings.suumo(store, rent, "rent", null);
        assertEquals(1, cutShort.listings().size()); assertEquals(2, fetched.size());
    }
}
