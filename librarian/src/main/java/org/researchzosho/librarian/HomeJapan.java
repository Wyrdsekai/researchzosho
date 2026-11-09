package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.librarian.HomeCut.Place;
import org.researchzosho.librarian.HomeCut.Range;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The home search in Japan: how a Japanese ask, or an English ask about a place in Japan, is read into the same cut as an American one.
 * The place is a municipality, 市区町村, with its prefecture when said, and a station when one is named; the money is in 万円 and 億円;
 * the rooms are a 間取り (2LDK is two rooms beside the living, dining and kitchen); the size is in ㎡ or 坪; the age is 築N年; the walk
 * to the station is 徒歩N分; the floor is N階. Each is turned into the words the cut's readers already take, so one judge serves both
 * countries. The municipality's code, the five digits every portal and the ministry's records key a town by, comes from Wikidata's record
 * of the town, with the towns within the radius; a test puts its own lookup here.
 */
public final class HomeJapan {
    private HomeJapan() { }
    private static final ObjectMapper M = new ObjectMapper();

    /** A prefecture: its two-digit code, its name, and its name in Latin letters. */
    public record Prefecture(String code, String ja, String romaji) {
        /** The region SUUMO files the prefecture under: a code of its own. */
        String suumoRegion() { return SUUMO_REGIONS.get(code); }
        /** The prefecture's name in SUUMO's addresses, read off its top page on 2026-11-05: the Latin name in lower case, but "gumma" and "hokkaido_". */
        String suumoSlug() { return code.equals("10") ? "gumma" : code.equals("01") ? "hokkaido_" : romaji.toLowerCase(Locale.ROOT); }
    }

    static final List<Prefecture> PREFECTURES = List.of(
            new Prefecture("01", "北海道", "Hokkaido"), new Prefecture("02", "青森県", "Aomori"), new Prefecture("03", "岩手県", "Iwate"), new Prefecture("04", "宮城県", "Miyagi"),
            new Prefecture("05", "秋田県", "Akita"), new Prefecture("06", "山形県", "Yamagata"), new Prefecture("07", "福島県", "Fukushima"), new Prefecture("08", "茨城県", "Ibaraki"),
            new Prefecture("09", "栃木県", "Tochigi"), new Prefecture("10", "群馬県", "Gunma"), new Prefecture("11", "埼玉県", "Saitama"), new Prefecture("12", "千葉県", "Chiba"),
            new Prefecture("13", "東京都", "Tokyo"), new Prefecture("14", "神奈川県", "Kanagawa"), new Prefecture("15", "新潟県", "Niigata"), new Prefecture("16", "富山県", "Toyama"),
            new Prefecture("17", "石川県", "Ishikawa"), new Prefecture("18", "福井県", "Fukui"), new Prefecture("19", "山梨県", "Yamanashi"), new Prefecture("20", "長野県", "Nagano"),
            new Prefecture("21", "岐阜県", "Gifu"), new Prefecture("22", "静岡県", "Shizuoka"), new Prefecture("23", "愛知県", "Aichi"), new Prefecture("24", "三重県", "Mie"),
            new Prefecture("25", "滋賀県", "Shiga"), new Prefecture("26", "京都府", "Kyoto"), new Prefecture("27", "大阪府", "Osaka"), new Prefecture("28", "兵庫県", "Hyogo"),
            new Prefecture("29", "奈良県", "Nara"), new Prefecture("30", "和歌山県", "Wakayama"), new Prefecture("31", "鳥取県", "Tottori"), new Prefecture("32", "島根県", "Shimane"),
            new Prefecture("33", "岡山県", "Okayama"), new Prefecture("34", "広島県", "Hiroshima"), new Prefecture("35", "山口県", "Yamaguchi"), new Prefecture("36", "徳島県", "Tokushima"),
            new Prefecture("37", "香川県", "Kagawa"), new Prefecture("38", "愛媛県", "Ehime"), new Prefecture("39", "高知県", "Kochi"), new Prefecture("40", "福岡県", "Fukuoka"),
            new Prefecture("41", "佐賀県", "Saga"), new Prefecture("42", "長崎県", "Nagasaki"), new Prefecture("43", "熊本県", "Kumamoto"), new Prefecture("44", "大分県", "Oita"),
            new Prefecture("45", "宮崎県", "Miyazaki"), new Prefecture("46", "鹿児島県", "Kagoshima"), new Prefecture("47", "沖縄県", "Okinawa"));

    /** SUUMO's regions by prefecture code, read off its area pages on 2026-11-05: 010 北海道, 020 東北, 030 首都圏, 040 甲信越・北陸, 050 東海, 060 関西, 070 四国, 080 中国, 090 九州・沖縄. */
    static final Map<String, String> SUUMO_REGIONS = new LinkedHashMap<>();
    static {
        String[][] regions = {{"010", "01"}, {"020", "02 03 04 05 06 07"}, {"030", "08 09 10 11 12 13 14"}, {"040", "15 16 17 18 19 20"}, {"050", "21 22 23 24"},
                {"060", "25 26 27 28 29 30"}, {"080", "31 32 33 34 35"}, {"070", "36 37 38 39"}, {"090", "40 41 42 43 44 45 46 47"}};
        for (String[] r : regions) for (String code : r[1].split(" ")) SUUMO_REGIONS.put(code, r[0]);
    }

