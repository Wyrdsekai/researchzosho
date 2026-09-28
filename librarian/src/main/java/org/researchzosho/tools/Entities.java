package org.researchzosho.tools;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML character references turned back into the characters they stand for: {@code &#x27;}, {@code &#8217;}, {@code &rsquo;},
 * {@code &amp;}. A page read as text kept them as they were written, so "a project&#x27;s popularity" never matched the quotation
 * "a project's popularity" and the check said the quotation was in no source. One pass, so {@code &amp;lt;} becomes {@code &lt;}
 * and not {@code <}; a reference that names no character is left as it is.
 */
public final class Entities {
    private Entities() { }

    private static final Pattern REF = Pattern.compile("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,8});");

    private static final Map<String, String> NAMED = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"), Map.entry("quot", "\""), Map.entry("apos", "'"),
            Map.entry("nbsp", " "), Map.entry("ensp", " "), Map.entry("emsp", " "), Map.entry("thinsp", " "),
            Map.entry("lsquo", "‘"), Map.entry("rsquo", "’"), Map.entry("ldquo", "“"), Map.entry("rdquo", "”"), Map.entry("sbquo", "‚"), Map.entry("bdquo", "„"),
            Map.entry("laquo", "«"), Map.entry("raquo", "»"), Map.entry("lsaquo", "‹"), Map.entry("rsaquo", "›"),
            Map.entry("ndash", "–"), Map.entry("mdash", "—"), Map.entry("hellip", "…"), Map.entry("middot", "·"), Map.entry("bull", "•"),
            Map.entry("copy", "©"), Map.entry("reg", "®"), Map.entry("trade", "™"), Map.entry("deg", "°"), Map.entry("times", "×"), Map.entry("divide", "÷"),
            Map.entry("euro", "€"), Map.entry("pound", "£"), Map.entry("yen", "¥"), Map.entry("cent", "¢"), Map.entry("sect", "§"), Map.entry("para", "¶"),
            Map.entry("prime", "′"), Map.entry("Prime", "″"), Map.entry("shy", ""), Map.entry("zwj", "‍"), Map.entry("zwnj", "‌"));

    public static String decode(String s) {
        if (s == null || s.indexOf('&') < 0) return s;
        Matcher m = REF.matcher(s);
        StringBuilder out = new StringBuilder(s.length());
        while (m.find()) {
            String ref = m.group(1), rep = null;
            if (ref.charAt(0) == '#') {
                try {
                    int cp = ref.length() > 1 && (ref.charAt(1) == 'x' || ref.charAt(1) == 'X') ? Integer.parseInt(ref.substring(2), 16) : Integer.parseInt(ref.substring(1));
                    if (cp > 0 && cp <= 0x10FFFF && (cp < 0xD800 || cp > 0xDFFF)) rep = cp == 0xA0 ? " " : new String(Character.toChars(cp));
                } catch (NumberFormatException ignored) { }
            } else rep = NAMED.get(ref);
            m.appendReplacement(out, Matcher.quoteReplacement(rep == null ? m.group() : rep));
        }
        m.appendTail(out);
        return out.toString();
    }
}
