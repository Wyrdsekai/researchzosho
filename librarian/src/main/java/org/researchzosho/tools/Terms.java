package org.researchzosho.tools;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sites whose terms of use forbid automated reading, as read on 2026-10-08: a fetch a model names is left out and the page is pointed
 * at instead, by its address from a search result. A person's own act (adding a page themselves) is not covered: that is their browser.
 */
public final class Terms {
    private Terms() { }

    /** Host suffix → the site's name, for the sentence that says why. */
    static final Map<String, String> SITES = new LinkedHashMap<>();
    static {
        for (String h : new String[]{"amazon.com", "amazon.co.jp", "amazon.co.uk", "amazon.de", "amazon.fr", "amazon.it", "amazon.es", "amazon.ca", "amazon.com.au", "amazon.in"}) SITES.put(h, "Amazon");
        SITES.put("consumerreports.org", "Consumer Reports"); SITES.put("nytimes.com", "The New York Times (Wirecutter)"); SITES.put("which.co.uk", "Which?"); SITES.put("test.de", "Stiftung Warentest");
        SITES.put("rtings.com", "RTINGS"); SITES.put("kakaku.com", "価格.com"); SITES.put("mercari.com", "Mercari"); SITES.put("mercari.jp", "メルカリ"); SITES.put("jp.mercari.com", "メルカリ");
        SITES.put("idealo.de", "idealo"); SITES.put("idealo.co.uk", "idealo"); SITES.put("idealo.fr", "idealo"); SITES.put("idealo.it", "idealo"); SITES.put("idealo.es", "idealo");
        SITES.put("pricerunner.com", "PriceRunner"); SITES.put("pricerunner.co.uk", "PriceRunner"); SITES.put("pricerunner.se", "PriceRunner"); SITES.put("geizhals.at", "Geizhals"); SITES.put("geizhals.de", "Geizhals"); SITES.put("geizhals.eu", "Geizhals");
        SITES.put("reddit.com", "Reddit"); SITES.put("camelcamelcamel.com", "camelcamelcamel"); SITES.put("keepa.com", "Keepa"); SITES.put("pricespy.co.uk", "PriceSpy"); SITES.put("manualslib.com", "ManualsLib"); SITES.put("gsmarena.com", "GSMArena");
    }

    /** The site's name when the address is on one whose terms forbid machine reading; null otherwise. */
    public static String forbids(String url) {
        if (url == null) return null;
        String host;
        try { host = URI.create(url.strip()).getHost(); } catch (Exception e) { return null; }
        if (host == null) return null;
        host = host.toLowerCase(Locale.ROOT);
        for (var e : SITES.entrySet()) if (host.equals(e.getKey()) || host.endsWith("." + e.getKey())) return e.getValue();
        return null;
    }
}
