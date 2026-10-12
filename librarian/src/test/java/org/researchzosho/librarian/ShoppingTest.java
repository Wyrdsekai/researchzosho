package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.librarian.profiles.ShoppingProfile;
import org.researchzosho.tools.Fetch;
import org.researchzosho.tools.ProductSafetyTool;
import org.researchzosho.tools.ProductSearchTool;
import org.researchzosho.tools.Recalls;
import org.researchzosho.tools.Shops;
import org.researchzosho.tools.WebSearchTool;
import org.researchzosho.tools.Terms;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The shopping field (0.6.0): it joins by the question's words, its tools ask the market's shops and the catalogues behind the person's keys, and the sites whose terms forbid machine reading are pointed at. */
class ShoppingTest {

    static final ObjectMapper M = new ObjectMapper();
    HttpServer server; Map<String, String> savedBase = new HashMap<>(); String realHome; Path savedOverride;

    @BeforeEach void stub(@TempDir Path tmp) throws Exception {
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        savedBase.putAll(Shops.BASE); savedOverride = Recalls.override; Recalls.override = tmp.resolve("recalls");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        serve("/yahoo", "{\"totalResultsAvailable\":2,\"hits\":[{\"name\":\"Kestrel 7ポートUSBハブ KH-7\",\"price\":3980,\"url\":\"https://store.shopping.yahoo.co.jp/kestrel/kh-7.html\",\"seller\":{\"name\":\"Kestrel Direct\"},\"janCode\":\"4901234567894\",\"brand\":{\"name\":\"Kestrel\"},\"inStock\":true},"
                + "{\"name\":\"Heron HDMIケーブル 2m\",\"price\":1280,\"url\":\"https://store.shopping.yahoo.co.jp/heron/hc-2.html\",\"seller\":{\"name\":\"Heron\"},\"janCode\":\"\",\"inStock\":false}]}");
        serve("/rakuten", "{\"Items\":[{\"Item\":{\"itemName\":\"Kestrel USBハブ 7ポート KH-7 ブラック\",\"itemPrice\":3680,\"itemUrl\":\"https://item.rakuten.co.jp/kestrelshop/kh-7/\",\"shopName\":\"Kestrelショップ\",\"itemCode\":\"kestrelshop:10000123\",\"availability\":1}}]}");
        serve("/icecat", "{\"data\":{\"GeneralInfo\":{\"IcecatId\":12345,\"Title\":\"Kestrel KH-7 USB hub 7 ports\",\"Brand\":\"Kestrel\",\"ProductName\":\"KH-7\",\"ReleaseDate\":\"2024-03-01\",\"Category\":{\"Name\":{\"Value\":\"Interface hubs\"}}},"
                + "\"FeaturesGroups\":[{\"Features\":[{\"Feature\":{\"Name\":{\"Value\":\"Number of USB ports\"}},\"PresentationValue\":\"7\"},{\"Feature\":{\"Name\":{\"Value\":\"Power delivery\"}},\"PresentationValue\":\"60 W\"}]}]}}");
        serve("/icecat-full", 403, "{\"statusCode\":403,\"Message\":\"To access Full Icecat content, an app_key is required. Full Icecat users can find it on their My Profile page.\"}");
        serve("/products/cgi/search.pl", "{\"count\":1,\"products\":[{\"code\":\"4901234567894\",\"product_name\":\"Kestrel KH-7 USB hub\",\"brands\":\"Kestrel\"}]}");
        serve("/products/api/v2/product/4901234567894.json", "{\"status\":1,\"product\":{\"code\":\"4901234567894\",\"product_name\":\"Kestrel KH-7 USB hub\",\"brands\":\"Kestrel\"}}");
        serve("/food/cgi/search.pl", "{\"count\":0,\"products\":[]}");
        serve("/ifixit/search/", "{\"results\":[{\"title\":\"Kestrel KH-7\",\"url\":\"https://www.ifixit.com/Device/Kestrel_KH-7\",\"dataType\":\"device\"}]}");
        serve("/ifixit/wikis/", "{\"title\":\"Kestrel KH-7\",\"repairability_score\":7}");
        serve("/search", "{\"results\":[{\"title\":\"Kestrel KH-7 hub after six months — any regrets? : r/UsbCHardware\",\"url\":\"https://www.reddit.com/r/UsbCHardware/comments/abc123/kestrel_kh7/\",\"content\":\"Still solid, the case gets warm when all seven ports are busy.\"}]}");
        serve("/reddit/api/v1/access_token", "{\"access_token\":\"tok\",\"expires_in\":3600}");
        serve("/ebay_token", "{\"access_token\":\"ebaytok\",\"expires_in\":7200}");
        serve("/ebay/item_summary/search", "{\"total\":2,\"itemSummaries\":[{\"title\":\"Kestrel KH-7 USB hub, boxed\",\"price\":{\"value\":\"21.50\",\"currency\":\"USD\"},\"condition\":\"Used\",\"buyingOptions\":[\"AUCTION\"],\"itemEndDate\":\"2026-10-12T18:00:00.000Z\",\"seller\":{\"username\":\"heronattic\",\"feedbackPercentage\":\"99.4\"},\"itemLocation\":{\"country\":\"US\"},\"itemWebUrl\":\"https://www.ebay.com/itm/1\"},"
                + "{\"title\":\"Kestrel KH-7 hub new sealed\",\"price\":{\"value\":\"34.00\",\"currency\":\"USD\"},\"condition\":\"New\",\"buyingOptions\":[\"FIXED_PRICE\"],\"seller\":{\"username\":\"wrenstore\"},\"itemWebUrl\":\"https://www.ebay.com/itm/2\"}]}");
        serve("/etsy/listings/active", "{\"count\":1,\"results\":[{\"listing_id\":7,\"title\":\"Hand-stitched leather sleeve for a 7-port hub\",\"url\":\"https://www.etsy.com/listing/7\",\"price\":{\"amount\":4250,\"divisor\":100,\"currency_code\":\"USD\"},\"quantity\":3,\"shop_id\":99}]}");
        serve("/reddit_api/search", "{\"data\":{\"children\":[{\"data\":{\"title\":\"Kestrel KH-7 after a year\",\"selftext\":\"Still works, runs warm under load.\",\"subreddit\":\"UsbCHardware\",\"permalink\":\"/r/UsbCHardware/comments/x1/kestrel_kh7/\",\"score\":42,\"created_utc\":1760000000}}]}}");
        server.start();
        Shops.BASE.put("yahoo", base + "/yahoo"); Shops.BASE.put("rakuten", base + "/rakuten"); Shops.BASE.put("icecat", base + "/icecat"); Shops.BASE.put("products", base + "/products"); Shops.BASE.put("food", base + "/food");
        Shops.BASE.put("ifixit", base + "/ifixit"); Shops.BASE.put("reddit", base + "/reddit"); Shops.BASE.put("reddit_api", base + "/reddit_api");
        Shops.BASE.put("ebay", base + "/ebay"); Shops.BASE.put("ebay_token", base + "/ebay_token"); Shops.BASE.put("etsy", base + "/etsy");
    }

