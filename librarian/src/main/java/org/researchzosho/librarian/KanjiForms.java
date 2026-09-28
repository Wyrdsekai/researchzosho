package org.researchzosho.librarian;

/**
 * Old and new forms of the characters family names are written in. A register from before 1946 writes 齋藤 and 髙橋;
 * a directory from after it writes 斎藤 and 高橋. They are one name, and a search has to try both. The pairs are the
 * ones common in surnames and given names; the rule is general: a name's other form is another name of the same node.
 */
public final class KanjiForms {

    private KanjiForms() { }

    private static final String[] PAIRS = {
            "齋斎", "齊斉", "髙高", "﨑崎", "嵜崎", "邊辺", "邉辺", "澤沢", "濱浜", "濵浜", "廣広", "國国", "嶋島", "嶌島", "德徳", "惠恵", "櫻桜", "眞真",
            "龍竜", "瀧滝", "萬万", "與与", "榮栄", "衞衛", "圓円", "會会", "學学", "樂楽", "關関", "氣気", "舊旧", "藏蔵", "總総", "瀨瀬", "彌弥", "禮礼",
            "壽寿", "實実", "寶宝", "豐豊", "靜静", "鐵鉄", "傳伝", "驛駅", "應応", "縣県", "條条", "莊荘", "淺浅", "黑黒", "顯顕", "鷗鴎", "曾曽"};

    /** The name in today's forms; the same string when it has no old form in it. */
    public static String modern(String name) {
        if (name == null) return "";
        StringBuilder b = new StringBuilder();
        name.codePoints().forEach(c -> {
            String ch = new String(Character.toChars(c)), to = ch;
            for (String p : PAIRS) { int first = p.codePointAt(0); if (first == c) { to = p.substring(Character.charCount(first)); break; } }
            b.append(to);
        });
        return b.toString();
    }
}
