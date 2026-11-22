package org.researchzosho.librarian;

import org.researchzosho.drive.DriveClient;
import org.researchzosho.drive.OpenRouterProviders;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.Map;

/**
 * Where what a library asks a hosted model goes, said to the person when it bears on the places the library avoids (0.8.1,
 * {@link Avoided}): for a model reached through OpenRouter, who serves it and who is left out ({@link OpenRouterProviders}); for a
 * model reached through Hugging Face's router, that a request cannot say which places to leave out; for a host the person named by
 * its address, where that host is run from, when that is known. A host the person named is used all the same: the sentence informs.
 */
public final class ModelPlaces {
    private ModelPlaces() { }

    /**
     * Where the companies behind some model hosts are based, by the end of the host's name. Recorded on 2026-11-20 from each company's own
     * site; a host whose name ends in a country's own letters (.cn, .hk, .ru, .su, .ir) needs no line here. It decides nothing: a host a person
     * names is used, and this table only lets the program say where its requests go.
     */
    static final Map<String, String> HOST_PLACE = Map.ofEntries(
            Map.entry("deepseek.com", "CN"), Map.entry("moonshot.cn", "CN"), Map.entry("aliyuncs.com", "CN"), Map.entry("bigmodel.cn", "CN"), Map.entry("z.ai", "CN"),
            Map.entry("minimax.chat", "CN"), Map.entry("minimaxi.com", "CN"), Map.entry("siliconflow.cn", "CN"), Map.entry("siliconflow.com", "CN"), Map.entry("volces.com", "CN"),
            Map.entry("baidubce.com", "CN"), Map.entry("stepfun.com", "CN"), Map.entry("lingyiwanwu.com", "CN"), Map.entry("xf-yun.com", "CN"), Map.entry("tencentcloudapi.com", "CN"),
            // Yandex's own domains (its names under .ru need no line). Read on 2026-11-20: Yandex Cloud's documentation gives the address its
            // services' endpoints are listed at as api.cloud.yandex.net (its page api-design-guide/concepts/endpoints; written here without the scheme, so that it is not taken for a host this program asks), and
            // the user agreement of yandex.com says it is "regulated and interpreted according to laws of the Russian Federation". The
            // documentation page's own footer names a company of the same group outside Russia (Direct Cursus Technology L.L.C.): the
            // case the owners' rule exists for, a place stated for an arm and another for the company behind it
            Map.entry("yandex.net", "RU"), Map.entry("yandex.cloud", "RU"), Map.entry("yandex.com", "RU"));

    /** Endings a country holds beside its two letters: the Soviet Union's, still Russia's registry; Russia's, China's, Hong Kong's and Macau's in their own script (as written and as a name server holds them). */
    static final Map<String, String> OWN_ENDINGS = Map.ofEntries(Map.entry("su", "RU"), Map.entry("\u0440\u0444", "RU"), Map.entry("xn--p1ai", "RU"),
            Map.entry("\u4e2d\u56fd", "CN"), Map.entry("xn--fiqs8s", "CN"), Map.entry("\u4e2d\u570b", "CN"), Map.entry("xn--fiqz9s", "CN"),
            Map.entry("\u9999\u6e2f", "HK"), Map.entry("xn--j6w193g", "HK"), Map.entry("\u6fb3\u9580", "MO"), Map.entry("xn--mix891f", "MO"));
    /** Two-letter endings that are sold to anybody and say nothing of a place: a model host on one of them is not placed by it. */
    static final Set<String> GENERIC_TWO_LETTERS = Set.of("ai", "io", "co", "me", "tv", "cc", "ly", "to", "fm", "gg", "sh", "so", "is", "im", "la", "ws", "vc", "ac", "st", "nu", "tk", "ml", "ga", "cf", "gq", "pw", "xyz");