    /** The region's code the cut keeps for a prefecture: "JP-13" for 東京都. */
    static String region(Prefecture p) { return "JP-" + p.code(); }

    /** The prefecture a region code names, or null. */
    static Prefecture prefecture(String region) {
        if (region == null || !region.startsWith("JP-")) return null;
        String code = region.substring(3);
        for (Prefecture p : PREFECTURES) if (p.code().equals(code)) return p;
        return null;
    }

    /** The prefecture a piece names, in Japanese ("東京都", "東京") or Latin letters ("Tokyo", "Tokyo Prefecture", "Hokkaido"); null when none. */
    static Prefecture prefectureNamed(String piece) {
        String p = piece == null ? "" : piece.strip();
        if (p.isEmpty()) return null;
        String bare = p.replaceAll("(?i)\\s*(prefecture|pref\\.?|-ken|-to|-fu|-do)$", "").strip();
        for (Prefecture x : PREFECTURES) {
            if (x.ja().equals(p) || x.ja().equals(p + "県") || x.ja().equals(p + "府") || x.ja().equals(p + "都")) return x;
            if (x.romaji().equalsIgnoreCase(bare) || x.romaji().replace("o", "ō").equalsIgnoreCase(bare)) return x;
        }
        if (p.equals("東京")) return PREFECTURES.get(12);
        return null;
    }

    /** The prefecture's code from a listing's prefecture, written either way; "" when it names none. */
    static String prefCode(String state) { Prefecture p = prefectureNamed(state); return p == null ? "" : region(p); }

    static final Pattern CJK = Pattern.compile("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}]");

    /** Whether the words carry Japanese: a kanji or a kana in them. */
    public static boolean isJapanese(String words) { return words != null && CJK.matcher(words).find(); }

