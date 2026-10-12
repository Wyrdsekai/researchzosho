package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code product_search}: the real products a shopping run may put in its table — what shops list right now with the price and the
 * time it was seen (kind=shops), a product's specifications from a catalogue (specs), a product's barcode and brand (identity),
 * what owners wrote (owners) and whether it can be repaired (repair). The shops are the market's own and each is behind the person's
 * key; a market with no keyed shop says so, and the run points at shops through the web instead of guessing. Nothing is stored.
 */
public final class ProductSearchTool implements Tool {

    static final int MOST = 20, MOST_CALLS = 30;
    static final ObjectMapper M = new ObjectMapper();
    private final AtomicInteger calls = new AtomicInteger();

    @Override public String name() { return "product_search"; }

    @Override public String description() {
        return "Products, for a shopping question: only what this tool returns may be recommended, since it is for sale now. kind=shops: what the market's shops "
                + "list for the words, each with its price, currency, shop, seller, link, barcode when known, and the time the price was seen (Japan: Yahoo! JAPAN "
                + "Shopping and 楽天市場, with the person's keys; other markets: no keyed shop, so search the web and point at the shop's page). kind=marketplaces: the long "
                + "tail — used, vintage, handmade, auctions, out of print: eBay listings with the person's keys, and for the marketplaces that cannot be asked (Mercari, "
                + "メルカリ, ヤフオク!, ラクマ, 日本の古本屋, AbeBooks) a search link each for the person to open, with the words typed in; books=true adds the bookshops. kind=specs: a "
                + "product's datasheet by brand and model, or by barcode (Open Icecat, with the person's account; the Open Facts databases by barcode otherwise): "
                + "title, category, release date, end of life, the features. kind=identity: a product's barcode and brand by words (the Open Facts databases). "
                + "kind=owners: what owners wrote, with the date and the score (Reddit, through the person's own app). kind=repair: the repairability score "
                + "(iFixit). The market is the person's own unless the question names another; say the market and the time with every price.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode().put("type", "object");
        ObjectNode props = p.putObject("properties");
        ObjectNode kind = props.putObject("kind").put("type", "string").put("description", "shops | marketplaces | specs | identity | owners | repair");
        for (String k : List.of("shops", "marketplaces", "specs", "identity", "owners", "repair")) kind.withArray("enum").add(k);
        props.putObject("query").put("type", "string").put("description", "the words (shops, identity, owners, repair); the product's name for owners and repair");
        props.putObject("brand").put("type", "string").put("description", "specs: the maker");
        props.putObject("model").put("type", "string").put("description", "specs: the model number as the maker writes it");
        props.putObject("barcode").put("type", "string").put("description", "specs: the barcode (GTIN, EAN, UPC, JAN) when known");
        props.putObject("books").put("type", "boolean").put("description", "marketplaces: true when the thing is a book, for the second-hand bookshops");
        props.putObject("market").put("type", "string").put("description", "shops, marketplaces: the country, two letters (JP, US, GB, DE…); the person's own when left out");
        props.putObject("language").put("type", "string").put("description", "specs: the language of the datasheet (EN, JA, DE…)");
        props.putObject("limit").put("type", "integer").put("description", "how many rows, up to " + MOST + " (default 10)");
        p.putArray("required").add("kind");
        return p;
    }

    @Override public String execute(JsonNode args) throws Exception {
        if (calls.incrementAndGet() > MOST_CALLS) return "ERROR: product_search has been called " + MOST_CALLS + " times in this run, which is its limit. Work with what was found.";
        String kind = args.path("kind").asText("shops").toLowerCase(Locale.ROOT);
        String query = args.path("query").asText("").strip();
        int limit = Math.max(1, Math.min(MOST, args.path("limit").asInt(10)));
        return switch (kind) {
            case "shops" -> shops(query, args.path("market").asText("").strip().toUpperCase(Locale.ROOT), limit);
            case "marketplaces" -> marketplaces(query, args.path("market").asText("").strip().toUpperCase(Locale.ROOT), args.path("books").asBoolean(false), limit);
            case "specs" -> specs(args.path("brand").asText("").strip(), args.path("model").asText("").strip(), args.path("barcode").asText("").strip(), args.path("language").asText("EN"), query);
            case "identity" -> identity(query, limit);
            case "owners" -> owners(query, limit);
            case "repair" -> repair(query);
            default -> "ERROR: kind is shops, specs, identity, owners or repair.";
        };
    }

