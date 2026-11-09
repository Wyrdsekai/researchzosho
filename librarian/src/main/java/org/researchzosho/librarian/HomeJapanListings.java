package org.researchzosho.librarian;

import org.researchzosho.librarian.HomeCut.Cut;
import org.researchzosho.librarian.HomeCut.Range;
import org.researchzosho.librarian.HomeSources.Yield;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a home search's listings come from in Japan. SUUMO's list pages are read as a program may read them: its robots rules (read
 * 2026-11-05) allow the rent search list and the area pages of homes for sale, and keep the sale search list from programs, so the sale
 * lists go by the area page of the municipality, which takes the same filters; its terms keep its content to a person's own private use
 * and forbid commercial use, which a person's own search is neither, and name no rule against a program. So the library enumerates its
 * lists by municipality with the search's own filters in the site's own query, the rent or price, the 間取り, the walk, the age, the size
 * and the kind, every page of the result and never a recommended or ranked slice, at the polite pace, one page a second. The
 * municipality's name in an area page's address is read off the prefecture's own page, once a month, since SUUMO's Latin names are not
 * always Wikidata's. Each room or home links its own page, with the list page it was seen on beside it. AtHome answers a program with a
 * verification page after one request (2026-11-05), so it is a link for the person; HOME'S refuses programs at the door (403). The
 * listings' money is yen; the sizes come in ㎡ and are kept in square feet too, for the one judge.
 */
public final class HomeJapanListings {
    private HomeJapanListings() { }

    public static final String SUUMO = "SUUMO";
    /** The most pages one list is read for: fifty rentals or a hundred homes a page, so thousands; the log says when a list has more. */
    static final int PAGE_CAP = 60;
    /** A page read under the site's robots rules at the polite pace, the body or null; a test puts its own here. */
    static volatile Function<String, String> GET = CareerSources::get;

    static final String SITE = "https://suumo.jp", RENT_LIST = SITE + "/jj/chintai/ichiran/FR301FC001/";

    // ---------------------------------------------------------------- the search, in SUUMO's own query

    /** SUUMO's rent 間取り codes: 01 ワンルーム … 14 5K以上, by the rooms each names. */
    static final String[][] RENT_LAYOUTS = {{"01", "1"}, {"02", "1"}, {"03", "1"}, {"04", "1"}, {"05", "2"}, {"06", "2"}, {"07", "2"}, {"08", "3"}, {"09", "3"}, {"10", "3"}, {"11", "4"}, {"12", "4"}, {"13", "4"}, {"14", "5"}};
    static final int[] WALK_OPTIONS = {1, 5, 7, 10, 15, 20}, BUILT_OPTIONS = {0, 1, 3, 5, 7, 10, 15, 20, 25, 30}, RENT_AREA_OPTIONS = {20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 80, 90, 100};
    static final int[] BUY_PRICE_OPTIONS = {500, 1000, 1500, 2000, 2500, 3000, 3500, 4000, 4500, 5000, 5500, 6000, 6500, 7000, 7500, 8000, 9000, 10000, 12000}, HOUSE_AREA_OPTIONS = {60, 70, 80, 90, 100, 110, 120, 130, 140, 150};
    static final double[] RENT_OPTIONS = {3, 3.5, 4, 4.5, 5, 5.5, 6, 6.5, 7, 7.5, 8, 8.5, 9, 9.5, 10, 10.5, 11, 11.5, 12, 12.5, 13, 13.5, 14, 15, 20, 30, 50, 100};

    /** The kinds of home for sale SUUMO lists apart: 中古マンション, 中古一戸建て, 土地. */
    record BuyKind(String bs, String name, String path) { }
    static final List<BuyKind> BUY_KINDS = List.of(new BuyKind("011", "中古マンション", "ms/chuko"), new BuyKind("021", "中古一戸建て", "chukoikkodate"), new BuyKind("030", "土地", "tochi"));