    /** Whether a place's words name Japan: Japanese writing, a prefecture in Latin letters, or the country. */
    static boolean looks(String want) {
        if (want == null || want.isBlank()) return false;
        if (isJapanese(want)) return true;
        for (String piece : want.split("\\s*[,;/]\\s*|\\s+(?:or|and|in)\\s+")) {
            String p = piece.strip().replaceAll("^(?i)(near|around|in|the)\\s+", "").replaceAll("(?i)\\s+(area|ward|city)$", "");
            if (p.equalsIgnoreCase("japan") || prefectureNamed(p) != null) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- the place

    static final String JA_RUN = "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー々]";
    /** A municipality as written: a prefecture before it or not, then the town ending in 市, 区, 町 or 村 ("東京都杉並区", "横浜市中区", "三鷹市"). */
    static final Pattern JA_TOWN = Pattern.compile("(" + JA_RUN + "{1,5}?[都道府県])?(" + JA_RUN + "{1,8}?[市区町村])");
    static final Pattern JA_STATION = Pattern.compile("(" + JA_RUN + "{1,8}?)駅");
    /** What goes with a place and is no place: how far, the station's surroundings, the particles. */
    static final Pattern JA_NOISE = Pattern.compile("(?:周辺|近く|付近|沿線|エリア|内|で|の|に|か|や|と|または|もしくは|あたり)");

    /** The places in Japanese or English words about Japan, as {@link HomeCut#places(String)} reads them; a station named goes with the first town. */
    static List<Place> places(String want) {
        List<Place> out = new ArrayList<>();
        if (want == null || want.isBlank()) return out;
        String w = HomeJapan.halfwidth(want).replaceAll("[（(]", ", ").replaceAll("[）)]", " ");
        if (isJapanese(w)) {
            Prefecture said = null;
            for (String piece : w.split("[,、;；/]")) {   // "杉並区, 東京都": the prefecture after the town is its
                Prefecture p = prefectureNamed(piece.strip());
                if (p != null && !JA_TOWN.matcher(piece).find()) said = p;
            }
            Matcher m = JA_TOWN.matcher(w);
            int lastEnd = -1;
            while (m.find()) {
                String town = m.group(2), prefWords = m.group(1) == null ? "" : m.group(1);
                if (town.contains("市区町") || JA_NOISE.matcher(town).matches()) continue;
                town = town.replaceFirst("^(?:" + JA_NOISE.pattern() + ")+", "");
                if (town.length() < 2) continue;
                if (town.endsWith("区") && m.start() == lastEnd && !out.isEmpty() && out.get(out.size() - 1).town().endsWith("市")) {   // "横浜市中区": a ward of a designated city keeps its city
                    Place city = out.remove(out.size() - 1);
                    town = city.town() + town; prefWords = city.regionSaid().isEmpty() ? prefWords : city.regionSaid();
                }
                lastEnd = m.end();
                Prefecture p = prefWords.isEmpty() ? said : prefectureNamed(prefWords);
                if (p == null) { p = prefectureNamed(town); if (p != null) { out.add(new Place("", region(p), p.ja(), town)); continue; } }   // "東京都" alone: anywhere in it
                Place place = p == null ? new Place(town, "", "", "") : new Place(town, region(p), p.ja(), prefWords.isEmpty() ? p.ja() : prefWords);
                if (out.stream().noneMatch(x -> x.key().equals(place.key()))) out.add(place);
            }
            if (out.isEmpty() && said != null) out.add(new Place("", region(said), said.ja(), said.ja()));
            return out;
        }
        // English: "Suginami, Tokyo", "Suginami-ku, Tokyo, Japan", "Mitaka or Musashino, Tokyo", "Osaka"
        List<String> towns = new ArrayList<>(); Prefecture pref = null;
        for (String piece : w.split("\\s*[,;/]\\s*|\\s+(?:or|and)\\s+")) {
            String p = piece.strip().replaceAll("^(?i)(near|around|in|within|the)\\s+", "").replaceAll("(?i)\\s+(area|ward|city|town|village)$", "").replaceAll("(?i)-(ku|shi|machi|cho|mura|son)$", "").strip();
            if (p.isEmpty() || p.equalsIgnoreCase("japan")) continue;
            Prefecture x = prefectureNamed(p);
            if (x != null && (pref == null || towns.isEmpty())) { if (towns.isEmpty() && pref != null) towns.add(pref.romaji()); pref = x; continue; }   // "Osaka, Japan": a prefecture named alone is looked up as the city of its name
            towns.add(p);
        }
        if (towns.isEmpty() && pref != null) towns.add(pref.romaji());
        for (String t : towns) {
            Prefecture own = prefectureNamed(t);
            Place place = pref != null && pref != own ? new Place(t, region(pref), pref.romaji(), pref.romaji()) : new Place(t, "", "", "");
            if (out.stream().noneMatch(x -> x.key().equals(place.key()))) out.add(place);
        }
        return out;
    }

    /** The station named with the place ("荻窪駅", "near Ogikubo station"); "" when none. */
    static String station(String want) {
        if (want == null) return "";
        String w = halfwidth(want);
        Matcher m = JA_STATION.matcher(w);
        while (m.find()) {
            String s = m.group(1);
            String[] bits = s.split("[のでにへとやか、]", -1);   // the name is what stands right before 駅: "杉並区の荻窪駅" is 荻窪, and "杉並区で駅徒歩" names none
            s = bits[bits.length - 1];
            if (s.isEmpty()) continue;
            String bare = s.replaceFirst("^(?:" + JA_RUN + "{1,5}?[都道府県])?" + JA_RUN + "{1,8}?[区]", "");   // "杉並区荻窪駅" is 荻窪; a town written before it with no particle
            if (bare.isEmpty()) { if (s.endsWith("区")) continue; } else s = bare;   // a ward alone before 駅 names no station
            s = s.replaceFirst("^(?:" + JA_NOISE.pattern() + ")+", "");
            if (s.isEmpty() || s.matches(".*(最寄|各|近|徒歩).*")) continue;
            return s + "駅";
        }
        Matcher e = Pattern.compile("(?i)\\b([A-Z][a-z]+(?:-[a-z]+)?)\\s+station\\b").matcher(w);
        return e.find() ? e.group(1) + " station" : "";
    }

    /** Whether a listing's town names the place: the town written in it, "東京都杉並区浜田山３" for 杉並区. */
    static boolean in(String town, String name) {
        if (town == null || name == null || name.isBlank()) return false;
        return halfwidth(town).replace(" ", "").contains(halfwidth(name).replace(" ", ""));
    }

    /** The prefecture and the municipality off a listing's address: {"東京都", "杉並区", "浜田山３"}; the prefecture "" when the address starts at the town. */
    static String[] split(String address) {
        String a = address == null ? "" : address.strip();   // as the portal writes it, full-width digits and all
        Matcher m = Pattern.compile("^(" + JA_RUN + "{1,5}?[都道府県])?(" + JA_RUN + "{1,8}?(?:市" + JA_RUN + "{1,6}?区|[市区町村]))(.*)$").matcher(a);
        if (!m.find()) return new String[]{"", "", a};
        return new String[]{m.group(1) == null ? "" : m.group(1), m.group(2), m.group(3).strip()};
    }

    /** The district of an address, as the ministry's records name it: "浜田山３" is 浜田山, "荻窪3丁目5-2" is 荻窪. */
    static String district(String rest) {
        String r = halfwidth(rest == null ? "" : rest).strip();
        r = r.replaceAll("[0-9０-９一二三四五六七八九十]+丁目.*$", "").replaceAll("[0-9]+[-番号].*$", "").replaceAll("[0-9]+$", "").strip();
        return r;
    }

    // ---------------------------------------------------------------- the figures

    /** Full-width digits, letters and signs made ASCII, and the wave dashes one: "１５万円〜" is "15万円~". */
    static String halfwidth(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch >= '\uFF10' && ch <= '\uFF19') b.append((char) ('0' + ch - '\uFF10'));
            else if (ch >= '\uFF21' && ch <= '\uFF3A') b.append((char) ('A' + ch - '\uFF21'));
            else if (ch >= '\uFF41' && ch <= '\uFF5A') b.append((char) ('a' + ch - '\uFF41'));
            else if (ch == '\uFF0C') b.append(','); else if (ch == '\uFF0E') b.append('.'); else if (ch == '\uFF0D' || ch == '\u2212') b.append('-');
            else if (ch == '\u301C' || ch == '\uFF5E' || ch == '\u223C') b.append('~'); else if (ch == '\u3000') b.append(' ');
            else if (ch == '\uFF08') b.append('('); else if (ch == '\uFF09') b.append(')');
            else b.append(ch);
        }
        return b.toString();
    }