    static String marketplaces(String query, String marketArg, boolean books, int limit) {
        if (query.isEmpty()) return "ERROR: query is needed: the words of the thing.";
        String market = marketArg.matches("^[A-Z]{2}$") ? marketArg : Settings.market();
        StringBuilder b = new StringBuilder("Market " + market + ". ");
        List<Shops.Listing> all = new ArrayList<>(); List<String> notes = new ArrayList<>();
        if (Shops.ebayOn()) { try { all.addAll(Shops.ebay(query, market, limit)); } catch (Exception e) { notes.add("eBay did not answer (" + e.getMessage() + ")"); } }
        if (Shops.etsyOn()) { try { all.addAll(Shops.etsy(query, limit)); } catch (Exception e) { notes.add("Etsy did not answer (" + e.getMessage() + ")"); } }
        if (!all.isEmpty()) {
            b.append(all.size()).append(" listing(s) for \"").append(query).append("\", each for sale now; only these may be recommended from this call, and an auction's price is the bid at the time shown, not what it will sell for.\n");
            int n = 0;
            for (Shops.Listing l : all) {
                if (n++ >= limit * 2) break;
                b.append("- ").append(Shops.clean(l.name())).append(" — ").append(l.price().isEmpty() ? "price not shown" : l.price() + " " + l.currency()).append(" at ").append(l.shop())
                 .append(l.seller().isEmpty() ? "" : " (" + Shops.clean(l.seller()) + ")").append(l.inStock() ? "" : " — none left").append(" — seen ").append(l.seenAt()).append(" — ").append(l.url()).append('\n');
            }
        } else b.append("No marketplace the library can ask answered for \"").append(query).append("\"").append(Shops.ebayOn() || Shops.etsyOn() ? "" : " (eBay and Etsy need the person's keys: the settings ebay_client_id with ebay_client_secret, and etsy_key)").append(".\n");
        List<Shops.Place> places = Shops.places(query, market, books);
        if (!places.isEmpty()) {
            b.append("Search these yourself (the library cannot read them; each link has the words typed in, and what a listing says there is the person's to read):\n");
            for (Shops.Place p : places) b.append("- ").append(p.name()).append(" — ").append(p.what()).append(" — ").append(p.url()).append('\n');
            b.append("A web search with site:<the marketplace> and the words shows what is listed there as snippets; a watched search (a living shelf) tells the person when something new appears.\n");
        }
        if (!notes.isEmpty()) b.append("Note: ").append(String.join("; ", notes)).append(".\n");
        return b.toString();
    }