    /** The rent list's address for a cut and a page: the municipality, the rent, the rooms, the walk, the age, the size and the kind as SUUMO's own filters. */
    static String rentUrl(Cut c, int page) {
        String code = c.japan().code(), pref = code.length() >= 2 ? code.substring(0, 2) : "";
        StringBuilder q = new StringBuilder(RENT_LIST + "?ar=" + HomeJapan.SUUMO_REGIONS.getOrDefault(pref, "030") + "&bs=040&ta=" + pref + "&sc=" + code);
        Range price = c.price();
        q.append("&cb=").append(price.min() > 0 ? String.format(Locale.ROOT, "%.1f", floorTo(price.min() / 10_000.0, RENT_OPTIONS)) : "0.0");
        q.append("&ct=").append(price.max() > 0 ? String.format(Locale.ROOT, "%.1f", ceilTo(price.max() / 10_000.0, RENT_OPTIONS)) : "9999999");
        q.append("&et=").append(c.japan().walk().max() > 0 ? String.valueOf(ceilTo((int) c.japan().walk().max(), WALK_OPTIONS)) : "9999999");
        q.append("&cn=").append(c.built().min() > 0 ? String.valueOf(ceilTo(Math.max(0, LocalDate.now().getYear() - (int) c.built().min()), BUILT_OPTIONS)) : "9999999");
        q.append("&mb=").append(c.sqft().min() > 0 ? String.valueOf(floorTo((int) Math.floor(c.sqft().min() / HomeCut.SQ_FT_PER_M2), RENT_AREA_OPTIONS)) : "0");
        q.append("&mt=").append(c.sqft().max() > 0 ? String.valueOf(ceilTo((int) Math.ceil(c.sqft().max() / HomeCut.SQ_FT_PER_M2), RENT_AREA_OPTIONS)) : "9999999");
        if (!c.beds().open()) for (String[] l : RENT_LAYOUTS) { int r = Integer.parseInt(l[1]); if ((c.beds().min() <= 0 || r >= c.beds().min()) && (c.beds().max() <= 0 || r <= c.beds().max())) q.append("&md=").append(l[0]); }
        for (String k : c.kinds()) { String ts = k.equals("マンション") ? "1" : k.equals("アパート") ? "2" : k.equals("一戸建て") || k.equals("テラスハウス") ? "3" : ""; if (!ts.isEmpty() && !q.toString().contains("&ts=" + ts)) q.append("&ts=").append(ts); }
        return q.append("&pc=50&page=").append(page).toString();
    }

    /** The area page of a municipality for a kind of home for sale: "https://suumo.jp/ms/chuko/tokyo/sc_mitaka/". */
    static String areaUrl(Cut c, BuyKind kind, String slug) {
        HomeJapan.Prefecture p = HomeJapan.prefectureNamed(c.japan().pref());
        return SITE + "/" + kind.path() + "/" + (p == null ? "tokyo" : p.suumoSlug()) + "/sc_" + slug + "/";
    }

    /** The municipalities of a prefecture as SUUMO names them in its addresses, their Japanese names to their slugs, read off the prefecture's page and kept a month; empty when the page did not answer. */
    static Map<String, String> slugs(LibraryStore store, HomeJapan.Prefecture p) {
        Map<String, String> out = new LinkedHashMap<>();
        if (p == null) return out;
        String body = store == null ? slugLines(parseSlugs(GET.apply(SITE + "/ms/chuko/" + p.suumoSlug() + "/"), p.suumoSlug()))
                : HomeJapanRecords.cached(store, "suumo-slugs-" + p.code(), 31, 1, () -> { String h; try { h = GET.apply(SITE + "/ms/chuko/" + p.suumoSlug() + "/"); } catch (RuntimeException e) { h = null; } return h == null ? null : slugLines(parseSlugs(h, p.suumoSlug())); });
        if (body == null) return out;
        for (String line : body.split("\n")) { int i = line.indexOf('='); if (i > 0) out.put(line.substring(0, i), line.substring(i + 1)); }
        return out;
    }

