package org.researchzosho.librarian;

import org.researchzosho.librarian.HomeCut.Range;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The words the home search says to a person, in the language they asked in: the reasons a listing is set aside, what a near miss does
 * not show, the cut read back, the page's tiers, and a sum in the search's money. One judge in {@link HomeCut} decides; this says it, in
 * English or in Japanese, so a person who asked in Japanese reads the verdicts in Japanese with the prices in 万円.
 */
final class HomeWords {
    final String lang, currency;

    HomeWords(String lang, String currency) { this.lang = lang == null || lang.isBlank() ? "en" : lang; this.currency = currency == null || currency.isBlank() ? "USD" : currency; }

    static HomeWords of(HomeCut.Cut c) { return new HomeWords(c.lang(), c.currency()); }

    boolean ja() { return lang.equals("ja"); }
    boolean yen() { return currency.equals("JPY"); }

    // ---------------------------------------------------------------- figures

    /** A sum: "$750,000", "15万円", "¥150,000"; a figure under a hundred dollars keeps its cents. */
    String money(double n) {
        if (yen()) return ja() ? HomeJapan.yenWords(n) : "¥" + String.format(Locale.ROOT, "%,d", Math.round(n));
        return n < 100 && n != Math.floor(n) ? "$" + String.format(Locale.ROOT, "%.2f", n) : "$" + String.format(Locale.ROOT, "%,d", Math.round(n));
    }

    /** A rent, with its period: "$2,800 a month", "15万円/月", "¥150,000 a month". */
    String rent(double n) { return ja() ? money(n) + "/月" : money(n) + " a month"; }

    /** The unit of a price per area, after the sum: "a square foot", "/㎡", "per m²". */
    String perAreaUnit(boolean metric) { return ja() ? (metric ? "/㎡" : "/平方フィート") : metric ? " a square metre" : " a square foot"; }

    String areaWord(boolean metric) { return ja() ? (metric ? "㎡" : "平方フィート") : metric ? "square metre" : "square foot"; }

    /** A size kept in square feet, said in the unit of the search: "1,400 sq ft", "55.2㎡", "55.2 m²". */
    String size(double sqft, boolean metric) {
        if (!metric) return String.format(Locale.ROOT, "%,d", Math.round(sqft)) + (ja() ? "平方フィート" : " sq ft");
        double m2 = sqft / HomeCut.SQ_FT_PER_M2;
        String n = m2 == Math.floor(m2) ? String.format(Locale.ROOT, "%,d", Math.round(m2)) : String.format(Locale.ROOT, "%,.1f", m2);
        return n + (ja() ? "㎡" : " m²");
    }

    /** "up to $900,000", "from $400,000 to $550,000"; "15万円以下", "4,000万円〜6,000万円", "4,000万円以上". */
    String budget(Range r, boolean rentMonthly) {
        String a = money(r.min()), b = money(r.max());
        if (ja()) {
            String s = r.min() > 0 && r.max() > 0 ? a + "〜" + b : r.max() > 0 ? b + "以下" : a + "以上";
            return rentMonthly ? "家賃" + s : s;
        }
        String s = r.min() > 0 && r.max() > 0 ? "from " + a + " to " + b : r.max() > 0 ? "up to " + b : "from " + a;
        return rentMonthly ? s + " a month" : s;
    }

    /** The budget as a verdict names it: "$900,000", "$400,000 to $550,000", "$400,000 or more"; "15万円", "4,000万円〜6,000万円". */
    String budgetWords(Range r) {
        String a = money(r.min()), b = money(r.max());
        if (r.min() > 0 && r.max() > 0) return ja() ? a + "〜" + b : a + " to " + b;
        return r.max() > 0 ? b : ja() ? a + "以上" : a + " or more";
    }

    /** "up to $850 a square foot", "from $600 to $800 a square foot"; "㎡単価80万円以下". */
    String perArea(Range r, boolean metric, boolean rentMonthly) {
        String u = perAreaUnit(metric);
        if (ja()) { String s = r.min() > 0 && r.max() > 0 ? money(r.min()) + "〜" + money(r.max()) + u : r.max() > 0 ? money(r.max()) + u + "以下" : money(r.min()) + u + "以上"; return (metric ? "㎡単価" : "単価") + s + (rentMonthly ? "（月額）" : ""); }
        String s = r.min() > 0 && r.max() > 0 ? "from " + money(r.min()) + " to " + money(r.max()) + u : r.max() > 0 ? "up to " + money(r.max()) + u : "from " + money(r.min()) + u;
        return rentMonthly ? s + " a month" : s;
    }