    static final Pattern YEN = Pattern.compile("(\\d+(?:,\\d{3})*(?:\\.\\d+)?)\\s*億\\s*(?:(\\d+(?:,\\d{3})*(?:\\.\\d+)?)\\s*万)?\\s*円?|(\\d+(?:,\\d{3})*(?:\\.\\d+)?)\\s*万\\s*円?|(\\d{1,3}(?:,\\d{3})+|\\d+)\\s*円");

    /** A sum in yen written the Japanese way, as a figure: "15万円" → 150000, "4,480万円" → 44800000, "1億2000万円" → 120000000, "11.7万" → 117000; 0 when none. */
    public static long yen(String s) {
        if (s == null) return 0;
        Matcher m = YEN.matcher(halfwidth(s));
        if (!m.find()) return 0;
        try {
            if (m.group(1) != null) return Math.round(Double.parseDouble(m.group(1).replace(",", "")) * 100_000_000 + (m.group(2) == null ? 0 : Double.parseDouble(m.group(2).replace(",", "")) * 10_000));
            if (m.group(3) != null) return Math.round(Double.parseDouble(m.group(3).replace(",", "")) * 10_000);
            return Long.parseLong(m.group(4).replace(",", ""));
        } catch (NumberFormatException e) { return 0; }
    }

    /** A sum in yen in words: "15万円", "11.7万円", "4,480万円", "1億2,000万円"; under ten thousand, "8,500円". */
    public static String yenWords(double yen) {
        long n = Math.round(yen);
        if (n < 10_000) return String.format(Locale.ROOT, "%,d", n) + "円";
        long oku = n / 100_000_000, rest = n % 100_000_000;
        double man = rest / 10_000.0;
        String manWords = man == Math.floor(man) ? String.format(Locale.ROOT, "%,d", Math.round(man)) : String.format(Locale.ROOT, "%,.1f", man);
        if (oku > 0) return String.format(Locale.ROOT, "%,d", oku) + "億" + (rest == 0 ? "" : manWords + "万") + "円";
        return manWords + "万円";
    }

    /** The Japanese words of a range made the words the cut's readers take: "15万円以下" is "150000 or less", "4000万~6000万円" is "40000000 to 60000000", "15万円前後" is "150000 around". */
    static String normal(String want) {
        if (want == null || want.isBlank()) return "";
        String w = halfwidth(want);
        Matcher m = YEN.matcher(w);
        StringBuilder b = new StringBuilder();
        while (m.find()) m.appendReplacement(b, " " + yen(m.group()) + " ");
        w = m.appendTail(b).toString();
        w = w.replaceAll("(\\d)\\s*(?:~|から)\\s*(\\d)", "$1 to $2");   // "4000万〜6000万円", "4000万円から6000万円": the two ends
        w = w.replaceAll("以下|以内|まで|未満|を超えない|迄", " or less ").replaceAll("以上|から|より上|超", " or more ").replaceAll("前後|程度|くらい|ぐらい|位|約|ほど", " around ").replaceAll("ちょうど|きっかり", " exactly ");
        return w.replaceAll("\\s+", " ").strip();
    }

    /** Whether the words carry a figure in yen or a Japanese range word, so {@link #normal} has something to do. */
    static boolean hasJapaneseFigures(String want) { return want != null && (YEN.matcher(halfwidth(want)).find() || want.matches(".*(以下|以内|まで|未満|以上|から|前後|程度|くらい|約|~|〜|～).*")); }

    /** A size with Japanese units made metric words: "50㎡以上" is "50 m² or more", "20坪" is "66.1 m²". */
    static String normalArea(String want) {
        if (want == null || want.isBlank()) return "";
        String w = halfwidth(want);
        Matcher t = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*坪").matcher(w);
        StringBuilder b = new StringBuilder();
        while (t.find()) t.appendReplacement(b, String.format(Locale.ROOT, "%.1f m² ", Double.parseDouble(t.group(1)) * TSUBO_M2));
        w = t.appendTail(b).toString();
        w = w.replaceAll("㎡|平米|平方メートル|m2", " m² ");
        return normal(w);
    }

    /** Square metres in a 坪. */
    public static final double TSUBO_M2 = 3.30579;

    /** The rooms a 間取り names: 1K, 1DK, 1LDK and ワンルーム are 1; 2LDK is 2; 5K以上 is 5; 0 when the words name none. */
    public static int rooms(String layout) {
        if (layout == null) return 0;
        String l = halfwidth(layout).toUpperCase(Locale.ROOT);
        if (l.contains("ワンルーム") || l.matches(".*\\b1R\\b.*")) return 1;
        Matcher m = Pattern.compile("(\\d{1,2})\\s*(?:S?LDK|LDK|SDK|DK|SK|K|L|R)\\b").matcher(l);
        if (m.find()) return Integer.parseInt(m.group(1));
        Matcher n = Pattern.compile("(\\d{1,2})\\s*部屋").matcher(l);
        return n.find() ? Integer.parseInt(n.group(1)) : 0;
    }

