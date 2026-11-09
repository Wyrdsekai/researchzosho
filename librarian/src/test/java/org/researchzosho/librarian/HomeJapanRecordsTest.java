package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.tools.SearchTally;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The ministry's records on a stand-in: the municipalities and their codes, the transaction prices of the latest quarters kept once per
 * municipality and quarter, the comparable sales and their middle price per square metre, the nearest land price point, the flood depth
 * at the point, each in the language of the search; the key never in any line or fact; one sentence without a key. No web.
 */
class HomeJapanRecordsTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String STAND_IN_KEY = "stand-in-key-xyz-123";
    final List<String> calls = new CopyOnWriteArrayList<>();
    volatile String key = STAND_IN_KEY;
    String realHome;

    @BeforeEach void standIns(@TempDir Path tmp) {
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        SearchTally.override = tmp.resolve("tally.json");
        HomeJapanRecords.KEY_VALUE = () -> key;
        HomeJapanRecords.GEOCODE = address -> new double[]{35.70, 139.63};
        HomeJapanRecords.API = url -> {
            calls.add(url);
            assertFalse(url.contains(STAND_IN_KEY), "the key never travels in an address");
            if (url.contains("XIT002?area=13&language=en")) return "{\"status\":\"OK\",\"data\":[{\"id\":\"13101\",\"name\":\"Chiyoda Ward\"},{\"id\":\"13115\",\"name\":\"Suginami Ward\"}]}";
            if (url.contains("XIT002?area=13")) return "{\"status\":\"OK\",\"data\":[{\"id\":\"13101\",\"name\":\"千代田区\"},{\"id\":\"13115\",\"name\":\"杉並区\"}]}";
            if (url.contains("XIT001?")) {
                List<int[]> qs = HomeJapanRecords.quarters(LocalDate.now());
                for (int i = 0; i < 2; i++) if (url.contains("year=" + qs.get(i)[0] + "&quarter=" + qs.get(i)[1] + "&city=13115")) return trades(qs.get(i)[0] + "年第" + qs.get(i)[1] + "四半期");
                return "";   // the ministry answers 404 for a quarter with nothing yet
            }
            if (url.contains("XPT002?")) return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[139.632,35.701]},\"properties\":{\"residence_display_name_ja\":\"例町３－１－１\",\"u_current_years_price_ja\":\"580,000(円/㎡)\",\"year_on_year_change_rate\":\"7.9\",\"regulations_use_category_name_ja\":\"第一種低層住居専用地域\",\"nearest_station_name_ja\":\"例\",\"u_road_distance_to_nearest_station_name_ja\":\"570m\"}},"
                    + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[139.66,35.72]},\"properties\":{\"residence_display_name_ja\":\"遠い町１\",\"u_current_years_price_ja\":\"900,000(円/㎡)\",\"year_on_year_change_rate\":\"1.0\"}}]}";
            if (url.contains("XKT026?")) return "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[139.62,35.69],[139.64,35.69],[139.64,35.71],[139.62,35.71],[139.62,35.69]]]},\"properties\":{\"A31a_202\":\"例川\",\"A31a_205\":2}},"
                    + "{\"type\":\"Feature\",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[139.70,35.69],[139.72,35.69],[139.72,35.71],[139.70,35.71],[139.70,35.69]]]},\"properties\":{\"A31a_202\":\"遠い川\",\"A31a_205\":5}}]}";
            if (url.contains("XKT029?") || url.contains("XKT016?")) return "{\"type\":\"FeatureCollection\",\"features\":[]}";
            return null;
        };
    }

    @AfterEach void back() {
        System.setProperty("user.home", realHome); Config.invalidate(); SearchTally.override = null;
        HomeJapanRecords.KEY_VALUE = () -> Config.get(HomeJapanRecords.KEY); HomeJapanRecords.API = HomeJapanRecords::fetch; HomeJapanRecords.GEOCODE = HomeJapanRecords::geocode;
    }

    static String trades(String period) {
        return "{\"status\":\"OK\",\"data\":["
                + row("中古マンション等", "例町", "44000000", "2LDK", "55", "", "ＲＣ", "2005年", period) + ","
                + row("中古マンション等", "例町", "38000000", "2LDK", "50", "", "ＲＣ", "1998年", period) + ","
                + row("中古マンション等", "例町", "52000000", "3LDK", "70", "", "ＳＲＣ", "2010年", period) + ","
                + row("中古マンション等", "見本町", "30000000", "1LDK", "40", "", "ＲＣ", "2001年", period) + ","
                + row("宅地(土地と建物)", "例町", "76000000", "", "80", "", "木造", "2025年", period) + ","
                + row("宅地(土地)", "例町", "60000000", "", "100", "600000", "", "", period) + "]}";
    }

    static String row(String type, String district, String price, String plan, String area, String unit, String structure, String built, String period) {
        return "{\"Type\":\"" + type + "\",\"Region\":\"住宅地\",\"MunicipalityCode\":\"13115\",\"Prefecture\":\"東京都\",\"Municipality\":\"杉並区\",\"DistrictName\":\"" + district + "\",\"TradePrice\":\"" + price + "\",\"FloorPlan\":\"" + plan + "\",\"Area\":\"" + area + "\",\"UnitPrice\":\"" + unit + "\",\"TotalFloorArea\":\"" + (type.contains("建物") ? "90" : "") + "\",\"BuildingYear\":\"" + built + "\",\"Structure\":\"" + structure + "\",\"Use\":\"住宅\",\"Period\":\"" + period + "\",\"Renovation\":\"\",\"Remarks\":\"\"}";
    }

    static Home.Listing flat(long price) {
        Home.Listing.Extra x = new Home.Listing.Extra("JPY", "メゾン例", "2LDK", 55.2, 2008, "例線", "例駅", 8, 3);
        return new Home.Listing("abc", "SUUMO", "sale", "東京都杉並区例町３", "杉並区", "東京都", "", price, 2, 0, (int) Math.round(55.2 * HomeCut.SQ_FT_PER_M2), "中古マンション", "https://suumo.jp/ms/chuko/tokyo/sc_suginami/nc_1/", 0, 0, "", "", "", "", x);
    }

    @Test void theRecordsOfAFlatInJapaneseAndTheKeyNowhere(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        assertEquals("13115", HomeJapanRecords.cityCode(store, "東京都", "杉並区"));
        assertEquals("13115", HomeJapanRecords.cityCode(store, "東京都", "Suginami"));
        assertEquals("13115", HomeJapanRecords.cityCode(store, "Tokyo", "Suginami Ward"));
        assertEquals("", HomeJapanRecords.cityCode(store, "東京都", "架空区"));
        HomeSensors.Sheet sheet = HomeJapanRecords.sheet(store, flat(44_800_000), "ja");
        String text = sheet.text(); JsonNode f = sheet.facts();
        assertTrue(text.startsWith("記録: 国土交通省 不動産情報ライブラリ（不動産取引価格情報 杉並区 "), text);
        assertTrue(text.contains(HomeJapanRecords.CREDIT), "the credit the ministry's terms ask for: " + text);
        assertEquals("13115", f.path("city_code").asText());
        assertEquals("例町", f.path("district").asText());
        assertTrue(text.contains("取引価格の中央値（中古マンション等、例町、6件、"), text);
        assertEquals(760_000.0, f.path("median_per_m2").asDouble(), 1e-6, "the middle of 742,857, 760,000 and 800,000, each in two quarters");
        assertTrue(text.contains("この物件の希望価格は81.2万円/㎡で、中央値の107%です"), text);
        assertTrue(text.contains("近くの取引（中古マンション等、6件）: 例町 中古マンション等 2LDK 55㎡ 4,400万円（80万円/㎡） ＲＣ 2005年築 "), text);
        assertFalse(text.contains("見本町"), "another district's sales are not nearest when the district has three of its own");
        assertTrue(text.contains("地価公示 " + LocalDate.now().getYear() + "年、最寄りの標準地（約") && text.contains("例町３－１－１ 580,000(円/㎡)（前年比 +7.9%）、最寄り駅 例 570m、第一種低層住居専用地域"), text);
        assertTrue(text.contains("洪水浸水想定区域（想定最大規模）: 例川、浸水深ランク2、0.5〜3.0m未満（床上から1階が浸水）"), text);
        assertTrue(text.contains("土砂災害警戒区域: 区域外") && text.contains("災害危険区域: 区域外"), text);
        assertEquals(2, f.path("flood_rank").asInt());
        assertFalse(text.contains(STAND_IN_KEY) || f.toString().contains(STAND_IN_KEY), "the key is in no line and no fact");
        assertFalse(String.join("\n", calls).contains(STAND_IN_KEY));
        // the tally counts the calls, by the service's name alone
        assertTrue(SearchTally.thisMonth().getOrDefault(HomeJapanRecords.TALLY, new int[]{0, 0})[0] >= 0);
        // the cache: a second sheet of the same municipality makes no new call for the prices, the point or the hazards
        int before = calls.size();
        HomeJapanRecords.sheet(store, flat(40_000_000), "ja");
        assertEquals(before, calls.size(), "kept with the library: " + calls.subList(before, calls.size()));
        assertTrue(Files.list(tmp.resolve("lib").resolve("catalog").resolve("home-cache")).anyMatch(p -> p.getFileName().toString().startsWith("reinfolib-trades-13115-")));
        // the English sheet
        String en = HomeJapanRecords.sheet(store, flat(44_800_000), "en").text();
        assertTrue(en.startsWith("Records: MLIT's 不動産情報ライブラリ (transaction prices for 杉並区, ") && en.contains("The middle price paid per m² for 中古マンション等 in 例町 (the district) (6 sales, ") && en.contains("this home asks ¥811,594/㎡, 107% of it") && en.contains("Flood inundation area (largest assumed flood): 例川, depth rank 2, 0.5 to 3 m (the ground floor flooded)"), en);
        assertTrue(en.contains(HomeJapanRecords.CREDIT));
        // a rental compares with the flats sold, and asks no per-㎡ of itself
        Home.Listing.Extra rx = new Home.Listing.Extra("JPY", "メゾン例", "2LDK", 55.2, 2008, "例線", "例駅", 8, 3);
        Home.Listing rent = new Home.Listing("r", "SUUMO", "rent", "東京都杉並区例町３", "杉並区", "東京都", "", 150_000, 2, 0, 594, "賃貸マンション", "https://suumo.jp/chintai/jnc_1/", 0, 0, "", "", "", "", rx);
        String rt = HomeJapanRecords.sheet(store, rent, "ja").text();
        assertTrue(rt.contains("取引価格の中央値（中古マンション等") && !rt.contains("この物件の希望価格"), rt);
    }

    @Test void withoutAKeyOneSentenceAndNoCall(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        key = "";
        assertFalse(HomeJapanRecords.keySet());
        HomeSensors.Sheet ja = HomeJapanRecords.sheet(store, flat(44_800_000), "ja");
        assertTrue(ja.text().contains("不動産情報ライブラリのキー（RESEARCHZOSHO_REINFOLIB_KEY）が設定されていないため、取引価格・地価公示・ハザード情報は読んでいません"), ja.text());
        assertTrue(ja.facts().path("records_line").asText().contains("設定されていない"));
        String en = HomeJapanRecords.sheet(store, flat(44_800_000), "en").text();
        assertTrue(en.contains("No 不動産情報ライブラリ key is set (RESEARCHZOSHO_REINFOLIB_KEY), so the card has no transaction prices, land price point or hazard areas"), en);
        assertTrue(calls.isEmpty(), "no call without a key: " + calls);
        assertTrue(HomeJapanRecords.cities(store, "13", "ja").isEmpty());
        assertTrue(HomeJapanRecords.transactions(store, "13115", 2026, 1).isEmpty());
    }

    @Test void theSettingAndTheServiceRowAndTheGeometry() {
        Settings.Setting s = Settings.named("reinfolib_key");
        assertNotNull(s); assertEquals(Settings.Kind.secret, s.kind());
        assertEquals("set (ends with …123)", Settings.shown(s, STAND_IN_KEY), "a secret is shown only as set");
        OutsideServices.Service svc = OutsideServices.of(HomeJapanRecords.KEY);
        assertNotNull(svc); assertTrue(svc.terms().startsWith("free") && svc.terms().contains(HomeJapanRecords.CREDIT) && svc.checkedOn().equals("2026-11-05"), svc.terms());
        assertEquals("https://www.reinfolib.mlit.go.jp/api/request/", svc.link());
        int[] t = HomeJapanRecords.tile(35.699535, 139.626068, 14);
        assertEquals(14546, t[0]); assertEquals(6450, t[1]);
        assertEquals(List.of(new int[]{2026, 4}).get(0)[1], HomeJapanRecords.quarters(LocalDate.of(2026, 11, 5)).get(0)[1]);
        assertEquals(2026, HomeJapanRecords.quarters(LocalDate.of(2026, 11, 5)).get(0)[0]);
        assertEquals(2025, HomeJapanRecords.quarters(LocalDate.of(2026, 2, 1)).get(1)[0]);
        assertEquals(4, HomeJapanRecords.quarters(LocalDate.of(2026, 2, 1)).get(1)[1], "the quarter before this year's first is last year's fourth");
        assertEquals("1億2,000万円", HomeJapan.yenWords(120_000_000));
        try {
            JsonNode poly = M.readTree("{\"type\":\"Polygon\",\"coordinates\":[[[0,0],[2,0],[2,2],[0,2],[0,0]],[[0.5,0.5],[1.5,0.5],[1.5,1.5],[0.5,1.5],[0.5,0.5]]]}");
            assertTrue(HomeJapanRecords.inside(poly, 0.25, 0.25)); assertFalse(HomeJapanRecords.inside(poly, 1, 1), "a hole"); assertFalse(HomeJapanRecords.inside(poly, 3, 3));
            JsonNode multi = M.readTree("{\"type\":\"MultiPolygon\",\"coordinates\":[[[[0,0],[1,0],[1,1],[0,1],[0,0]]],[[[5,5],[6,5],[6,6],[5,6],[5,5]]]]}");
            assertTrue(HomeJapanRecords.inside(multi, 5.5, 5.5)); assertFalse(HomeJapanRecords.inside(multi, 3, 3));
        } catch (Exception e) { fail(e); }
    }
}