    /** "3 to 4 bedrooms", "at least 3 bedrooms"; in Japanese by rooms, with the 間取り words when the person wrote some: "2LDK以上（2部屋以上）". */
    String count(Range r, String noun, List<String> layouts) {
        String a = Home.Listing.num(r.min()), b = Home.Listing.num(r.max());
        if (ja()) {
            String n = noun.equals("bedroom") ? "部屋" : "浴室";
            String rooms = r.min() > 0 && r.max() > 0 ? (r.min() == r.max() ? a + n : a + "〜" + b + n) : r.min() > 0 ? a + n + "以上" : b + n + "以下";
            return layouts == null || layouts.isEmpty() ? rooms : String.join("、", layouts) + "（" + rooms + "）";
        }
        if (r.min() > 0 && r.max() > 0) return r.min() == r.max() ? a + " " + noun + (r.min() == 1 ? "" : "s") : a + " to " + b + " " + noun + "s";
        return r.min() > 0 ? "at least " + a + " " + noun + (r.min() == 1 ? "" : "s") : "at most " + b + " " + noun + (r.max() == 1 ? "" : "s");
    }

    /** "1,400 to 1,800 sq ft", "at least 50㎡". */
    String span(Range r, boolean metric) {
        String a = size(r.min(), metric), b = size(r.max(), metric);
        if (ja()) return r.min() > 0 && r.max() > 0 ? a + "〜" + b : r.min() > 0 ? a + "以上" : b + "以下";
        if (r.min() > 0 && r.max() > 0) { String unit = metric ? " m²" : " sq ft"; return a.replace(unit, "") + " to " + b; }   // the unit once: "1,200 to 1,800 sq ft"
        return r.min() > 0 ? "at least " + a : "at most " + b;
    }

    String built(Range r) {
        int a = (int) r.min(), b = (int) r.max();
        if (ja()) return a > 0 && b > 0 ? a + "〜" + b + "年築" : a > 0 ? a + "年以降に建てられた" : b + "年以前に建てられた";
        return "built " + (a > 0 && b > 0 ? a + " to " + b : a > 0 ? a + " or later" : b + " or earlier");
    }

    String walk(Range r) {
        int a = (int) r.min(), b = (int) r.max();
        if (ja()) return a > 0 && b > 0 ? "駅徒歩" + a + "〜" + b + "分" : b > 0 ? "駅徒歩" + b + "分以内" : "駅徒歩" + a + "分以上";
        return a > 0 && b > 0 ? a + " to " + b + " minutes' walk to a station" : b > 0 ? "within " + b + " minutes' walk of a station" : "at least " + a + " minutes' walk from a station";
    }

    String floor(Range r) {
        int a = (int) r.min(), b = (int) r.max();
        if (ja()) return a > 0 && b > 0 ? (a == b ? a + "階" : a + "〜" + b + "階") : a > 0 ? a + "階以上" : b + "階以下";
        return a > 0 && b > 0 ? (a == b ? "the " + ordinal(a) + " floor" : "the " + ordinal(a) + " to " + ordinal(b) + " floor") : a > 0 ? "the " + ordinal(a) + " floor or higher" : "the " + ordinal(b) + " floor or lower";
    }