    /** The 間取り words in the want, as written: "2LDK", "3LDK以上". */
    static final Pattern LAYOUT = Pattern.compile("(?i)(ワンルーム|\\d{1,2}\\s*(?:S?LDK|SDK|DK|SK|K|R)(?![A-Za-z]))(以上|以下|まで|のみ)?");

    /** The rooms wanted, from 間取り words: "2LDK以上" is two or more rooms, "3LDK" three or more (a bare count is the fewest, as in English), "1LDK以下" one at most. */
    static Range roomsRange(String want) {
        String w = halfwidth(want == null ? "" : want);
        Matcher m = LAYOUT.matcher(w);
        List<Integer> rs = new ArrayList<>(); String bound = "";
        while (m.find()) { int r = rooms(m.group(1)); if (r > 0) rs.add(r); if (m.group(2) != null) bound = m.group(2); }
        if (rs.isEmpty()) {
            Matcher n = Pattern.compile("(\\d{1,2})\\s*部屋\\s*(以上|以下|まで)?").matcher(w);
            if (!n.find()) return null;
            rs.add(Integer.parseInt(n.group(1))); bound = n.group(2) == null ? "" : n.group(2);
        }
        int min = rs.stream().min(Integer::compare).orElse(0), max = rs.stream().max(Integer::compare).orElse(0);
        if (rs.size() > 1 && bound.isEmpty()) return new Range(min, max);   // "2LDK〜3LDK", "2DK、2LDK"
        if (bound.equals("以下") || bound.equals("まで")) return new Range(0, max);
        if (bound.equals("のみ")) return new Range(min, max);
        return new Range(min, 0);
    }