    static String shops(String query, String marketArg, int limit) {
        if (query.isEmpty()) return "ERROR: query is needed: the words of the thing.";
        String market = marketArg.matches("^[A-Z]{2}$") ? marketArg : Settings.market();
        StringBuilder b = new StringBuilder("Market " + market + ". ");
        List<Shops.Listing> all = new ArrayList<>(); List<String> notes = new ArrayList<>();
        if (market.equals("JP")) {
            if (Shops.yahooOn()) { try { all.addAll(Shops.yahoo(query, limit)); } catch (Exception e) { notes.add("Yahoo! JAPAN Shopping did not answer (" + e.getMessage() + ")"); } }
            else notes.add("Yahoo! JAPAN Shopping is not set up (the setting yahoo_jp_client_id; developer.yahoo.co.jp gives a Client ID)");
            if (Shops.rakutenOn()) { try { all.addAll(Shops.rakuten(query, limit)); } catch (Exception e) { notes.add("楽天市場 did not answer (" + e.getMessage() + ")"); } }
            else notes.add("楽天市場 is not set up (the settings rakuten_app_id and rakuten_access_key; webservice.rakuten.co.jp gives both)");
        } else {
            notes.add("no shop in the " + market + " market answers without a key the library does not have; search the web for the product and point at the shop's own page, with the price and the date as that page shows them");
        }
        if (all.isEmpty()) return b.append(String.join("; ", notes)).append(". Nothing listed for \"").append(query).append("\" through the shops the library can ask.").toString();
        b.append(all.size()).append(" listing(s) for \"").append(query).append("\", each for sale now; only these may be recommended from this call. Price = what the shop asked at the time shown.\n");
        int n = 0;
        for (Shops.Listing l : all) {
            if (n++ >= limit * 2) break;
            b.append("- ").append(Shops.clean(l.name())).append(" — ").append(l.price().isEmpty() ? "price not shown" : l.price() + " " + l.currency()).append(" at ").append(l.shop())
             .append(l.seller().isEmpty() ? "" : " (" + Shops.clean(l.seller()) + ")").append(l.inStock() ? "" : " — not in stock").append(l.code().isEmpty() ? "" : " — code " + l.code())
             .append(l.brand().isEmpty() ? "" : " — brand " + Shops.clean(l.brand())).append(" — seen ").append(l.seenAt()).append(" — ").append(l.url()).append('\n');
        }
        if (!notes.isEmpty()) b.append("Note: ").append(String.join("; ", notes)).append(".\n");
        if (all.stream().anyMatch(l -> l.shop().startsWith("Yahoo"))) b.append("Credit, to be kept where these listings are shown: Web Services by Yahoo! JAPAN (https://developer.yahoo.co.jp/sitemap/).\n");
        if (all.stream().anyMatch(l -> l.shop().startsWith("楽天"))) b.append("Each 楽天市場 listing is cited with its link, as its terms ask.\n");
        return b.toString();
    }

    static String specs(String brand, String model, String barcode, String language, String query) {
        List<String> notes = new ArrayList<>();
        if (Shops.icecatOn() && (!barcode.isEmpty() || (!brand.isEmpty() && !model.isEmpty()))) {
            try {
                Shops.Spec s = Shops.icecat(barcode, brand, model, language);
                if (s != null) {
                    StringBuilder b = new StringBuilder("Datasheet (" + s.source() + "): " + Shops.clean(s.title()) + " — brand " + Shops.clean(s.brand()) + ", model " + Shops.clean(s.model()) + (s.category().isEmpty() ? "" : ", category " + Shops.clean(s.category()))
                            + (s.released().isEmpty() ? "" : ", released " + s.released()) + (s.endOfLife().isEmpty() ? "" : ", end of life " + s.endOfLife()) + ". " + s.url() + "\n");
                    for (String[] f : s.features()) b.append("- ").append(Shops.clean(f[0])).append(": ").append(Shops.clean(f[1])).append('\n');
                    b.append("Specs by Open Icecat (https://icecat.biz), to be credited where they are shown.\n");
                    return b.toString();
                }
                notes.add("Open Icecat has no datasheet for " + (barcode.isEmpty() ? brand + " " + model : "barcode " + barcode));
            } catch (Exception e) { notes.add(e.getMessage() != null && e.getMessage().startsWith("the datasheet is in") ? "Open Icecat: " + e.getMessage() : "Open Icecat did not answer (" + e.getMessage() + ")"); }
        } else if (!Shops.icecatOn()) notes.add("Open Icecat is not set up (the setting icecat_user; a free Open Icecat account at icecat.biz)");
        if (!barcode.isEmpty()) {
            try {
                Shops.Listing l = Shops.openFactsByCode(barcode);
                if (l != null) return "Identity (" + l.shop() + "): " + Shops.clean(l.name()) + (l.brand().isEmpty() ? "" : " — brand " + Shops.clean(l.brand())) + " — code " + l.code() + " — " + l.url() + "\n" + (notes.isEmpty() ? "" : "Note: " + String.join("; ", notes) + ".\n");
            } catch (Exception e) { notes.add("the Open Facts databases did not answer (" + e.getMessage() + ")"); }
        }
        if (brand.isEmpty() && model.isEmpty() && barcode.isEmpty()) return "ERROR: specs needs brand and model, or barcode" + (query.isEmpty() ? "." : "; kind=identity with the words finds the barcode first.");
        return "No datasheet found for " + (barcode.isEmpty() ? brand + " " + model : "barcode " + barcode) + ". " + String.join("; ", notes) + ". Read the maker's own page for the specifications and cite it.";
    }