    static String ordinal(int n) { int last = n % 100; String end = last >= 11 && last <= 13 ? "th" : switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; }; return n + end; }

    // ---------------------------------------------------------------- why a listing is set aside

    String keepOut(String k) { return ja() ? "除外した「" + k + "」に当たります" : "it has " + k + ", which you keep out"; }
    String tenure(boolean wantRent) { return ja() ? (wantRent ? "売買物件ですが、ご希望は賃貸です" : "賃貸物件ですが、ご希望は購入です") : wantRent ? "it is for sale, and you want to rent" : "it is for rent, and you want to buy"; }
    String notInPlace() { return ja() ? "ご希望の場所にありません" : "it is not in a place you want"; }
    /** In another region: the listing's town and region, and the region wanted. */
    String elsewhere(String city, String region, String wanted) {
        if (ja()) return (city.isBlank() ? "" : city) + (region.isBlank() ? "" : "（" + region + "）") + "にあり、" + wanted + "ではありません";
        return "it is in " + (city.isBlank() ? "" : city + ", ") + region + ", not in " + wanted;
    }
    /** A town of the name in another region. */
    String elsewhereTown(String city, String region, String named, String wanted) {
        if (ja()) return city + "（" + region + "）にあり、" + wanted + "の" + named + "ではありません";
        return "it is in " + city + ", " + region + ", not " + named + ", " + wanted;
    }
    String costs(boolean more, Range budget, boolean rent) {
        if (ja()) return (rent ? "家賃" : "価格") + "が" + (more ? "上限の" : "下限の") + budgetWords(budget) + "を" + (more ? "超えています" : "下回っています");
        return "it costs " + (more ? "more" : "less") + " than your budget of " + budgetWords(budget);
    }
    String costsPer(double per, boolean more, double bound, boolean metric) {
        String u = perAreaUnit(metric);
        if (ja()) return (metric ? "㎡単価" : "単価") + "が" + money(per) + u + "で、" + (more ? "上限の" + money(bound) + u + "を超えています" : "下限の" + money(bound) + u + "を下回っています");
        return "it costs " + money(per) + u + ", " + (more ? "more than the " + money(bound) + u + " you want at most" : "less than the " + money(bound) + u + " you want at least");
    }
    /** Fewer or more rooms than wanted; in Japanese with the listing's 間取り when known: "間取りが4LDKで、ご希望は2LDKまでです". */
    String rooms(boolean fewer, double bound, String listedLayout, List<String> wantedLayouts) {
        if (ja()) {
            boolean bounded = wantedLayouts != null && wantedLayouts.stream().anyMatch(x -> x.matches(".*(以上|以下|まで|のみ)$"));   // "2LDK以上" carries its bound already
            String want = wantedLayouts == null || wantedLayouts.isEmpty() ? Home.Listing.num(bound) + "部屋" + (fewer ? "以上" : "まで") : String.join("、", wantedLayouts) + (bounded ? "" : fewer ? "以上" : "まで");
            return (listedLayout == null || listedLayout.isBlank() ? "部屋数が" + (fewer ? "ご希望の" + want + "に足りません" : "ご希望の" + want + "を超えています") : "間取りが" + listedLayout + "で、ご希望は" + want + "です");
        }
        return fewer ? "it has fewer bedrooms than the " + Home.Listing.num(bound) + " you want" : "it has more bedrooms than the " + Home.Listing.num(bound) + " you want at most";
    }
    String baths(boolean fewer, double bound) {
        if (ja()) return fewer ? "浴室がご希望の" + Home.Listing.num(bound) + "か所に足りません" : "浴室がご希望の" + Home.Listing.num(bound) + "か所を超えています";
        return fewer ? "it has fewer bathrooms than the " + Home.Listing.num(bound) + " you want" : "it has more bathrooms than the " + Home.Listing.num(bound) + " you want at most";
    }
    String size(boolean smaller, double boundSqft, boolean metric) {
        if (ja()) return smaller ? "広さがご希望の" + size(boundSqft, metric) + "に足りません" : "広さがご希望の上限" + size(boundSqft, metric) + "を超えています";
        return smaller ? "it is smaller than the " + size(boundSqft, metric) + " you want" : "it is larger than the " + size(boundSqft, metric) + " you want at most";
    }
    String kind(String listed) { return ja() ? "種別が" + listed + "で、ご希望の種類ではありません" : "it is a " + listed + ", not the kind you want"; }
    String walkOff(int listed, Range r) {
        if (ja()) return r.max() > 0 && listed > r.max() ? "駅まで徒歩" + listed + "分で、ご希望の" + (int) r.max() + "分以内を超えています" : "駅まで徒歩" + listed + "分で、ご希望の" + (int) r.min() + "分以上より近すぎます";
        return r.max() > 0 && listed > r.max() ? "it is " + listed + " minutes' walk from the station, more than the " + (int) r.max() + " you want" : "it is " + listed + " minutes' walk from the station, nearer than the " + (int) r.min() + " you want";
    }
    String floorOff(int listed, Range r) {
        if (ja()) return r.min() > 0 && listed < r.min() ? listed + "階で、ご希望の" + (int) r.min() + "階以上ではありません" : listed + "階で、ご希望の" + (int) r.max() + "階以下ではありません";
        return r.min() > 0 && listed < r.min() ? "it is on the " + ordinal(listed) + " floor, below the " + ordinal((int) r.min()) + " you want" : "it is on the " + ordinal(listed) + " floor, above the " + ordinal((int) r.max()) + " you want at most";
    }

    // ---------------------------------------------------------------- what a near miss does not show

    String regionNotGiven(String regionKind) { return ja() ? (regionKind.equals("prefecture") ? "都道府県" : regionKind) + "の記載がありません" : regionKind + " not given"; }
    String perAreaUnknown(boolean noPrice, boolean noSize, boolean metric) {
        if (ja()) return (metric ? "㎡単価" : "単価") + "が分かりません：" + (noPrice && noSize ? "価格も面積も記載がありません" : noPrice ? "価格の記載がありません" : "面積の記載がありません");
        return "price per " + areaWord(metric) + " not known: " + (noPrice && noSize ? "neither the price nor the size is given" : noPrice ? "the price is not given" : "the size is not given");
    }
    String priceNotGiven() { return ja() ? "価格の記載がありません" : "price not given"; }
    String sizeNotGiven() { return ja() ? "面積の記載がありません" : "size not given"; }
    String bedsNotGiven() { return ja() ? "間取りの記載がありません" : "bedrooms not given"; }
    String bathsNotGiven() { return ja() ? "浴室数の記載がありません" : "bathrooms not given"; }
    String kindNotGiven() { return ja() ? "種別の記載がありません" : "kind of home not given"; }
    String walkNotGiven() { return ja() ? "駅までの徒歩分数の記載がありません" : "walk to the station not given"; }
    String floorNotGiven() { return ja() ? "階数の記載がありません" : "floor not given"; }

    /** The page's tiers. */
    String[] tiers() {
        return ja() ? new String[]{"場所も決めた条件もすべて合う", "場所は合うが、決めた条件の一部の記載がない", "ライブラリが推測した場所と合わない", "ライブラリが推測した場所と合わず、決めた条件の一部の記載もない"} : HomeCut.TIERS;
    }

    // ---------------------------------------------------------------- the cut read back

    String tenureWords(String tenure) { return ja() ? (tenure.equals("rent") ? "賃貸" : "購入") : tenure.equals("rent") ? "to rent" : "to buy"; }

    /** The place part: the towns, how far around, the regions alone. */
    String place(List<String> towns, List<String> regions, int nearby, int km, boolean placed, String station) {
        if (ja()) {
            String near = nearby > 0 ? "、または" + km + "km以内の" + nearby + "市区町村" : placed && km > 0 ? "、または" + km + "km以内" : "";
            String t = towns.isEmpty() ? "" : String.join("または", towns) + (station.isEmpty() ? "" : "（" + station + "）") + near;
            return t + (regions.isEmpty() ? "" : (t.isEmpty() ? "" : "、または") + String.join("または", regions) + "内のどこでも");
        }
        String near = nearby > 0 ? ", or one of " + nearby + " towns within " + km + " km" : placed && km > 0 ? ", or within " + km + " km of it" : "";
        return (towns.isEmpty() ? "" : "in " + String.join(" or ", towns) + (station.isEmpty() ? "" : " (" + station + ")") + near) + (regions.isEmpty() ? "" : (towns.isEmpty() ? "" : ", or ") + "anywhere in " + String.join(" or ", regions));
    }
    String kinds(List<String> kinds) {
        if (ja()) return String.join("または", kinds);
        return (kinds.get(0).matches("(?i)^[aeiou].*") ? "an " : "a ") + HomeCut.or(kinds);
    }
    String keepingOut(List<String> out) { return ja() ? String.join("、", out) + "を除く" : "keeping out " + Career.and(out); }
    String lot(Range r, boolean metric) { return ja() ? "敷地" + span(r, metric) : "a lot of " + span(r, metric); }
    String firstInOrder(List<String> prefer) { return ja() ? "順序はまず" + String.join("または", prefer) : "first in the order: " + HomeCut.or(prefer); }
    String nothingDecided() { return ja() ? "場所も予算も住まいの条件もまだ決まっていないので、すべての物件が候補です" : "every listing passes, since nothing about the place, the budget or the home is decided yet"; }
    String join(List<String> parts) { return String.join(ja() ? "；" : "; ", parts); }

    /** The parts of the summary that are the same in every search: a helper for tests. */
    static List<String> list(String... xs) { List<String> out = new ArrayList<>(); for (String x : xs) if (x != null && !x.isBlank()) out.add(x); return out; }
}
