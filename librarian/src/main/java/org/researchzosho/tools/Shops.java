package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.researchzosho.Config;
import org.researchzosho.Version;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The shops and the product databases a shopping run may use, each behind the person's own key where one is needed, each under
 * the terms it was read with on 2026-10-08: Yahoo! JAPAN Shopping (personal non-commercial use allowed, a credit line required),
 * 楽天市場 (products shown with their link), Open Icecat (specifications; "Specs Icecat" link required), the Open Food/Products Facts
 * databases (open data, a contact in the User-Agent), iFixit (repairability, non-commercial), Reddit through the person's own app.
 * Every lookup is one request. Nothing is stored beyond the run; a price carries the time it was seen.
 */
public final class Shops {
    private Shops() { }

    static final ObjectMapper M = new ObjectMapper();
    static final String UA = "ResearchZosho/" + (Version.number() == null ? "dev" : Version.number()) + " (https://researchzosho.org; a personal research tool)";
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();

    /** The addresses; a test points them at its own server. */
    public static final Map<String, String> BASE = new LinkedHashMap<>();
    static {
        BASE.put("yahoo", "https://shopping.yahooapis.jp/ShoppingWebService/V3/itemSearch");
        BASE.put("rakuten", "https://app.rakuten.co.jp/services/api/IchibaItem/Search/20220601");
        BASE.put("icecat", "https://live.icecat.biz/api");
        BASE.put("products", "https://world.openproductsfacts.org");
        BASE.put("food", "https://world.openfoodfacts.org");
        BASE.put("ifixit", "https://www.ifixit.com/api/2.0");
        BASE.put("reddit", "https://www.reddit.com");
        BASE.put("reddit_api", "https://oauth.reddit.com");
        BASE.put("ebay", "https://api.ebay.com/buy/browse/v1");
        BASE.put("ebay_token", "https://api.ebay.com/identity/v1/oauth2/token");
        BASE.put("etsy", "https://openapi.etsy.com/v3/application");
    }

    public static final String YAHOO_KEY = "RESEARCHZOSHO_YAHOO_JP_CLIENT_ID", RAKUTEN_APP = "RESEARCHZOSHO_RAKUTEN_APP_ID", RAKUTEN_KEY = "RESEARCHZOSHO_RAKUTEN_ACCESS_KEY",
            ICECAT_USER = "RESEARCHZOSHO_ICECAT_USER", REDDIT_ID = "RESEARCHZOSHO_REDDIT_CLIENT_ID", REDDIT_SECRET = "RESEARCHZOSHO_REDDIT_CLIENT_SECRET",
            EBAY_ID = "RESEARCHZOSHO_EBAY_CLIENT_ID", EBAY_SECRET = "RESEARCHZOSHO_EBAY_CLIENT_SECRET", ETSY_KEY = "RESEARCHZOSHO_ETSY_KEY";

    static String setting(String key) { String v = Config.get(key); return v == null ? "" : v.strip(); }
    public static boolean yahooOn() { return !setting(YAHOO_KEY).isEmpty(); }
    public static boolean rakutenOn() { return !setting(RAKUTEN_APP).isEmpty() && !setting(RAKUTEN_KEY).isEmpty(); }
    public static boolean icecatOn() { return !setting(ICECAT_USER).isEmpty(); }
    public static boolean redditOn() { return !setting(REDDIT_ID).isEmpty() && !setting(REDDIT_SECRET).isEmpty(); }
    public static boolean ebayOn() { return !setting(EBAY_ID).isEmpty() && !setting(EBAY_SECRET).isEmpty(); }
    public static boolean etsyOn() { return !setting(ETSY_KEY).isEmpty(); }

    /** One thing a shop lists right now. */
    public record Listing(String shop, String name, String brand, String price, String currency, String url, String seller, String code, boolean inStock, String seenAt) { }
    /** A product's specifications from a catalogue. */
    public record Spec(String source, String title, String brand, String model, String category, String released, String endOfLife, List<String[]> features, String url) { }
    /** What an owner wrote. */
    public record Voice(String where, String title, String excerpt, int score, String date, String url) { }
    /** A repair verdict. */
    public record Repair(String device, String score, String url) { }

    /** A shop's text on one line, with nothing that could read as a fence or an instruction to the model. */
    public static String clean(String s) { return s == null ? "" : s.replaceAll("[\\r\\n\\t]+", " ").replace("<<<", "‹‹‹").replace(">>>", "›››").replaceAll("\\s{2,}", " ").strip(); }