    /**
     * What a yes allows or closes, in plain words, when one of the two settings of avoided places is about to be changed in the chat;
     * null for any other setting. A person who says yes reads here what will be let in, never only a setting's name and a code.
     */
    public static String changeSaid(String key, String value) {
        Chrome c = Chrome.now();
        if (Avoided.SETTING.equals(key)) {
            Set<String> now = Avoided.places(), then = Avoided.placesOf(value == null || value.isBlank() ? "none" : value);
            Set<String> allowed = new LinkedHashSet<>(now); allowed.removeAll(then);
            Set<String> added = new LinkedHashSet<>(then); added.removeAll(now);
            if (!allowed.isEmpty()) return c.sentences(List.of(c.t("let the providers of hosted models and the search engines based in {0} serve this library", Avoided.names(allowed, c)),
                    then.isEmpty() ? c.t("(it would then keep away from no place)") : c.t("(it would go on keeping away from {0})", Avoided.names(then, c))));
            if (!added.isEmpty()) return c.t("keep this library's hosted models and its search away from {0} as well", Avoided.names(added, c));
            return c.t("leave the places this library keeps away from as they are ({0})", Avoided.names(then, c));
        }
        if (Avoided.BY_OWNER.equals(key)) {
            boolean off = value != null && List.of("off", "no", "false", "0").contains(value.strip().toLowerCase(Locale.ROOT));
            String places = Avoided.names(Avoided.places(), c);
            List<String> who = OpenRouterProviders.ignoredByOwner(Avoided.places());
            return off ? c.t("let providers that are owned by a company based in {0} serve this library's hosted models, though they state another place for themselves (today: {1})", places, who.isEmpty() ? c.t("none on the list") : String.join(c.sep(), who))
                    : c.t("leave out the providers of hosted models that are owned by a company based in {0}, whatever place they state for themselves", places);
        }
        return null;
    }

    /** The country a model host is run from, as far as the program can say: its name's own country letters, else the table's; "" when neither says. */
    public static String placeOfHost(String host) {
        String h = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        if (h.isEmpty()) return "";
        // a name that ends in a country's own letters is that country's: .cn, .ru, .ir; and the endings a country holds beside its two letters
        int dot = h.lastIndexOf('.');
        String end = dot < 0 ? "" : h.substring(dot + 1);
        String own = OWN_ENDINGS.get(end);
        if (own != null) return own;
        if (end.matches("[a-z]{2}") && !GENERIC_TWO_LETTERS.contains(end)) return end.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, String> e : HOST_PLACE.entrySet()) if (h.equals(e.getKey()) || h.endsWith("." + e.getKey())) return e.getValue();
        return "";
    }

    /** What a person is told about where an entry's requests go; "" when nothing bears on the places this library avoids. */
    public static String said(ModelEntries.Entry e) {
        if (e == null || Avoided.places().isEmpty()) return "";   // any host a person names, whether or not it is one of the hosted services this program knows by name
        String host = e.host() == null ? "" : e.host().toLowerCase(Locale.ROOT);
        Chrome c = Chrome.now();
        if (DriveClient.hostIs(host, "openrouter.ai")) return OpenRouterProviders.said(e.model());
        if (DriveClient.hostIs(host, "huggingface.co")) {
            // one provider named after the model id ("…:together") is the only one asked
            if (e.model() != null && e.model().matches(".+:[A-Za-z0-9-]+") && !e.model().matches(".+:(fastest|cheapest)")) return "";
            return c.t("Hugging Face's router picks the provider for each request, and a request cannot say which places to leave out, so this model may be served from {0}. To keep it to one provider, write the provider's name after the model's id ({1}), or turn providers off in your Hugging Face settings.",
                    Avoided.names(Avoided.places(), c), e.model() + ":together");
        }
        String place = placeOfHost(host);
        if (!Avoided.avoids(place)) return "";
        return c.t("{0} is run from {1}, a place this library keeps its content away from: what the library asks this model goes there. You named the host, so it is used; the setting {2} leaves out a router's providers, not a host you name.",
                host, Avoided.name(place, c), "avoid_places");
    }
}
