package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The places a library's content is kept away from (0.8.1): country codes the person owns as a setting. Unset, they are the countries
 * of concern of the United States Department of Justice's rule on access to sensitive personal data (28 CFR 202.601, in effect since
 * 2025-04-08, read on 2026-11-20): China, which that rule defines with Hong Kong and Macau (28 CFR 202.208), Cuba, Iran, North Korea,
 * Russia and Venezuela. A list with a stated basis, not one picked by hand; the person may name other places, or none.
 *
 * <p>One setting drives both uses. A hosted model reached through a router is never served by a provider based or running in one of
 * these places, nor, unless the person allows it ({@link #BY_OWNER}), by one owned by a company based there ({@code OpenRouterProviders});
 * and the search engines based there are left out of the SearXNG settings this program writes ({@link Searx}). A model host that is
 * itself there is said to be where it is. The setting names places, never companies or models: which provider or engine is where comes
 * from what each source states, read at the time.
 */
public final class Avoided {
    private Avoided() { }

    public static final String SETTING = "RESEARCHZOSHO_AVOID_PLACES";
    /** What an unset setting means: the countries of concern of 28 CFR 202.601, China with Hong Kong and Macau as 28 CFR 202.208 defines it. */
    static final String UNSET = "CN, HK, MO, CU, IR, KP, RU, VE";
    /** Where the unset list comes from, as a person is told. */
    public static final String BASIS = "the countries of concern of the United States Department of Justice's data security rule (28 CFR 202.601, in effect since 2025-04-08)";

    /**
     * Whether a router's provider that is owned by a company based in an avoided place is left out too, whatever place it states for
     * itself: on unless the person set it off. Decided by the owner on 2026-11-20: "chinese owned providers as excluded unless the user
     * turns them on"; the rule is the general one, for any avoided place.
     */
    public static final String BY_OWNER = "RESEARCHZOSHO_AVOID_BY_OWNER";
    public static boolean byOwner() {
        String v = Config.get(BY_OWNER);
        return v == null || v.isBlank() || !List.of("off", "no", "false", "0").contains(v.strip().toLowerCase(Locale.ROOT));
    }

    /** The codes a value of the setting names ("none", or anything that is no two-letter code, names no place); the unset list for null or blank. */
    public static Set<String> placesOf(String value) {
        String v = value == null || value.isBlank() ? UNSET : value;
        Set<String> out = new LinkedHashSet<>();
        for (String c : v.split("[,;\\s]+")) if (c.matches("[A-Za-z]{2}")) out.add(c.toUpperCase(Locale.ROOT));
        return out;
    }

    /** The country codes avoided, in capitals, in the order written; none when the setting says {@code none}. */
    public static Set<String> places() {
        return placesOf(Config.get(SETTING));   // "none", "off" or anything that is no two-letter code names no place
    }

    /** Whether {@code code} (a country code as a source states it, any case; null or blank: nowhere stated) is a place this library avoids. */
    public static boolean avoids(String code) { return code != null && !code.isBlank() && places().contains(code.strip().toUpperCase(Locale.ROOT)); }

    /** The meaning the language files give a place's name beside it: a country or region, named inside a sentence or a list. */
    static final String PLACE = "a country or region, named inside a sentence or a list";

    /** A place by the name a person knows, in English: China for CN, Hong Kong for HK (for what is English throughout, SearXNG's notes). */
    public static String name(String code) { return name(code, Chrome.ENGLISH); }

    /**
     * A place by the name a person knows, in the language of {@code c}: the eight of the unset list through the language files, as the
     * language ordinarily names them (香港, マカオ; not the long official forms a locale gives); any other place by the locale's name for
     * its code, and by the code itself when the locale has none.
     */
    public static String name(String code, Chrome c) {
        String k = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        return switch (k) {
            case "CN" -> c.tc("China", PLACE);
            case "HK" -> c.tc("Hong Kong", PLACE);
            case "MO" -> c.tc("Macao", PLACE);
            case "CU" -> c.tc("Cuba", PLACE);
            case "IR" -> c.tc("Iran", PLACE);
            case "KP" -> c.tc("North Korea", PLACE);
            case "RU" -> c.tc("Russia", PLACE);
            case "VE" -> c.tc("Venezuela", PLACE);
            default -> {
                String n = k.isEmpty() ? "" : Locale.of("", k).getDisplayCountry(c.locale());
                yield n.isBlank() ? String.valueOf(code) : n;
            }
        };
    }

    /** Places by their names in English, as a sentence lists them: "China", "China and Hong Kong", "China, Hong Kong and Macao". */
    public static String names(Collection<String> codes) { return names(codes, Chrome.ENGLISH); }

    /** Places by their names in the language of {@code c}, joined as that language joins a list ({@link Chrome#and}): 中国、香港とマカオ. */
    public static String names(Collection<String> codes, Chrome c) {
        List<String> n = new ArrayList<>();
        for (String code : codes) { String name = name(code, c); if (!n.contains(name)) n.add(name); }
        return c.and(n);
    }
}
