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
 * its address, where that host is run from, when that is known, or the place it is owned from, when a company registered elsewhere runs
 * it ({@link #HOST_OWNER}). A host the person named is used all the same: the sentence informs.
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

    /**
     * A model host run by a company registered in one place and owned by a company based in another, recorded as the owners' table
     * records such a provider of the router's ({@code openrouter-controllers.json}): the controlling company's country and the company as
     * its source names it, one public source with its words, further sources under {@code also}, and a note that says what the row rests
     * on; and beside these, who runs the host and where that company is registered, which is what the person reads, and the day the
     * sources were read. It decides nothing either: the library says where what it asks goes. CodeZaiku carries the same row (its commit
     * of 2026-11-24). The pages are written without their scheme, as above, so that none is taken for a host this program asks.
     */
    record HostOwner(String runBy, String registered, String country, String company, String recordedOn, String source, String says, List<String[]> also, String note) { }

    /** By the end of the host's name, as {@link #HOST_PLACE}. */
    static final Map<String, HostOwner> HOST_OWNER = Map.of(
            // Moonshot AI's international platform, the address of the host words moonshot and kimi (ModelEntries)
            "moonshot.ai", new HostOwner("Moonshot AI PTE. LTD.", "SG", "CN", "Moonshot AI", "2026-11-25",
                    "www.moonshot.ai/about", "76 Zhichun Road, Haidian District, Beijing",
                    List.of(new String[]{"platform.kimi.ai/docs/agreement/userprivacy", "Our services are provided and controlled by MOONSHOT AI PTE. LTD. (“we”or”Moonshot AI”) in Singapore through web pages."},
                            new String[]{"platform.kimi.ai/docs/api/chat", "\"url\":\"https://api.moonshot.ai\",\"description\":\"Production\""}),
                    "The tie between the host and the company rests on the company's own site, not on a sentence of ownership: its About page gives the Beijing address and links its "
                            + "platform (platform.kimi.ai), whose privacy policy (last updated April 30, 2025) says the services are provided and controlled by MOONSHOT AI PTE. LTD. "
                            + "in Singapore and whose API reference gives https://api.moonshot.ai as the production server. As the owners' table's row for the provider moonshotai."));

    /** Who runs a model host and where it is owned from, by the table; null when the table has no row for it. */
    static HostOwner ownerOfHost(String host) {
        String h = host == null ? "" : host.strip().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        for (Map.Entry<String, HostOwner> e : HOST_OWNER.entrySet()) if (h.equals(e.getKey()) || h.endsWith("." + e.getKey())) return e.getValue();
        return null;
    }

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
        HostOwner owner = ownerOfHost(h);   // a host run by a company registered in one place and owned from another: run from the first
        return owner == null ? "" : owner.registered();
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
        if (Avoided.avoids(place))
            return c.t("{0} is run from {1}, a place this library keeps its content away from: what the library asks this model goes there. You named the host, so it is used; the setting {2} leaves out a router's providers, not a host you name.",
                    host, Avoided.name(place, c), "avoid_places");
        // registered elsewhere and owned from a place avoided: said while the library leaves out a router's providers of that kind
        HostOwner owner = ownerOfHost(host);
        if (owner == null || !Avoided.byOwner() || !Avoided.avoids(owner.country())) return "";
        return c.t("{0} is run by {1}, a company registered in {2} and owned by a company based in {3}, a place this library keeps its content away from: what the library asks this model goes to that company. You named the host, so it is used; the setting {4} leaves out a router's providers for their owner's place, not a host you name.",
                host, owner.runBy(), Avoided.name(owner.registered(), c), Avoided.name(owner.country(), c), "avoid_by_owner");
    }
}