    /** The links of a prefecture's page to its municipalities' area pages: "/ms/chuko/tokyo/sc_mitaka/" with the text 三鷹市. */
    static Map<String, String> parseSlugs(String html, String prefSlug) {
        Map<String, String> out = new LinkedHashMap<>();
        if (html == null) return out;
        Matcher m = Pattern.compile("href=\"/ms/chuko/" + Pattern.quote(prefSlug) + "/sc_([a-z0-9_]+)/\"[^>]*>\\s*([^<]{1,24}?)\\s*<").matcher(html);
        while (m.find()) { String name = m.group(2).strip(); if (HomeJapan.isJapanese(name) && !out.containsKey(name)) out.put(name, m.group(1)); }
        return out;
    }

    static String slugLines(Map<String, String> slugs) { StringBuilder b = new StringBuilder(); for (Map.Entry<String, String> e : slugs.entrySet()) b.append(e.getKey()).append('=').append(e.getValue()).append('\n'); return b.toString(); }

    /** The cut's municipality as SUUMO names it: by its Japanese name on the prefecture's page, else by its Latin name; "" when neither is known. */
    static String slug(LibraryStore store, Cut c) {
        Map<String, String> all = slugs(store, HomeJapan.prefectureNamed(c.japan().pref()));
        String name = c.japan().name();
        if (!name.isEmpty()) {
            String s = all.get(name);
            if (s != null) return s;
            for (Map.Entry<String, String> e : all.entrySet()) if (e.getKey().endsWith(name) || name.endsWith(e.getKey())) return e.getValue();
        }
        String en = c.japan().en().toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "");
        return all.isEmpty() ? en : "";   // with the page in hand and the name not on it, no guess
    }

    /** The sale list's address for a cut, a kind, its area page and a page number: the price in 万円, the rooms, the walk, the age and the sizes as SUUMO's own filters. */
    static String buyUrl(Cut c, BuyKind kind, String area, int page) {
        StringBuilder q = new StringBuilder(area + "?");
        Range price = c.price();
        q.append("kb=").append(price.min() > 0 ? String.valueOf(Math.max(1, floorTo((int) (price.min() / 10_000), BUY_PRICE_OPTIONS))) : "1");
        int maxMan = price.max() > 0 ? (int) Math.ceil(price.max() / 10_000) : 0;
        q.append("&kt=").append(maxMan > 0 ? String.valueOf(aboveTo(maxMan, BUY_PRICE_OPTIONS)) : "9999999");   // kt is "under": the first option above the ceiling keeps a home at the ceiling in
        q.append("&et=").append(c.japan().walk().max() > 0 ? String.valueOf(ceilTo((int) c.japan().walk().max(), new int[]{1, 3, 5, 7, 10, 15, 20})) : "9999999");
        q.append("&cn=").append(c.built().min() > 0 ? String.valueOf(ceilTo(Math.max(0, LocalDate.now().getYear() - (int) c.built().min()), BUILT_OPTIONS)) : "9999999");
        int minM2 = c.sqft().min() > 0 ? (int) Math.floor(c.sqft().min() / HomeCut.SQ_FT_PER_M2) : 0, maxM2 = c.sqft().max() > 0 ? (int) Math.ceil(c.sqft().max() / HomeCut.SQ_FT_PER_M2) : 0;
        if (kind.bs().equals("011")) { q.append("&mb=").append(minM2 > 0 ? minM2 : 0).append("&mt=").append(maxM2 > 0 ? maxM2 : 9999999); }
        else if (kind.bs().equals("021")) { q.append("&hb=").append(minM2 > 0 ? floorTo(minM2, HOUSE_AREA_OPTIONS) : 0).append("&ht=").append(maxM2 > 0 ? ceilTo(maxM2, HOUSE_AREA_OPTIONS) : 9999999); }
        else { q.append("&tb=").append(c.lot().min() > 0 ? floorTo((int) Math.floor(c.lot().min() / HomeCut.SQ_FT_PER_M2), HOUSE_AREA_OPTIONS) : 0).append("&tt=").append(c.lot().max() > 0 ? ceilTo((int) Math.ceil(c.lot().max() / HomeCut.SQ_FT_PER_M2), HOUSE_AREA_OPTIONS) : 9999999); }
        if (!c.beds().open() && !kind.bs().equals("030")) for (int r = 1; r <= 5; r++) if ((c.beds().min() <= 0 || r >= c.beds().min()) && (c.beds().max() <= 0 || r <= c.beds().max())) q.append("&md=").append(r);
        return q.append("&pc=100&page=").append(page).toString();
    }

    /** The kinds for sale a cut asks for, in Japanese or English words: the ones named, else flats and houses. */
    static List<BuyKind> buyKinds(Cut c) {
        List<BuyKind> out = new ArrayList<>();
        for (String k : c.kinds()) {
            String bs = switch (k.toLowerCase(Locale.ROOT)) { case "マンション", "condo", "apartment", "loft", "studio" -> "011"; case "一戸建て", "テラスハウス", "house", "townhouse", "duplex", "multi-family" -> "021"; case "土地", "land" -> "030"; default -> ""; };
            for (BuyKind b : BUY_KINDS) if (b.bs().equals(bs) && !out.contains(b)) out.add(b);
        }
        if (out.isEmpty()) { out.add(BUY_KINDS.get(0)); out.add(BUY_KINDS.get(1)); }
        return out;
    }

    static int ceilTo(int n, int[] options) { for (int o : options) if (o >= n) return o; return options[options.length - 1]; }
    static int floorTo(int n, int[] options) { int best = 0; for (int o : options) if (o <= n) best = o; return best; }
    /** The first option strictly above n; the last when none is. */
    static int aboveTo(int n, int[] options) { for (int o : options) if (o > n) return o; return 9999999; }
    static double ceilTo(double n, double[] options) { for (double o : options) if (o >= n - 1e-9) return o; return 9999999; }
    static double floorTo(double n, double[] options) { double best = 0; for (double o : options) if (o <= n + 1e-9) best = o; return best; }

    // ---------------------------------------------------------------- the enumeration

    /** How far a list was read: the pages, the rooms or homes, the total the site states, and whether the cap stopped it. */
    public record Read(List<Home.Listing> listings, int pages, int total, boolean capped, String firstUrl) { }

    /** SUUMO's rentals or homes for sale that fit the cut, every page; the yield names the pages read and the site's own count. */
    public static Yield suumo(LibraryStore store, Cut c, String tenure, CareerFind.Progress progress) {
        boolean ja = c.lang().equals("ja");
        if (c.japan().code().isEmpty()) return new Yield(SUUMO, "list page", RENT_LIST, List.of(), ja ? "市区町村コードが分からないため、SUUMOは読んでいません" : "the municipality's code is not known, so SUUMO was not read");
        Map<String, Home.Listing> out = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        String first = "";
        int pagesAll = 0;
        if (tenure.equals("rent")) {
            Read r = read(p -> rentUrl(c, p), HomeJapanListings::rentListings, c, progress, ja ? "SUUMOの賃貸一覧" : "SUUMO's rentals");
            for (Home.Listing l : r.listings()) out.putIfAbsent(l.id(), l);
            pagesAll += r.pages(); first = r.firstUrl();
            notes.add(note(ja, "賃貸", "rentals", r));
        } else {
            String slug = slug(store, c);
            if (slug.isEmpty()) return new Yield(SUUMO, "list page", SITE + "/ms/chuko/", List.of(), ja ? c.japan().name() + "がSUUMOの" + c.japan().pref() + "のページに見つからないため、売買の一覧は読んでいません" : c.japan().name() + " is not on SUUMO's page of " + c.japan().pref() + ", so its lists of homes for sale were not read");
            for (BuyKind k : buyKinds(c)) {
                String area = areaUrl(c, k, slug);
                Read r = read(p -> buyUrl(c, k, area, p), html -> buyListings(html, k), c, progress, ja ? "SUUMOの" + k.name() + "一覧" : "SUUMO's " + k.name() + " for sale");
                for (Home.Listing l : r.listings()) out.putIfAbsent(l.id(), l);
                pagesAll += r.pages(); if (first.isEmpty()) first = r.firstUrl();
                notes.add(note(ja, k.name(), k.name() + " for sale", r));
            }
        }
        String note = String.join(ja ? "；" : "; ", notes) + (ja ? "。各物件はSUUMOの自身のページへリンクします" : ". Each links its own page on SUUMO");
        return new Yield(SUUMO, "list page", first.isEmpty() ? (tenure.equals("rent") ? RENT_LIST : SITE + "/ms/chuko/") : first, new ArrayList<>(out.values()), note, -1, 0);
    }

    static String note(boolean ja, String whatJa, String whatEn, Read r) {
        String total = r.total() >= 0 ? String.format(Locale.ROOT, "%,d", r.total()) : "";
        if (ja) return whatJa + "：" + r.pages() + "ページから" + r.listings().size() + "件" + (total.isEmpty() ? "" : "（SUUMOの掲載件数 " + total + "件）") + (r.capped() ? "。" + PAGE_CAP + "ページで打ち切り、続きは読んでいません。条件を絞ると残りも読めます" : "");
        return whatEn + ": " + r.listings().size() + " from " + r.pages() + (r.pages() == 1 ? " page" : " pages") + (total.isEmpty() ? "" : " (SUUMO states " + total + " listings)") + (r.capped() ? "; stopped at " + PAGE_CAP + " pages, the rest unread: narrow what you decided to read them all" : "");
    }

    /** Every page of one list, in order, until a page carries nothing or names no next page, or the cap. */
    static Read read(Function<Integer, String> url, Function<String, List<Home.Listing>> reader, Cut c, CareerFind.Progress progress, String what) {
        List<Home.Listing> out = new ArrayList<>();
        int total = -1, pages = 0; boolean capped = false;
        String first = url.apply(1);
        for (int page = 1; page <= PAGE_CAP + 1; page++) {
            if (page > PAGE_CAP) { capped = true; break; }
            String u = url.apply(page);
            if (progress != null) progress.at(what + (page == 1 ? "" : ", page " + page));
            String html;
            try { html = GET.apply(u); } catch (RuntimeException e) { html = null; }
            if (html == null) break;
            if (page == 1) total = total(html);
            List<Home.Listing> found = reader.apply(html);
            pages++;
            for (Home.Listing l : found) out.add(withSeen(l, u));
            if (found.isEmpty() || !hasPage(html, page + 1)) break;
        }
        return new Read(out, pages, total, capped, first);
    }

    static Home.Listing withSeen(Home.Listing l, String listUrl) {
        return new Home.Listing(l.id(), l.source(), l.tenure(), l.address(), l.city(), l.state(), l.zip(), l.price(), l.beds(), l.baths(), l.sqft(), l.kind(), l.url(), l.lat(), l.lon(), l.listed(), l.firstSeen(), l.lastSeen(),
                (l.note().isEmpty() ? "" : l.note() + "; ") + "seen on " + listUrl, l.extra());
    }

    /** SUUMO's own count of the list, from the pagination's hit box; -1 when the page does not say. */
    static int total(String html) {
        Matcher m = Pattern.compile("pagina(?:te|tion)_set-hit\">\\s*([\\d,]+)\\s*<").matcher(html);
        if (m.find()) try { return Integer.parseInt(m.group(1).replace(",", "")); } catch (NumberFormatException ignored) { }
        return -1;
    }

    /** Whether the page's pagination links a page of that number. */
    static boolean hasPage(String html, int page) {
        Matcher m = Pattern.compile("(?s)<(?:ol|div) class=\"pagination(?:-parts| pagination_set-nav)\".*?</(?:ol|div)>").matcher(html);
        String nav = m.find() ? m.group() : html;
        return nav.contains("page=" + page + "\"") || nav.contains("page=" + page + "&") || nav.contains("page=" + page + "'");
    }

    // ---------------------------------------------------------------- the readers

    /** The words of a piece of the page, as the portal writes them: tags out, spaces folded. */
    static String text(String html) { return html.replaceAll("<sup>2</sup>", "2").replaceAll("<[^>]+>", " ").replaceAll("&nbsp;", " ").replaceAll("&amp;", "&").replaceAll("\\s+", " ").strip(); }
    /** The same with its digits and letters made ASCII, for the figures. */
    static String plain(String html) { return HomeJapan.halfwidth(text(html)); }

    /** A rent page's rooms: each building's name, address, stations and age, then each room's floor, rent, 間取り, size and own page. */
    static List<Home.Listing> rentListings(String html) {
        List<Home.Listing> out = new ArrayList<>();
        Matcher b = Pattern.compile("(?s)<div class=\"cassetteitem\">(.*?)(?=<div class=\"cassetteitem\">|<div class=\"pagination_set\">|$)").matcher(html);
        while (b.find()) {
            String block = b.group(1);
            String label = first(block, "cassetteitem_content-label\">\\s*<span[^>]*>([^<]*)<"), name = first(block, "cassetteitem_content-title\">([^<]*)<"), address = first(block, "cassetteitem_detail-col1\">([^<]*)<");
            String age = first(block, "cassetteitem_detail-col3\">\\s*<div>([^<]*)</div>");
            int built = builtFromAge(age);
            String[] st = nearestStation(first(block, "(?s)cassetteitem_detail-col2\">(.*?)</li>"));
            String[] addr = HomeJapan.split(text(address));
            Matcher r = Pattern.compile("(?s)<tr class=\"js-cassette_link\">(.*?)</tr>").matcher(block);
            while (r.find()) {
                String row = r.group(1);
                String href = first(row, "<a href=\"([^\"]+)\"[^>]*class=\"js-cassette_link_href");
                if (href.isEmpty()) continue;
                String url = href.startsWith("http") ? href : "https://suumo.jp" + href;
                long rent = HomeJapan.yen(first(row, "cassetteitem_other-emphasis[^>]*>([^<]*)<"));
                String admin = text(first(row, "cassetteitem_price--administration\">([^<]*)<"));
                String layout = text(first(row, "cassetteitem_madori\">([^<]*)<")), area = text(first(row, "cassetteitem_menseki\">(.*?)</span>"));
                int floor = floorOf(first(row, "</td>\\s*<td>\\s*([^<]*階)\\s*</td>"));
                double m2 = m2(area);
                Home.Listing.Extra x = new Home.Listing.Extra("JPY", text(name), layout, m2, built, st[0], st[1], walkOf(st[2]), floor);
                String note = (admin.isEmpty() || admin.equals("-") ? "" : "管理費 " + admin) ;
                out.add(new Home.Listing(homeId(text(address), text(name), rent, layout, m2, floor), SUUMO, "rent", text(address), addr[1], addr[0], "", rent, HomeJapan.rooms(layout), 0, (int) Math.round(m2 * HomeCut.SQ_FT_PER_M2), text(label), url, 0, 0, "", "", "", note, x));
            }
        }
        return out;
    }

    /** A sale page's homes: each unit's name, price, address, station, sizes, 間取り, age and own page. */
    static List<Home.Listing> buyListings(String html, BuyKind kind) {
        List<Home.Listing> out = new ArrayList<>();
        Matcher u = Pattern.compile("(?s)<div class=\"property_unit[ \"](.*?)(?=<div class=\"property_unit[ \"]|<div class=\"pagination_set\">|$)").matcher(html);
        while (u.find()) {
            String unit = u.group(1);
            String href = first(unit, "property_unit-title\">\\s*<a href=\"([^\"]+)\"");
            if (href.isEmpty()) continue;
            String url = href.startsWith("http") ? href : "https://suumo.jp" + href;
            Map<String, String> dd = new LinkedHashMap<>();
            Matcher d = Pattern.compile("(?s)<dt[^>]*>([^<]*)</dt>\\s*<dd[^>]*>(.*?)</dd>").matcher(unit);
            while (d.find()) dd.putIfAbsent(text(d.group(1)), text(d.group(2)));
            String address = dd.getOrDefault("所在地", ""), name = dd.getOrDefault("物件名", "");
            long price = HomeJapan.yen(dd.getOrDefault("販売価格", ""));
            String[] st = stationWalk(dd.getOrDefault("沿線・駅", ""));
            String layout = dd.getOrDefault("間取り", "");
            double m2 = m2(dd.getOrDefault("専有面積", dd.getOrDefault("建物面積", dd.getOrDefault("土地面積", ""))));
            double lot = m2(dd.getOrDefault("土地面積", ""));
            int built = builtFromDate(dd.getOrDefault("築年月", ""));
            String[] addr = HomeJapan.split(address);
            boolean nameIsAddress = name.isEmpty() || HomeJapan.in(address, HomeJapan.halfwidth(name).replaceAll("\\d+丁目$", "").replaceAll("\\d+$", ""));   // "杉並区例町２丁目" for 東京都杉並区例町２ names no building
            Home.Listing.Extra x = new Home.Listing.Extra("JPY", nameIsAddress ? "" : name, layout, m2, built, st[0], st[1], walkOf(st[2]), 0);
            String note = lot > 0 && !kind.bs().equals("030") ? "土地面積 " + dd.getOrDefault("土地面積", "") : "";
            out.add(new Home.Listing(homeId(address, name, price, layout, m2, 0), SUUMO, "sale", address, addr[1], addr[0], "", price, HomeJapan.rooms(layout), 0, (int) Math.round(m2 * HomeCut.SQ_FT_PER_M2), kind.name(), url, 0, 0, "", "", "", note, x));
        }
        return out;
    }

    /**
     * A home's id by its own figures, not its page: SUUMO lists the same unit once per agent, each with a page of its own, and the same
     * home from two agents is one listing, its first page kept. Two rooms of one building with the same floor, 間取り, size and rent are one too.
     */
    static String homeId(String address, String name, long price, String layout, double m2, int floor) {
        return CareerSources.id("suumo|" + HomeJapan.halfwidth(address) + "|" + HomeJapan.halfwidth(name) + "|" + price + "|" + HomeJapan.halfwidth(layout).toUpperCase(Locale.ROOT) + "|" + m2 + "|" + floor);
    }

    static String first(String s, String regex) { Matcher m = Pattern.compile(regex).matcher(s); return m.find() ? m.group(1).strip() : ""; }

    /** "京王井の頭線/浜田山駅 歩3分", "ＪＲ中央線「荻窪」徒歩19分" → {the line, the station, the minutes}. */
    static String[] stationWalk(String s) {
        String t = plain(s == null ? "" : s);
        if (t.isEmpty()) return new String[]{"", "", ""};
        Matcher m = Pattern.compile("^(.+?)\\s*[/／「]\\s*([^」\\s]+?)駅?\\s*[」]?\\s*(?:歩|徒歩)\\s*(\\d+)\\s*分").matcher(t);
        if (m.find()) return new String[]{m.group(1).strip(), m.group(2).strip() + "駅", m.group(3)};
        Matcher w = Pattern.compile("(?:歩|徒歩)\\s*(\\d+)\\s*分").matcher(t);
        Matcher st = Pattern.compile("([^\\s/／「」]+?)駅").matcher(t);
        String line = t.contains("/") ? t.substring(0, t.indexOf('/')).strip() : t.contains("「") ? t.substring(0, t.indexOf('「')).strip() : "";   // "例線/例駅 バス10分", "ＪＲ中央線「三鷹」バス10分": the line, the station, and no minutes on foot
        String station = st.find() ? st.group(1) + "駅" : "";
        if (station.isEmpty()) { Matcher q = Pattern.compile("「([^」]+)」").matcher(t); if (q.find()) station = q.group(1) + "駅"; }   // the station in brackets, reached by bus
        boolean bus = t.contains("バス");   // "バス10分停歩2分": the minutes on foot are the bus stop's, not the station's
        return new String[]{line, station, bus || !w.find() ? "" : w.group(1)};
    }

    static int walkOf(String minutes) { try { return minutes.isEmpty() ? 0 : Integer.parseInt(minutes); } catch (NumberFormatException e) { return 0; } }

    /**
     * The nearest of the stations a listing names, by the minutes on foot: a building lists two or three lines, and SUUMO's walk filter
     * matches any of them, so the judge measures the nearest, as the filter did. The first named when none gives minutes.
     */
    static String[] nearestStation(String col2) {
        String[] best = new String[]{"", "", ""};
        Matcher m = Pattern.compile("(?s)cassetteitem_detail-text\">([^<]*)<").matcher(col2 == null ? "" : col2);
        while (m.find()) {
            String[] st = stationWalk(m.group(1));
            if (best[1].isEmpty()) best = st;
            else if (walkOf(st[2]) > 0 && (walkOf(best[2]) == 0 || walkOf(st[2]) < walkOf(best[2]))) best = st;
        }
        return best;
    }

    /** "築42年" → the year built; "新築" → this year; 0 when the words name none. */
    static int builtFromAge(String age) {
        String a = plain(age == null ? "" : age);
        if (a.contains("新築")) return LocalDate.now().getYear();
        Matcher m = Pattern.compile("築\\s*(\\d{1,3})\\s*年").matcher(a);
        return m.find() ? LocalDate.now().getYear() - Integer.parseInt(m.group(1)) : 0;
    }

    /** "1984年10月" → 1984. */
    static int builtFromDate(String s) { Matcher m = Pattern.compile("(\\d{4})\\s*年").matcher(plain(s == null ? "" : s)); return m.find() ? Integer.parseInt(m.group(1)) : 0; }

    /** "39.6m2", "57.87m2（17.50坪）", "63㎡" → the square metres. */
    static double m2(String s) {
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:m2|㎡|m²|平米)").matcher(plain(s == null ? "" : s));
        if (m.find()) try { return Double.parseDouble(m.group(1)); } catch (NumberFormatException ignored) { }
        return 0;
    }

    /** "2階" → 2; "B1階", "-" → 0. */
    static int floorOf(String s) { Matcher m = Pattern.compile("^\\s*(\\d{1,3})\\s*階").matcher(plain(s == null ? "" : s)); return m.find() ? Integer.parseInt(m.group(1)) : 0; }

    // ---------------------------------------------------------------- the links the person opens themselves

    /** The portals' own pages for the place, for the person: SUUMO's search with the cut's filters, and the sites a program cannot read. */
    public static List<String[]> portalLinks(LibraryStore store, Cut c, String tenure) {
        List<String[]> out = new ArrayList<>();
        if (c.japan().code().isEmpty()) return out;
        if (tenure.equals("rent")) out.add(new String[]{"SUUMO 賃貸（この条件で検索）", rentUrl(c, 1).replaceAll("&page=\\d+$", "")});
        else { String slug = slug(store, c); if (!slug.isEmpty()) for (BuyKind k : buyKinds(c)) out.add(new String[]{"SUUMO " + k.name() + "（この条件で検索）", buyUrl(c, k, areaUrl(c, k, slug), 1).replaceAll("&page=\\d+$", "")}); }
        String en = c.japan().en().toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", "");
        HomeJapan.Prefecture p = HomeJapan.prefectureNamed(c.japan().pref());
        if (!en.isEmpty() && p != null) {
            String pref = p.romaji().toLowerCase(Locale.ROOT);
            out.add(new String[]{"アットホーム（プログラムには認証ページが返るため、ご自身で開いてください）", tenure.equals("rent") ? "https://www.athome.co.jp/chintai/" + pref + "/" + en + "-city/list/" : "https://www.athome.co.jp/mansion/chuko/" + pref + "/" + en + "-city/list/"});
            out.add(new String[]{"LIFULL HOME'S（プログラムを受け付けないため、ご自身で開いてください）", tenure.equals("rent") ? "https://www.homes.co.jp/chintai/" + pref + "/" + en + "-city/list/" : "https://www.homes.co.jp/mansion/chuko/" + pref + "/" + en + "-city/list/"});
        }
        return out;
    }
}