    /** The year a home must be built after or before, from 築 words: "築10年以内" after ten years ago, "築20年以上" before twenty years ago, "新築" this year or last, "2000年以降" after 2000. */
    static String normalBuilt(String want, int thisYear) {
        if (want == null || want.isBlank()) return "";
        String w = halfwidth(want);
        Matcher m = Pattern.compile("築\\s*(\\d{1,3})\\s*年\\s*(以内|未満|以下|まで|以上|超)?").matcher(w);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1)); String b = m.group(2) == null ? "以内" : m.group(2);
            return (b.equals("以上") || b.equals("超") ? "before " : "after ") + (thisYear - n);
        }
        if (w.contains("新築")) return "after " + (thisYear - 1);
        if (w.contains("築浅")) return "after " + (thisYear - 10);
        Matcher y = Pattern.compile("(\\d{4})\\s*年\\s*(以降|以後|より後|以前|より前|まで)?").matcher(w);
        if (y.find()) { String b = y.group(2) == null ? "" : y.group(2); return (b.equals("以前") || b.equals("より前") || b.equals("まで") ? "before " : "after ") + y.group(1); }
        return "";
    }

    /** The most minutes on foot to the station, from the words: "徒歩10分以内", "駅から10分", "within 10 minutes of a station"; the fewest with 以上. Null when none. */
    static Range walk(String want) {
        if (want == null || want.isBlank()) return null;
        String w = halfwidth(want);
        Matcher m = Pattern.compile("(?:徒歩|歩いて|駅から|駅まで)?\\s*(\\d{1,3})\\s*分\\s*(以内|以下|まで|圏内|以上|超)?").matcher(w);
        if (m.find()) { int n = Integer.parseInt(m.group(1)); String b = m.group(2) == null ? "" : m.group(2); return b.equals("以上") || b.equals("超") ? new Range(n, 0) : new Range(0, n); }
        Matcher e = Pattern.compile("(?i)(\\d{1,3})\\s*(?:min(?:ute)?s?)(?:['’]?\\s*walk)?").matcher(w);
        if (e.find()) { int n = Integer.parseInt(e.group(1)); return w.matches("(?i).*\\b(at least|or more|more than)\\b.*") ? new Range(n, 0) : new Range(0, n); }
        return null;
    }

    /** The floor wanted: "2階以上" the second or higher, "3階以下" at most the third, "1階以外" the second or higher, "5階" that floor; "2nd floor or higher". Null when none. */
    static Range floor(String want) {
        if (want == null || want.isBlank()) return null;
        String w = halfwidth(want);
        Matcher m = Pattern.compile("(\\d{1,2})\\s*階(?!建)\\s*(以上|より上|以下|まで|以外|のみ)?").matcher(w);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1)); String b = m.group(2) == null ? "" : m.group(2);
            return switch (b) { case "以下", "まで" -> new Range(0, n); case "以外" -> n == 1 ? new Range(2, 0) : new Range(0, 0); case "以上", "より上" -> new Range(n, 0); default -> new Range(n, n); };
        }
        Matcher e = Pattern.compile("(?i)(\\d{1,2})(?:st|nd|rd|th)\\s+floor(?:\\s+(or higher|or above|and up|or lower|or below))?").matcher(w);
        if (e.find()) { int n = Integer.parseInt(e.group(1)); String b = e.group(2) == null ? "" : e.group(2); return b.contains("lower") || b.contains("below") ? new Range(0, n) : b.isEmpty() ? new Range(n, n) : new Range(n, 0); }
        if (w.matches("(?i).*\\b(not|no)\\s+(the\\s+)?(ground|first)\\s+floor\\b.*")) return new Range(2, 0);
        return null;
    }

    /** The kinds of home in Japanese words, as the cut keeps them: マンション, アパート, 一戸建て, 土地, テラスハウス. */
    static final List<String> KINDS = List.of("マンション", "アパート", "一戸建て", "土地", "テラスハウス");

    static List<String> kindWords(String want) {
        List<String> out = new ArrayList<>();
        String w = halfwidth(want == null ? "" : want);
        for (String k : KINDS) if (w.contains(k) || (k.equals("一戸建て") && (w.contains("戸建") || w.contains("一軒家"))) || (k.equals("テラスハウス") && w.contains("タウンハウス"))) out.add(k);
        return out;
    }

    /** Whether a listing's kind, as a portal writes it ("賃貸マンション", "中古一戸建て", "Single Family"), is the kind wanted, in either language. */
    static boolean kindIs(String listed, String wanted) {
        String a = halfwidth(listed).toLowerCase(Locale.ROOT), b = halfwidth(wanted).toLowerCase(Locale.ROOT);
        if (a.contains(b)) return true;
        return switch (b) {
            case "マンション", "condo", "apartment" -> a.contains("マンション") || a.contains("condo") || a.contains("apartment");
            case "アパート" -> a.contains("アパート") || a.contains("apartment");
            case "一戸建て", "house" -> a.contains("戸建") || a.contains("一軒家") || a.contains("single") || a.contains("house") || a.contains("detached");
            case "土地", "land" -> a.contains("土地") || a.contains("land") || a.contains("lot");
            case "テラスハウス", "townhouse" -> a.contains("テラス") || a.contains("タウン") || a.contains("town");
            default -> false;
        };
    }

    /** Whether the kind words are Japanese kinds. */
    static boolean japaneseKind(String k) { return KINDS.contains(k); }

    // ---------------------------------------------------------------- a Japanese ask, read without a model

    /**
     * What a Japanese ask settles, topic by topic, read by the program: the place (with the station), buy or rent, the budget, the
     * rooms, the size, the age, the walk, the floor, the kind and the distance, each in the person's own words. Empty for words that
     * carry no Japanese. What is read is decided; a model, when one answers, is asked only for the rest.
     */
    public static Map<String, String> read(String words) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!isJapanese(words)) return out;
        String w = halfwidth(words);
        List<Place> ps = places(w);
        String station = station(w);
        if (!ps.isEmpty() || !station.isEmpty()) {
            List<String> keys = new ArrayList<>(); for (Place p : ps) keys.add(p.regionAlone() ? p.regionName() : (p.regionName().isEmpty() ? p.town() : p.regionName() + p.town()));
            out.put("place", String.join("、", keys) + (station.isEmpty() ? "" : (keys.isEmpty() ? "" : "、") + station));
        }
        Matcher d = Pattern.compile("(\\d{1,3})\\s*(km|キロ)\\s*(以内|圏内)?").matcher(w);
        if (d.find()) out.put("distance", d.group(1) + " km");
        if (w.matches(".*(賃貸|借り|家賃|貸し).*")) out.put("tenure", "賃貸");
        else if (w.matches(".*(購入|買|売買|中古|新築|分譲|物件価格|販売).*")) out.put("tenure", "購入");
        Matcher b = Pattern.compile("((?:家賃|賃料|価格|予算|販売価格|総額)\\s*(?:は|が|:|：)?\\s*)?((?:\\d+(?:,\\d{3})*(?:\\.\\d+)?\\s*(?:億|万)(?:\\s*\\d+(?:,\\d{3})*(?:\\.\\d+)?\\s*万)?\\s*円?|\\d{1,3}(?:,\\d{3})+\\s*円|\\d+\\s*円)(?:\\s*(?:~|から)\\s*(?:\\d+(?:,\\d{3})*(?:\\.\\d+)?\\s*(?:億|万)(?:\\s*\\d+(?:,\\d{3})*(?:\\.\\d+)?\\s*万)?\\s*円?))?\\s*(?:以下|以内|まで|未満|以上|前後|程度|くらい|位)?)").matcher(w);
        if (b.find()) out.put("budget", ((b.group(1) == null ? "" : b.group(1)) + b.group(2)).strip());
        Matcher a = Pattern.compile("(\\d+(?:\\.\\d+)?\\s*(?:㎡|平米|平方メートル|m2|坪)(?:\\s*(?:~|から)\\s*\\d+(?:\\.\\d+)?\\s*(?:㎡|平米|平方メートル|m2|坪)?)?\\s*(?:以上|以下|まで|以内|未満|前後|程度|くらい)?)").matcher(w);
        if (a.find()) out.put("size", a.group(1).strip());
        Matcher l = Pattern.compile("((?:ワンルーム|\\d{1,2}\\s*(?:S?LDK|SDK|DK|SK|K|R)(?![A-Za-z]))(?:\\s*(?:~|から|、|・)\\s*(?:\\d{1,2}\\s*(?:S?LDK|SDK|DK|SK|K|R)(?![A-Za-z])))*\\s*(?:以上|以下|まで|のみ)?|\\d{1,2}\\s*部屋\\s*(?:以上|以下|まで)?)").matcher(w);
        if (l.find()) out.put("bedrooms", l.group(1).strip());
        Matcher y = Pattern.compile("(築\\s*\\d{1,3}\\s*年\\s*(?:以内|未満|以下|まで|以上|超)?|新築|築浅|\\d{4}\\s*年\\s*(?:以降|以後|以前|より前|より後))").matcher(w);
        if (y.find()) out.put("built", y.group(1).strip());
        Matcher k = Pattern.compile("((?:駅)?(?:から|まで)?\\s*徒歩\\s*\\d{1,3}\\s*分\\s*(?:以内|以下|まで|圏内|以上)?)").matcher(w);
        if (k.find()) out.put("walk", k.group(1).strip());
        Matcher f = Pattern.compile("(\\d{1,2}\\s*階(?!建)\\s*(?:以上|より上|以下|まで|以外|のみ)?)").matcher(w);
        if (f.find()) out.put("floor", f.group(1).strip());
        List<String> kinds = kindWords(w);
        if (!kinds.isEmpty()) out.put("kind", String.join("、", kinds));
        return out;
    }

    // ---------------------------------------------------------------- the municipality, from Wikidata

    /** What the lookup gives for a municipality: where it was taken to be, its names, its five-digit code, its centre, and the municipalities within the radius by their Japanese names. */
    public record Lookup(String where, String ja, String en, String code, double lat, double lon, List<String> towns, String why) {
        /** A lookup that answered. */
        public Lookup(String where, String ja, String en, String code, double lat, double lon, List<String> towns) { this(where, ja, en, code, lat, lon, towns, ""); }
        public Lookup { if (why == null) why = ""; }
        public static final Lookup NONE = new Lookup("", "", "", "", 0, 0, List.of());
        /** Wikidata did not answer: not the same as no municipality of the name, and not kept, so the next search asks again. */
        public static final Lookup SILENT = new Lookup("", "", "", "", 0, 0, List.of(), "did not answer");
        public boolean found() { return !code.isEmpty(); }
        public boolean silent() { return why.equals("did not answer"); }
    }

    /** The lookup: the town as written, the prefecture's Japanese name or "", the parent city for a ward of one or "", and the radius. A test puts its own here. */
    public interface Near { Lookup apply(String town, String pref, String parent, int km); }
    static volatile Near NEAR = HomeJapan::fromWikidata;

    /** The lookup, kept with the library a month under the town's name and the radius. */
    static Lookup lookup(LibraryStore store, String town, String pref, int km) {
        String parent = "";
        String t = town;
        Matcher w = Pattern.compile("^(" + JA_RUN + "{1,6}市)(" + JA_RUN + "{1,6}区)$").matcher(halfwidth(town));
        if (w.find()) { parent = w.group(1); t = w.group(2); }
        Path dir = store.root().resolve("catalog").resolve("home-cache"), f = dir.resolve("jp-near-" + CareerSources.id(town.toLowerCase(Locale.ROOT) + "|" + pref + "|" + km) + ".json");
        try {
            if (Files.isRegularFile(f)) {
                JsonNode j = M.readTree(f.toFile());
                if (LocalDate.parse(j.path("at").asText("2000-01-01")).plusDays(31).isAfter(LocalDate.now())) return new Lookup(j.path("where").asText(""), j.path("ja").asText(""), j.path("en").asText(""), j.path("code").asText(""), j.path("lat").asDouble(0), j.path("lon").asDouble(0), HomeCut.list(j.path("towns")));
            }
        } catch (IOException | RuntimeException stale) { /* looked up again */ }
        Lookup l;
        try { l = NEAR.apply(t, pref, parent, km); } catch (RuntimeException e) { l = Lookup.SILENT; }
        if (l == null) l = Lookup.SILENT;
        if (l.silent()) return l;   // nothing to keep: asked again next time
        try {
            Files.createDirectories(dir);
            ObjectNode o = M.createObjectNode().put("town", town).put("pref", pref).put("km", km).put("at", LocalDate.now().toString()).put("where", l.where()).put("ja", l.ja()).put("en", l.en()).put("code", l.code()).put("lat", l.lat()).put("lon", l.lon());
            HomeCut.put(o, "towns", l.towns());
            M.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), o);
        } catch (IOException ignored) { }
        return l;
    }

    /** One query to Wikidata, asked a second time after a pause when it did not answer: a cold query over the places a town lies in can take ten seconds. Null when it did not answer twice. */
    static String sparql(String query) {
        String url = "https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        String body = CareerSources.get(url);
        if (body != null) return body;
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        return CareerSources.get(url);
    }

    /** Whether a 団体コード is a prefecture's rather than a municipality's: the three digits after the prefecture's are zeros. */
    static boolean prefectureCode(String code6) { return code6 != null && code6.length() >= 5 && code6.substring(2, 5).equals("000"); }

    /**
     * Wikidata, in two queries: the municipality is the place of that label, in Japanese or English, that carries a 団体コード (the
     * six-digit local government code, whose first five are the code the portals and the ministry key a town by) and lies in the
     * prefecture or the city named, the most populous when none is; the towns within the radius are the municipalities around its centre,
     * by their Japanese labels, largest first, the prefecture itself and the town left out.
     */
    static Lookup fromWikidata(String town, String pref, String parent, int km) {
        String name = halfwidth(town).replace("\"", "").strip();
        boolean ja = isJapanese(name);
        String labels = ja ? "\"" + name + "\"@ja" : "\"" + name + "\"@en \"" + name.replace("o", "ō") + "\"@en \"" + name.replace("u", "ū") + "\"@en";
        String first = "SELECT ?c ?cLabel ?en ?code ?loc ?pop (GROUP_CONCAT(DISTINCT ?aLabel; separator=\"; \") AS ?within) WHERE { VALUES ?name { " + labels + " } ?c rdfs:label ?name . ?c wdt:P429 ?code . ?c wdt:P625 ?loc . "
                + "OPTIONAL { ?c wdt:P1082 ?pop } OPTIONAL { ?c rdfs:label ?en FILTER(lang(?en) = \"en\") } OPTIONAL { ?c wdt:P131+ ?a . ?a rdfs:label ?aLabel FILTER(lang(?aLabel) = \"ja\" || lang(?aLabel) = \"en\") } "
                + "SERVICE wikibase:label { bd:serviceParam wikibase:language \"ja\". } } GROUP BY ?c ?cLabel ?en ?code ?loc ?pop ORDER BY DESC(?pop) LIMIT 20";
        String body = sparql(first);
        if (body == null) return Lookup.SILENT;
        record Found(String item, String ja, String en, String code, double lat, double lon, double pop, String within) { }
        List<Found> found = new ArrayList<>();
        try {
            for (JsonNode r : M.readTree(body).path("results").path("bindings")) {
                String code = r.path("code").path("value").asText("");
                if (prefectureCode(code)) continue;
                Matcher pt = Pattern.compile("Point\\(([-\\d.]+) ([-\\d.]+)\\)").matcher(r.path("loc").path("value").asText(""));
                double lat = 0, lon = 0; if (pt.find()) { lon = Double.parseDouble(pt.group(1)); lat = Double.parseDouble(pt.group(2)); }
                found.add(new Found(r.path("c").path("value").asText("").replaceFirst(".*/", ""), r.path("cLabel").path("value").asText(""), r.path("en").path("value").asText(""), code, lat, lon, r.path("pop").path("value").asDouble(0), r.path("within").path("value").asText("")));
            }
        } catch (IOException | NumberFormatException e) { return Lookup.NONE; }
        if (found.isEmpty()) return Lookup.NONE;
        Found chosen = null;
        Prefecture p = prefectureNamed(pref);
        for (Found f : found) if (!parent.isEmpty() && f.within().contains(parent)) { chosen = f; break; }
        if (chosen == null && p != null) for (Found f : found) if (f.within().contains(p.ja()) || f.within().toLowerCase(Locale.ROOT).contains(p.romaji().toLowerCase(Locale.ROOT))) { chosen = f; break; }
        if (chosen == null) chosen = found.get(0);
        String prefJa = ""; for (Prefecture x : PREFECTURES) if (chosen.within().contains(x.ja())) { prefJa = x.ja(); break; }
        String where = (parent.isEmpty() ? "" : parent) + chosen.ja() + (prefJa.isEmpty() ? "" : "（" + prefJa + "）");
        String q = "SELECT DISTINCT ?t ?tLabel ?code ?pop WHERE { wd:" + chosen.item() + " wdt:P625 ?loc . SERVICE wikibase:around { ?t wdt:P625 ?tloc . bd:serviceParam wikibase:center ?loc . bd:serviceParam wikibase:radius \"" + Math.max(1, km) + "\" . } "
                + "?t wdt:P429 ?code . FILTER(?t != wd:" + chosen.item() + ") OPTIONAL { ?t wdt:P1082 ?pop } SERVICE wikibase:label { bd:serviceParam wikibase:language \"ja\". } } ORDER BY DESC(?pop) LIMIT 150";
        List<String> towns = new ArrayList<>();
        body = sparql(q);
        if (body != null) {
            try {
                for (JsonNode r : M.readTree(body).path("results").path("bindings")) {
                    String label = r.path("tLabel").path("value").asText("").strip(), code = r.path("code").path("value").asText("");
                    if (label.isEmpty() || label.matches("Q\\d+") || prefectureCode(code) || label.equals(chosen.ja())) continue;
                    if (towns.stream().noneMatch(x -> x.equals(label))) towns.add(label);
                    if (towns.size() >= 100) break;
                }
            } catch (IOException ignored) { }
        }
        return new Lookup(where, (parent.isEmpty() ? "" : parent) + chosen.ja(), chosen.en(), chosen.code().length() >= 5 ? chosen.code().substring(0, 5) : "", chosen.lat(), chosen.lon(), towns);
    }
}