    static String now() { return DateTimeFormatter.ISO_INSTANT.format(Instant.now().atZone(ZoneOffset.UTC).withSecond(0).withNano(0)); }
    static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }

    static JsonNode getJson(String url, Map<String, String> headers) throws IOException {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", UA).header("Accept", "application/json");
            headers.forEach(b::header);
            HttpResponse<String> r = HTTP.send(b.GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) {
                String said = "";
                try { JsonNode body = M.readTree(r.body()); said = body.path("Message").asText(body.path("message").asText(body.path("error").asText(""))); } catch (Exception notJson) { /* the status is the answer */ }
                throw new IOException("HTTP " + r.statusCode() + (said.isBlank() ? "" : ": " + clean(said.length() > 200 ? said.substring(0, 200) : said)));
            }
            return M.readTree(r.body());
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }

    // ---- marketplaces: the long tail, used, vintage, handmade, auctions ----

    /** eBay's marketplace id for a country; the US one where eBay has none (Japan buys on ebay.com). */
    public static String ebayMarket(String market) {
        return switch (market) { case "GB" -> "EBAY_GB"; case "DE" -> "EBAY_DE"; case "FR" -> "EBAY_FR"; case "IT" -> "EBAY_IT"; case "ES" -> "EBAY_ES"; case "AU" -> "EBAY_AU"; case "CA" -> "EBAY_CA"; case "AT" -> "EBAY_AT"; case "CH" -> "EBAY_CH"; case "NL" -> "EBAY_NL"; case "BE" -> "EBAY_BE"; case "IE" -> "EBAY_IE"; case "PL" -> "EBAY_PL"; default -> "EBAY_US"; };
    }

    private static volatile String ebayToken; private static volatile long ebayTokenUntil;

    /** An application token (client credentials), kept until shortly before it expires. */
    static String ebayToken() throws IOException {
        if (ebayToken != null && System.currentTimeMillis() < ebayTokenUntil) return ebayToken;
        try {
            String basic = Base64.getEncoder().encodeToString((setting(EBAY_ID) + ":" + setting(EBAY_SECRET)).getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE.get("ebay_token"))).timeout(Duration.ofSeconds(30)).header("User-Agent", UA).header("Authorization", "Basic " + basic)
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&scope=" + enc("https://api.ebay.com/oauth/api_scope"))).build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) throw new IOException("eBay token HTTP " + r.statusCode());
            JsonNode d = M.readTree(r.body());
            ebayToken = d.path("access_token").asText(""); ebayTokenUntil = System.currentTimeMillis() + Math.max(60, d.path("expires_in").asLong(7200) - 120) * 1000;
            return ebayToken;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }

    /** eBay (Browse API): listings by words in the market, fixed price and auctions, each with its condition, seller and end time. */
    public static List<Listing> ebay(String query, String market, int limit) throws IOException {
        String url = BASE.get("ebay") + "/item_summary/search?q=" + enc(query) + "&limit=" + Math.max(1, Math.min(limit, 50));
        JsonNode d = getJson(url, Map.of("Authorization", "Bearer " + ebayToken(), "X-EBAY-C-MARKETPLACE-ID", ebayMarket(market)));
        List<Listing> out = new ArrayList<>();
        String seen = now();
        for (JsonNode h : d.path("itemSummaries")) {
            StringBuilder seller = new StringBuilder(h.path("seller").path("username").asText(""));
            if (h.path("seller").has("feedbackPercentage")) seller.append(", ").append(h.path("seller").path("feedbackPercentage").asText()).append("% positive");
            String cond = h.path("condition").asText("");
            boolean auction = false; for (JsonNode o : h.path("buyingOptions")) if (o.asText().equals("AUCTION")) auction = true;
            String note = (cond.isEmpty() ? "" : cond) + (auction ? (cond.isEmpty() ? "" : ", ") + "auction" + (h.has("itemEndDate") ? " ending " + h.path("itemEndDate").asText() : "") : "")
                    + (h.path("itemLocation").has("country") ? (cond.isEmpty() && !auction ? "" : ", ") + "ships from " + h.path("itemLocation").path("country").asText() : "");
            out.add(new Listing("eBay" + (auction ? " (auction)" : ""), h.path("title").asText("") + (note.isEmpty() ? "" : " [" + note + "]"), "", h.path("price").path("value").asText(""), h.path("price").path("currency").asText(""),
                    h.path("itemWebUrl").asText(""), seller.toString(), "", true, seen));
        }
        return out;
    }

    /** Etsy (v3, the person's own app key): active listings by words — the handmade and vintage tail. */
    public static List<Listing> etsy(String query, int limit) throws IOException {
        JsonNode d = getJson(BASE.get("etsy") + "/listings/active?keywords=" + enc(query) + "&limit=" + Math.max(1, Math.min(limit, 100)), Map.of("x-api-key", setting(ETSY_KEY)));
        List<Listing> out = new ArrayList<>();
        String seen = now();
        for (JsonNode h : d.path("results")) {
            JsonNode p = h.path("price");
            String price = p.has("amount") && p.path("divisor").asInt(100) > 0 ? String.format(Locale.ROOT, "%.2f", p.path("amount").asDouble() / p.path("divisor").asInt(100)) : "";
            out.add(new Listing("Etsy", h.path("title").asText(""), "", price, p.path("currency_code").asText(""), h.path("url").asText(""), h.has("shop_id") ? "shop " + h.path("shop_id").asText() : "", "", h.path("quantity").asInt(1) > 0, seen));
        }
        return out;
    }

    /** One place to search by hand, with the search already typed in. */
    public record Place(String name, String what, String url) { }

    /**
     * The marketplaces the library cannot ask (no public API, or terms that forbid it) and the keyed ones when the key is missing, as search
     * links for the person's market: the person opens them, the run never reads them.
     */
    public static List<Place> places(String query, String market, boolean books) {
        String q = enc(query);
        List<Place> out = new ArrayList<>();
        if (market.equals("JP")) {
            out.add(new Place("メルカリ", "used, from people", "https://jp.mercari.com/search?keyword=" + q));
            out.add(new Place("ヤフオク!", "auctions", "https://auctions.yahoo.co.jp/search/search?p=" + q));
            out.add(new Place("ラクマ", "used, from people", "https://fril.jp/s?query=" + q));
            if (books) out.add(new Place("日本の古本屋", "second-hand bookshops' stock", "https://www.kosho.or.jp/products/list.php?name=" + q));
            if (!yahooOn()) out.add(new Place("Yahoo!ショッピング", "new, from shops", "https://shopping.yahoo.co.jp/search?p=" + q));
            if (!rakutenOn()) out.add(new Place("楽天市場", "new, from shops", "https://search.rakuten.co.jp/search/mall/" + q + "/"));
        } else {
            if (market.equals("US")) out.add(new Place("Mercari", "used, from people", "https://www.mercari.com/search/?keyword=" + q));
            if (!ebayOn()) out.add(new Place("eBay", "used, new and auctions", "https://www." + ebayHost(market) + "/sch/i.html?_nkw=" + q));
            if (!etsyOn()) out.add(new Place("Etsy", "handmade and vintage", "https://www.etsy.com/search?q=" + q));
            if (books) out.add(new Place("AbeBooks", "second-hand and out-of-print books", "https://www.abebooks.com/servlet/SearchResults?kn=" + q));
        }
        return out;
    }

    static String ebayHost(String market) {
        return switch (market) { case "GB" -> "ebay.co.uk"; case "DE" -> "ebay.de"; case "FR" -> "ebay.fr"; case "IT" -> "ebay.it"; case "ES" -> "ebay.es"; case "AU" -> "ebay.com.au"; case "CA" -> "ebay.ca"; case "AT" -> "ebay.at"; case "CH" -> "ebay.ch"; case "NL" -> "ebay.nl"; case "IE" -> "ebay.ie"; case "PL" -> "ebay.pl"; default -> "ebay.com"; };
    }

    // ---- shops ----

    /** Yahoo! JAPAN Shopping: items by words, yen, with the seller and the JAN code where the listing carries one. */
    public static List<Listing> yahoo(String query, int limit) throws IOException {
        JsonNode d = getJson(BASE.get("yahoo") + "?appid=" + enc(setting(YAHOO_KEY)) + "&query=" + enc(query) + "&results=" + Math.max(1, Math.min(limit, 30)) + "&in_stock=true", Map.of());
        List<Listing> out = new ArrayList<>();
        String seen = now();
        for (JsonNode h : d.path("hits")) {
            out.add(new Listing("Yahoo!ショッピング", h.path("name").asText(""), h.path("brand").path("name").asText(""), h.path("price").asText(""), "JPY", h.path("url").asText(""),
                    h.path("seller").path("name").asText(""), h.path("janCode").asText(""), !h.has("inStock") || h.path("inStock").asBoolean(true), seen));
        }
        return out;
    }

    /** 楽天市場: items by words, yen, each with its link as the terms require. */
    public static List<Listing> rakuten(String query, int limit) throws IOException {
        JsonNode d = getJson(BASE.get("rakuten") + "?applicationId=" + enc(setting(RAKUTEN_APP)) + "&accessKey=" + enc(setting(RAKUTEN_KEY)) + "&keyword=" + enc(query) + "&hits=" + Math.max(1, Math.min(limit, 30)) + "&availability=1&format=json", Map.of());
        List<Listing> out = new ArrayList<>();
        String seen = now();
        for (JsonNode w : d.path("Items")) {
            JsonNode i = w.has("Item") ? w.path("Item") : w;
            out.add(new Listing("楽天市場", i.path("itemName").asText(""), "", i.path("itemPrice").asText(""), "JPY", i.path("itemUrl").asText(""), i.path("shopName").asText(""), i.path("itemCode").asText(""), i.path("availability").asInt(1) == 1, seen));
        }
        return out;
    }

    // ---- specifications and identity ----

    /** Open Icecat: a product's datasheet by barcode, or by brand and model; null when the catalogue has none. */
    public static Spec icecat(String gtin, String brand, String model, String language) throws IOException {
        String q = gtin != null && !gtin.isBlank() ? "&GTIN=" + enc(gtin.replaceAll("\\D", "")) : "&Brand=" + enc(brand) + "&ProductCode=" + enc(model);
        JsonNode d;
        try { d = getJson(BASE.get("icecat") + "?UserName=" + enc(setting(ICECAT_USER)) + "&Language=" + enc(language == null || language.isBlank() ? "EN" : language.toUpperCase(Locale.ROOT)) + q, Map.of()); }
        catch (IOException e) {
            if (e.getMessage().contains("HTTP 400") || e.getMessage().contains("HTTP 404")) return null;
            if (e.getMessage().contains("HTTP 403") && e.getMessage().contains("app_key")) throw new IOException("the datasheet is in Icecat's paid Full catalogue, not the open one, which carries the brands that sponsor it (Kingston, Philips, HP and others)");
            throw e;
        }
        JsonNode g = d.path("data").path("GeneralInfo");
        if (g.isMissingNode() || g.path("Title").asText("").isBlank()) return null;
        List<String[]> features = new ArrayList<>();
        for (JsonNode group : d.path("data").path("FeaturesGroups")) for (JsonNode f : group.path("Features")) {
            String name = f.path("Feature").path("Name").path("Value").asText(""), value = f.path("PresentationValue").asText(f.path("Value").asText(""));
            if (!name.isBlank() && !value.isBlank() && features.size() < 40) features.add(new String[]{name, value});
        }
        String id = g.path("IcecatId").asText("");
        return new Spec("Open Icecat", g.path("Title").asText(""), g.path("Brand").asText(""), g.path("ProductName").asText(""), g.path("Category").path("Name").path("Value").asText(""),
                g.path("ReleaseDate").asText(""), g.path("EndOfLifeDate").asText(""), features, id.isEmpty() ? "https://icecat.biz" : "https://icecat.biz/p/" + id);
    }

    /** The Open Food, Products, Beauty and Pet Food Facts databases: a product by barcode. */
    public static Listing openFactsByCode(String code) throws IOException {
        for (String base : new String[]{BASE.get("products"), BASE.get("food")}) {
            try {
                JsonNode d = getJson(base + "/api/v2/product/" + enc(code.replaceAll("\\D", "")) + ".json?fields=code,product_name,brands,quantity,categories", Map.of());
                if (d.path("status").asInt(0) == 1) { JsonNode p = d.path("product"); return new Listing(base.contains("food") ? "Open Food Facts" : "Open Products Facts", p.path("product_name").asText("") + (p.path("quantity").asText("").isBlank() ? "" : " " + p.path("quantity").asText()), p.path("brands").asText(""), "", "", base + "/product/" + p.path("code").asText(""), "", p.path("code").asText(""), true, now()); }
            } catch (IOException ignored) { }
        }
        return null;
    }

    /** The same databases by words: products with their barcodes and brands. */
    public static List<Listing> openFactsSearch(String query, int limit) throws IOException {
        List<Listing> out = new ArrayList<>();
        for (String base : new String[]{BASE.get("products"), BASE.get("food")}) {
            try {
                JsonNode d = getJson(base + "/cgi/search.pl?search_terms=" + enc(query) + "&json=1&page_size=" + Math.max(1, Math.min(limit, 20)) + "&fields=code,product_name,brands,quantity", Map.of());
                for (JsonNode p : d.path("products")) if (!p.path("product_name").asText("").isBlank()) out.add(new Listing(base.contains("food") ? "Open Food Facts" : "Open Products Facts", p.path("product_name").asText("") + (p.path("quantity").asText("").isBlank() ? "" : " " + p.path("quantity").asText()), p.path("brands").asText(""), "", "", base + "/product/" + p.path("code").asText(""), "", p.path("code").asText(""), true, now()));
            } catch (IOException ignored) { }
            if (out.size() >= limit) break;
        }
        return out;
    }

    // ---- repair ----

    /** iFixit: the device the words name and its repairability score, where iFixit has scored it. */
    public static List<Repair> ifixit(String query) throws IOException {
        JsonNode d = getJson(BASE.get("ifixit") + "/search/" + enc(query).replace("+", "%20") + "?filter=device&limit=5", Map.of());
        List<Repair> out = new ArrayList<>();
        for (JsonNode r : d.path("results")) {
            String title = r.path("title").asText(""), url = r.path("url").asText("");
            if (title.isBlank()) continue;
            String score = "";
            try { JsonNode w = getJson(BASE.get("ifixit") + "/wikis/CATEGORY/" + enc(title).replace("+", "%20"), Map.of()); if (w.hasNonNull("repairability_score")) score = w.path("repairability_score").asText(); } catch (IOException ignored) { }
            out.add(new Repair(title, score, url.isBlank() ? "https://www.ifixit.com/Device/" + enc(title).replace("+", "_") : url));
            if (out.size() >= 3) break;
        }
        return out;
    }

    // ---- owners ----

    private static String redditToken; private static Instant redditTokenUntil;

    /** Reddit through the person's own app: posts by words, newest and most upvoted first. */
    public static List<Voice> reddit(String query, int limit) throws IOException {
        String token = redditToken();
        JsonNode d = getJson(BASE.get("reddit_api") + "/search?q=" + enc(query) + "&limit=" + Math.max(1, Math.min(limit, 25)) + "&sort=relevance&t=year&type=link", Map.of("Authorization", "Bearer " + token));
        List<Voice> out = new ArrayList<>();
        for (JsonNode c : d.path("data").path("children")) {
            JsonNode p = c.path("data");
            String text = p.path("selftext").asText("").replaceAll("\\s+", " ").strip();
            long created = p.path("created_utc").asLong(0);
            out.add(new Voice("r/" + p.path("subreddit").asText(""), p.path("title").asText(""), text.length() > 300 ? text.substring(0, 300) + "…" : text, p.path("score").asInt(0),
                    created > 0 ? Instant.ofEpochSecond(created).atZone(ZoneOffset.UTC).toLocalDate().toString() : "", "https://www.reddit.com" + p.path("permalink").asText("")));
        }
        return out;
    }

    static synchronized String redditToken() throws IOException {
        if (redditToken != null && redditTokenUntil != null && Instant.now().isBefore(redditTokenUntil)) return redditToken;
        try {
            String basic = Base64.getEncoder().encodeToString((setting(REDDIT_ID) + ":" + setting(REDDIT_SECRET)).getBytes(StandardCharsets.UTF_8));
            HttpRequest req = HttpRequest.newBuilder(URI.create(BASE.get("reddit") + "/api/v1/access_token")).timeout(Duration.ofSeconds(30)).header("User-Agent", UA).header("Authorization", "Basic " + basic)
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build();
            HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() != 200) throw new IOException("Reddit did not give a token (HTTP " + r.statusCode() + ")");
            JsonNode d = M.readTree(r.body());
            redditToken = d.path("access_token").asText(""); redditTokenUntil = Instant.now().plusSeconds(Math.max(60, d.path("expires_in").asLong(3600) - 60));
            if (redditToken.isEmpty()) throw new IOException("Reddit did not give a token");
            return redditToken;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted"); }
    }

    /** For a test: forget the token. */
    static synchronized void forgetRedditToken() { redditToken = null; redditTokenUntil = null; }
}