    static String identity(String query, int limit) {
        if (query.isEmpty()) return "ERROR: query is needed: the words of the thing.";
        try {
            List<Shops.Listing> found = Shops.openFactsSearch(query, limit);
            if (found.isEmpty()) return "The Open Facts databases list nothing for \"" + query + "\"; they cover food, cosmetics, pet food and some goods, by barcode.";
            StringBuilder b = new StringBuilder(found.size() + " product(s) for \"" + query + "\" in the Open Facts databases (open data, by barcode):\n");
            for (Shops.Listing l : found) b.append("- ").append(Shops.clean(l.name())).append(l.brand().isEmpty() ? "" : " — brand " + Shops.clean(l.brand())).append(" — code ").append(l.code()).append(" — ").append(l.url()).append('\n');
            return b.toString();
        } catch (Exception e) { return "The Open Facts databases did not answer (" + e.getMessage() + ")."; }
    }

    static String owners(String query, int limit) {
        if (query.isEmpty()) return "ERROR: query is needed: the product's name.";
        if (!Shops.redditOn()) {
            // Reddit stopped creating apps for new personal use in 2025 (its form reloads with only the policy link), so without credentials
            // the owners' words come as the search engine's snippets of Reddit threads: pointed at, never fetched, as Reddit's terms ask
            String note = "No Reddit app is set (the settings reddit_client_id and reddit_client_secret work for an app made before Reddit closed its form to new personal apps), "
                    + "so these are the search engine's snippets of Reddit threads about \"" + query + "\": a title and a few words each, one person's experience, paid or planted praise "
                    + "included — weigh it, never count it. The addresses are to point the person at; Reddit's terms do not allow reading the pages.\n";
            try { return note + new WebSearchTool().execute(M.createObjectNode().put("query", "site:reddit.com " + query).put("limit", Math.max(3, Math.min(limit, 10)))); }
            catch (Exception e) { return note + "The web search did not answer (" + e.getMessage() + "). What owners say can still be found through video_search and podcast_search."; }
        }
        try {
            List<Shops.Voice> v = Shops.reddit(query, limit);
            if (v.isEmpty()) return "Nothing on Reddit in the last year for \"" + query + "\".";
            StringBuilder b = new StringBuilder(v.size() + " post(s) on Reddit for \"" + query + "\" (what owners wrote; a post is one person's experience, and a thread may carry paid or planted praise — weigh it, never count it):\n");
            for (Shops.Voice x : v) b.append("- ").append(x.where()).append(", ").append(x.date()).append(", score ").append(x.score()).append(": ").append(Shops.clean(x.title())).append(x.excerpt().isEmpty() ? "" : " — " + Shops.clean(x.excerpt())).append(" — ").append(x.url()).append('\n');
            return b.toString();
        } catch (Exception e) { return "Reddit did not answer (" + e.getMessage() + ")."; }
    }

    static String repair(String query) {
        if (query.isEmpty()) return "ERROR: query is needed: the product's name.";
        try {
            List<Shops.Repair> r = Shops.ifixit(query);
            if (r.isEmpty()) return "iFixit has no device page for \"" + query + "\".";
            StringBuilder b = new StringBuilder("iFixit (repair guides; the score is iFixit's, 10 = easiest to repair; a device without a score has guides but no verdict):\n");
            for (Shops.Repair x : r) b.append("- ").append(Shops.clean(x.device())).append(x.score().isEmpty() ? " — no repairability score" : " — repairability " + x.score() + "/10").append(" — ").append(x.url()).append('\n');
            return b.toString();
        } catch (Exception e) { return "iFixit did not answer (" + e.getMessage() + ")."; }
    }
}