    @AfterEach void restore() { server.stop(0); Shops.BASE.putAll(savedBase); Recalls.override = savedOverride; System.setProperty("user.home", realHome); Config.invalidate(); }

    void serve(String path, int status, String body) {
        server.createContext(path, x -> { byte[] b = body.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json"); x.sendResponseHeaders(status, b.length); try (OutputStream o = x.getResponseBody()) { o.write(b); } });
    }

    void serve(String path, String body) {
        server.createContext(path, x -> { byte[] b = body.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", "application/json"); x.sendResponseHeaders(200, b.length); try (OutputStream o = x.getResponseBody()) { o.write(b); } });
    }

    static ObjectNode args(String json) throws Exception { return (ObjectNode) M.readTree(json); }

    @Test
    void theFieldJoinsOnTheQuestionsWordsAndItsRulesNameTheTools() {
        ShoppingProfile p = new ShoppingProfile();
        assertTrue(p.applies("Which USB-C hub should I buy for a laptop with one port, under 5000 yen?"));
        assertTrue(p.applies("ノートPC用のUSBハブ、5000円以下でおすすめは？"));
        assertFalse(p.applies("When did the Meiji restoration begin?"));
        assertTrue(Profiles.known().stream().anyMatch(x -> x.name().equals("shopping")), "registered with the other fields");
        assertTrue(p.register().contains("product_search") && p.register().contains("product_safety") && p.register().contains("never read"));
        assertTrue(p.writerRules().contains("as of") && p.criticRules().contains("dropped"));
        assertTrue(p.chatNote().contains("one question at a time") && p.chatNote().contains("library_owned") && p.chatNote().contains("re-cut") && p.chatNote().contains("Follow-up to"), "the needs-and-critique loop is the chat's: " + p.chatNote());
    }

    @Test
    void theMarketplacesAnswerBehindKeysAndTheRestAreTypedInSearchLinks(@TempDir Path tmp) throws Exception {
        ProductSearchTool t = new ProductSearchTool();
        String noKeys = t.execute(args("{\"kind\":\"marketplaces\",\"query\":\"Kestrel KH-7\",\"market\":\"US\",\"books\":true}"));
        assertTrue(noKeys.startsWith("Market US. No marketplace the library can ask answered") && noKeys.contains("ebay_client_id with ebay_client_secret, and etsy_key") && noKeys.contains("- Mercari — used, from people — https://www.mercari.com/search/?keyword=Kestrel+KH-7")
                && noKeys.contains("- eBay — used, new and auctions — https://www.ebay.com/sch/i.html?_nkw=Kestrel+KH-7") && noKeys.contains("- AbeBooks — second-hand and out-of-print books — https://www.abebooks.com/servlet/SearchResults?kn=Kestrel+KH-7") && noKeys.contains("watched search"), noKeys);
        String jp = t.execute(args("{\"kind\":\"marketplaces\",\"query\":\"ケストレル ハブ\",\"market\":\"JP\"}"));
        assertTrue(jp.contains("- メルカリ — used, from people — https://jp.mercari.com/search?keyword=") && jp.contains("- ヤフオク! — auctions — https://auctions.yahoo.co.jp/search/search?p=") && jp.contains("- ラクマ") && !jp.contains("日本の古本屋") && !jp.contains("Mercari —"), jp);
        Config.set(Shops.EBAY_ID, "id"); Config.set(Shops.EBAY_SECRET, "secret"); Config.set(Shops.ETSY_KEY, "etsykey");
        String keyed = t.execute(args("{\"kind\":\"marketplaces\",\"query\":\"Kestrel KH-7\",\"market\":\"GB\"}"));
        assertTrue(keyed.contains("3 listing(s)") && keyed.contains("Kestrel KH-7 USB hub, boxed [Used, auction ending 2026-10-12T18:00:00.000Z, ships from US] — 21.50 USD at eBay (auction) (heronattic, 99.4% positive)")
                && keyed.contains("Kestrel KH-7 hub new sealed [New] — 34.00 USD at eBay (wrenstore)") && keyed.contains("Hand-stitched leather sleeve for a 7-port hub — 42.50 USD at Etsy (shop 99)") && keyed.contains("an auction's price is the bid at the time shown")
                && !keyed.contains("- eBay —") && !keyed.contains("- Etsy —") && !keyed.contains("Mercari"), keyed);
        assertEquals("EBAY_GB", Shops.ebayMarket("GB")); assertEquals("EBAY_US", Shops.ebayMarket("JP"));
    }

    @Test
    void theShopsAnswerForThePersonsMarketBehindTheirKeysAndSayWhenAKeyIsMissing(@TempDir Path tmp) throws Exception {
        ProductSearchTool t = new ProductSearchTool();
        Config.set(Settings.MARKET, "JP");
        String noKeys = t.execute(args("{\"kind\":\"shops\",\"query\":\"USB hub 7 port\"}"));
        assertTrue(noKeys.contains("Market JP") && noKeys.contains("yahoo_jp_client_id") && noKeys.contains("rakuten_app_id") && noKeys.contains("Nothing listed"), noKeys);
        Config.set(Shops.YAHOO_KEY, "yahoo-test"); Config.set(Shops.RAKUTEN_APP, "rk-app"); Config.set(Shops.RAKUTEN_KEY, "rk-key");
        String jp = t.execute(args("{\"kind\":\"shops\",\"query\":\"USB hub 7 port\"}"));
        assertTrue(jp.contains("3 listing(s)") && jp.contains("3980 JPY at Yahoo!ショッピング (Kestrel Direct)") && jp.contains("code 4901234567894") && jp.contains("3680 JPY at 楽天市場") && jp.contains("not in stock") && jp.contains("seen 20"), jp);
        assertTrue(jp.contains("Web Services by Yahoo! JAPAN") && jp.contains("cited with its link"), "the credits the terms ask for: " + jp);
        String us = t.execute(args("{\"kind\":\"shops\",\"query\":\"USB hub\",\"market\":\"US\"}"));
        assertTrue(us.contains("Market US") && us.contains("no shop in the US market") && us.contains("point at the shop's own page"), us);
        String specsNoUser = t.execute(args("{\"kind\":\"specs\",\"brand\":\"Kestrel\",\"model\":\"KH-7\"}"));
        assertTrue(specsNoUser.contains("icecat_user"), specsNoUser);
        Config.set(Shops.ICECAT_USER, "someone");
        String specs = t.execute(args("{\"kind\":\"specs\",\"brand\":\"Kestrel\",\"model\":\"KH-7\"}"));
        Shops.BASE.put("icecat", "http://127.0.0.1:" + server.getAddress().getPort() + "/icecat-full");
        String paid = t.execute(args("{\"kind\":\"specs\",\"brand\":\"Heron\",\"model\":\"HC-2\"}"));
        assertTrue(paid.contains("Open Icecat: the datasheet is in Icecat's paid Full catalogue, not the open one") && !paid.contains("did not answer"), paid);
        Shops.BASE.put("icecat", "http://127.0.0.1:" + server.getAddress().getPort() + "/icecat");
        assertTrue(specs.contains("Datasheet (Open Icecat): Kestrel KH-7 USB hub 7 ports") && specs.contains("released 2024-03-01") && specs.contains("- Power delivery: 60 W") && specs.contains("Open Icecat (https://icecat.biz)"), specs);
        String identity = t.execute(args("{\"kind\":\"identity\",\"query\":\"Kestrel KH-7\"}"));
        assertTrue(identity.contains("code 4901234567894") && identity.contains("brand Kestrel"), identity);
        String repair = t.execute(args("{\"kind\":\"repair\",\"query\":\"Kestrel KH-7\"}"));
        assertTrue(repair.contains("repairability 7/10"), repair);
        var override = WebSearchTool.class.getDeclaredField("endpointOverride"); override.setAccessible(true); override.set(null, "http://127.0.0.1:" + server.getAddress().getPort());
        String ownersNoApp;
        try { ownersNoApp = t.execute(args("{\"kind\":\"owners\",\"query\":\"Kestrel KH-7\"}")); } finally { override.set(null, null); }
        assertTrue(ownersNoApp.startsWith("No Reddit app is set (the settings reddit_client_id") && ownersNoApp.contains("search engine's snippets of Reddit threads") && ownersNoApp.contains("any regrets? : r/UsbCHardware")
                && ownersNoApp.contains("https://www.reddit.com/r/UsbCHardware/comments/abc123/kestrel_kh7/") && ownersNoApp.contains("case gets warm"), ownersNoApp);
        assertEquals("Reddit", Terms.forbids("https://old.reddit.com/r/UsbCHardware/comments/abc123/"), "Reddit's pages are pointed at, never read");
        Config.set(Shops.REDDIT_ID, "id"); Config.set(Shops.REDDIT_SECRET, "secret");
        String owners = t.execute(args("{\"kind\":\"owners\",\"query\":\"Kestrel KH-7\"}"));
        assertTrue(owners.contains("r/UsbCHardware") && owners.contains("score 42") && owners.contains("runs warm under load") && owners.contains("weigh it, never count it"), owners);
    }

    @Test
    void safetyReadsTheNoticesOnFileAndTheLedger(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        Files.createDirectories(Recalls.override);
        Files.writeString(Recalls.override.resolve("cpsc.jsonl"), "{\"feed\":\"cpsc\",\"id\":\"26-001\",\"date\":\"2026-09-01\",\"title\":\"Kestrel Recalls USB Hubs Due to Fire Hazard\",\"brand\":\"Kestrel Direct\",\"model\":\"KH-7\",\"barcodes\":[\"4901234567894\"],\"text\":\"Kestrel 7-port USB hubs, model KH-7.\",\"url\":\"https://www.cpsc.gov/Recalls/2026/kestrel\",\"country\":\"US\"}\n", StandardCharsets.UTF_8);
        Path csv = tmp.resolve("Order History.csv");
        Files.writeString(csv, "\"Website\",\"Order ID\",\"Order Date\",\"Currency\",\"Unit Price\",\"Total Amount\",\"ASIN\",\"Product Name\",\"Original Quantity\",\"Order Status\"\n\"Amazon.com\",\"113-0000001-0000001\",\"2025-05-20T14:02:11Z\",\"USD\",\"21.50\",\"21.50\",\"B07EXAMPLE1\",\"Kestrel 7-Port USB Hub with Power Switch (KH-7)\",\"1\",\"Closed\"\n", StandardCharsets.UTF_8);
        Owned.importPath(store, csv, "amazon", "");
        ProductSafetyTool s = new ProductSafetyTool(store);
        String byModel = s.execute(args("{\"name\":\"Kestrel KH-7 USB hub\",\"brand\":\"Kestrel\",\"model\":\"KH-7\"}"));
        assertTrue(byModel.contains("1 recall notice(s) on file may concern") && byModel.contains("matched by the brand kestrel and the model KH-7") && byModel.contains("already owns something that reads like it") && byModel.contains("not recommended again"), byModel);
        String byCode = s.execute(args("{\"name\":\"a hub\",\"barcode\":\"4901234567894\"}"));
        assertTrue(byCode.contains("matched by the barcode 4901234567894"), byCode);
        String none = s.execute(args("{\"name\":\"Heron HDMI cable 2m\",\"brand\":\"Heron\"}"));
        assertTrue(none.contains("No recall notice on file names") && none.contains("not \"safe\""), none);
    }

    @Test
    void sitesWhoseTermsForbidMachineReadingArePointedAtNotRead() {
        for (String u : new String[]{"https://www.amazon.com/dp/B07EXAMPLE1", "https://www.amazon.co.jp/dp/B07EXAMPLE1", "https://www.nytimes.com/wirecutter/reviews/best-usb-hub/", "https://www.consumerreports.org/usb-hubs/", "https://kakaku.com/item/K0001/", "https://www.rtings.com/monitor/reviews/x"}) {
            String why = Fetch.Policy.DEFAULT.leftOut(u);
            assertNotNull(why, u); assertTrue(why.contains("terms") && why.contains("point"), why);
            assertNull(Fetch.Policy.PERSON.leftOut(u), "the person's own act, like a browser: " + u);
        }
        assertNull(Fetch.Policy.DEFAULT.leftOut("https://www.ifixit.com/Device/Kestrel_KH-7"));
    }
}
