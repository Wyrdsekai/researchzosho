package org.researchzosho.drive;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.librarian.Avoided;
import org.researchzosho.librarian.Chrome;
import org.researchzosho.tools.Outbound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Where a router's providers are (0.8.1). OpenRouter sends a request for a model to one of the providers that serve it, the cheaper
 * ones first, and says of each provider where it is based and where its machines stand ({@code /api/v1/providers}: a slug, a
 * {@code headquarters} country, {@code datacenters} countries; some state neither). A library whose owner keeps its content away from
 * some places ({@link Avoided}: China, Hong Kong and Macau unless they say otherwise) tells OpenRouter with every request to leave out
 * every provider based or running in one of them ({@code provider.ignore}, {@link #ignored}); on 2026-11-20 the cheapest provider of the
 * model this program suggests for the word "deepseek" was Baidu.
 *
 * <p>The list is the router's own, read once a day in the background and kept beside the settings. A copy as it was read when this
 * version was made ships in the program and is what is used until the first reading succeeds and whenever none does: what is left out
 * never depends on a request that can fail. Nothing here names a provider; which ones are left out comes from the list and from the
 * places the owner set. A provider that states no place is not left out, and {@link #unplaced} says how many there are.
 */
public final class OpenRouterProviders {
    private OpenRouterProviders() { }

    private static final Logger log = LoggerFactory.getLogger(OpenRouterProviders.class);
    private static final ObjectMapper M = new ObjectMapper();
    static final String HOST = "openrouter.ai", LIST = "https://openrouter.ai/api/v1/providers", ENDPOINTS = "https://openrouter.ai/api/v1/models/%s/endpoints";
    private static final HttpClient HTTP = Outbound.client(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());

    /** One provider as the router's list states it; a place not stated is "" or none. */
    public record Provider(String slug, String name, String headquarters, List<String> datacenters) {
        /** Whether it is based, or runs machines, in one of {@code places}. */
        public boolean in(Set<String> places) {
            if (places.contains(headquarters)) return true;
            for (String d : datacenters) if (places.contains(d)) return true;
            return false;
        }
        public boolean placed() { return !headquarters.isEmpty() || !datacenters.isEmpty(); }
    }

    /** A body from the router, or null when it cannot be read; a test stands one in, and the suite reads none (the property researchzosho.pricelist=off, as for the price list). */
    static volatile Function<String, String> FETCH = "off".equals(System.getProperty("researchzosho.pricelist")) ? url -> null : OpenRouterProviders::fetch;
    /** The clock, for the day's one reading; a test stands it in. */
    static volatile LongSupplier NOW = System::currentTimeMillis;

    private static volatile Map<String, Provider> providers;   // by slug
    private static volatile String readOn = "";                  // the day the list in use was read
    private static volatile boolean shippedInUse = true;
    private static volatile long tried;                          // when a reading was last begun
    static final long EVERY_MS = 24 * 3600_000L;

    static String fetch(String url) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 ? r.body() : null;
        } catch (IOException e) { return null; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
    }

    /** The list as a body gives it: {@code data[] {slug, name, headquarters, datacenters[]}}; null when the body is no such list. */
    static Map<String, Provider> parse(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            Map<String, Provider> out = new LinkedHashMap<>();
            for (JsonNode p : M.readTree(body).path("data")) {
                String slug = p.path("slug").asText("").strip().toLowerCase(Locale.ROOT);
                if (slug.isEmpty()) continue;
                List<String> dcs = new ArrayList<>();
                for (JsonNode d : p.path("datacenters")) if (d.isTextual() && !d.asText().isBlank()) dcs.add(d.asText().strip().toUpperCase(Locale.ROOT));
                out.put(slug, new Provider(slug, p.path("name").asText(slug), p.path("headquarters").isTextual() ? p.path("headquarters").asText().strip().toUpperCase(Locale.ROOT) : "", List.copyOf(dcs)));
            }
            return out.size() < 5 ? null : out;   // an answer of another shape, or a list cut short, is not taken for the list
        } catch (IOException e) { return null; }
    }

    static Path kept() { return Config.home().resolve("openrouter-providers.json"); }

    /** The list in use: the last one read, else the one kept from an earlier reading, else the copy that ships. A reading is begun in the background when the day's is due. */
    static Map<String, Provider> providers() {
        Map<String, Provider> now = providers;
        if (now == null) synchronized (OpenRouterProviders.class) {
            if ((now = providers) == null) {
                Map<String, Provider> fromDisk = null;
                String day = "";
                try { if (Files.isRegularFile(kept())) { JsonNode k = M.readTree(Files.readString(kept())); fromDisk = parse(k.toString()); day = k.path("read_on").asText(""); } } catch (IOException | RuntimeException e) { fromDisk = null; }
                if (fromDisk != null) { now = fromDisk; readOn = day; shippedInUse = false; }
                else {
                    try (InputStream in = OpenRouterProviders.class.getResourceAsStream("openrouter-providers.json")) {
                        JsonNode k = M.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        now = parse(k.toString()); readOn = k.path("read_on").asText("");
                    } catch (IOException | RuntimeException e) { now = null; }
                    if (now == null) now = Map.of();
                    shippedInUse = true;
                }
                providers = now;
            }
        }
        refreshWhenDue();
        return now;
    }

    private static void refreshWhenDue() {
        long t = NOW.getAsLong();
        if (t - tried < EVERY_MS && tried != 0) return;
        synchronized (OpenRouterProviders.class) {
            if (t - tried < EVERY_MS && tried != 0) return;
            tried = t;
        }
        int begun = generation;
        Path keep = kept();   // where the list is kept, as it is now: the reading ends later, and by then a test may have another home
        Thread reader = new Thread(() -> readNow(begun, keep), "openrouter-providers");
        reader.setDaemon(true);
        reader.start();
    }

    /** One reading of the router's list: taken into use and kept when it is a list, left alone when it is not. Returns whether it was. */
    static boolean readNow() { return readNow(generation, kept()); }

    private static volatile int generation;   // counts the times the list was forgotten: a reading begun before is not taken into use after

    private static boolean readNow(int begun, Path keep) {
        String body;
        try { body = FETCH.apply(LIST); } catch (RuntimeException e) { body = null; }
        Map<String, Provider> read = parse(body);
        if (read == null) { if (body != null) log.warn("OpenRouter's list of providers came back in a shape this program does not read; the list it had stays in use"); return false; }
        String day = LocalDate.now().toString();
        synchronized (OpenRouterProviders.class) {
            if (begun != generation) return false;   // the list was forgotten while this reading went on: it is taken into use by nobody, and kept nowhere
            providers = read; readOn = day; shippedInUse = false;
            try {
                ObjectNode k = (ObjectNode) M.readTree(body);
                k.put("read_on", day);
                Files.createDirectories(keep.getParent());
                Files.writeString(keep, M.writeValueAsString(k));
            } catch (IOException | RuntimeException e) { /* in use for this process all the same */ }
        }
        return true;
    }

    /** Forgets what was read: a test's way back to the copy that ships. */
    public static void forget() { synchronized (OpenRouterProviders.class) { generation++; providers = null; readOn = ""; shippedInUse = true; tried = 0; } SERVED.clear(); }

    /**
     * The company that controls a provider, where it is based in another country than the one the router's list states for the
     * provider (0.8.1). The list says where a provider is registered, and seven of its 123 stated Singapore on 2026-11-20 while being
     * the international arms of companies based in mainland China. Which they are is data, not code: a dated table that ships beside
     * the providers' copy ({@code openrouter-controllers.json}), a row per provider with one public source, the company's own site or
     * a filing, and the words of it. A provider with no row is taken at what the router's list states.
     */
    public record Controller(String slug, String country, String company, String source) { }

    private static volatile Map<String, Controller> controllers;
    private static volatile String controllersOn = "";

    /** The table that ships: by slug. Empty when it cannot be read: then only the router's own statements count. */
    public static Map<String, Controller> controllers() {
        Map<String, Controller> c = controllers;
        if (c != null) return c;
        Map<String, Controller> out = new LinkedHashMap<>();
        try (InputStream in = OpenRouterProviders.class.getResourceAsStream("openrouter-controllers.json")) {
            if (in != null) {
                JsonNode k = M.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                controllersOn = k.path("recorded_on").asText("");
                for (JsonNode r : k.path("rows")) {
                    String slug = r.path("slug").asText("").strip().toLowerCase(Locale.ROOT), country = r.path("country").asText("").strip().toUpperCase(Locale.ROOT);
                    if (!slug.isEmpty() && country.matches("[A-Z]{2}") && !r.path("source").asText("").isBlank()) out.put(slug, new Controller(slug, country, r.path("company").asText(slug), r.path("source").asText()));
                }
            }
        } catch (IOException | RuntimeException e) { out.clear(); }
        return controllers = Map.copyOf(out);
    }

    /** The day the owners' table was recorded. */
    public static String controllersRecordedOn() { controllers(); return controllersOn; }

    /** Whether a provider is owned from one of {@code places}, by the table. */
    static boolean ownedIn(String slug, Set<String> places) { Controller c = controllers().get(slug); return c != null && places.contains(c.country()); }

    /**
     * The providers a request leaves out for the places this library avoids, by their slugs, in order: every one based or running in
     * such a place by the router's list, and, unless the person allowed them ({@code Avoided.byOwner}), every one owned by a company
     * based there by the table.
     */
    public static List<String> ignored() { return ignored(Avoided.places(), Avoided.byOwner()); }

    public static List<String> ignored(Set<String> places) { return ignored(places, Avoided.byOwner()); }

    public static List<String> ignored(Set<String> places, boolean byOwner) {
        if (places.isEmpty()) return List.of();
        Set<String> out = new TreeSet<>();
        for (Provider p : providers().values()) if (p.in(places)) out.add(p.slug());
        if (byOwner) for (Provider p : providers().values()) if (ownedIn(p.slug(), places)) out.add(p.slug());   // a row of the table bears on a provider the list holds
        return List.copyOf(out);
    }

    /** The providers left out by ownership alone: owned from an avoided place, and neither based nor running there by the router's list. */
    public static List<String> ignoredByOwner(Set<String> places) {
        Set<String> out = new TreeSet<>();
        Map<String, Provider> all = providers();
        for (Controller c : controllers().values()) if (places.contains(c.country()) && all.get(c.slug()) != null && !all.get(c.slug()).in(places)) out.add(c.slug());
        return List.copyOf(out);
    }

    /** How many providers of the list state no place at all: they are not left out, since nothing says where they are. */
    public static int unplaced() { int n = 0; for (Provider p : providers().values()) if (!p.placed()) n++; return n; }

    /** How many providers the list holds, and the day it was read ("" when it never was); whether it is the copy that ships. */
    public static int count() { return providers().size(); }
    public static String readOn() { providers(); return readOn; }
    public static boolean shipped() { providers(); return shippedInUse; }

    /**
     * Who serves one model, as the router lists its endpoints: how many providers, which of them are left out, which of those by their
     * owner alone ({@code byOwner}, a part of {@code leftOut}), and how many state no place.
     */
    public record Served(String model, int providers, List<String> leftOut, List<String> byOwner, int unplaced, boolean known) {
        public Served(String model, int providers, List<String> leftOut, int unplaced, boolean known) { this(model, providers, leftOut, List.of(), unplaced, known); }
        /** Every provider that serves it is left out: it will not answer while the places stay avoided. */
        public boolean none() { return known && providers > 0 && leftOut.size() == providers; }
        /** The providers that would serve it if the ones left out by their owner alone were allowed. */
        public boolean noneButOwned() { return none() && !byOwner.isEmpty(); }
    }

    private static final Map<String, Object[]> SERVED = new ConcurrentHashMap<>();   // model → {Served, when read}

    /** The providers of {@code model}'s endpoints against the places avoided now; not known when the router's list of them cannot be read. */
    public static Served served(String model) {
        Set<String> places = Avoided.places();
        boolean byOwner = Avoided.byOwner();
        String key = model + "|" + String.join(",", places) + "|" + byOwner;
        Object[] had = SERVED.get(key);
        if (had != null && NOW.getAsLong() - (Long) had[1] < EVERY_MS) return (Served) had[0];
        Served s = new Served(model, 0, List.of(), 0, false);
        String body;
        try { body = FETCH.apply(String.format(ENDPOINTS, model)); } catch (RuntimeException e) { body = null; }
        if (body != null) try {
            Map<String, Provider> all = providers();
            Set<String> slugs = new TreeSet<>(), out = new TreeSet<>(), owned = new TreeSet<>();
            int nowhere = 0;
            JsonNode endpoints = M.readTree(body).path("data").path("endpoints");
            if (endpoints.isArray()) {
                for (JsonNode e : endpoints) {
                    String tag = e.path("tag").asText("").strip().toLowerCase(Locale.ROOT);
                    String slug = tag.contains("/") ? tag.substring(0, tag.indexOf('/')) : tag;   // a provider's slug, then its variant or region
                    if (!slug.isEmpty()) slugs.add(slug);
                }
                for (String slug : slugs) {
                    Provider p = all.get(slug);
                    String name = p == null ? slug : p.name();
                    if (p != null && p.in(places)) out.add(name);
                    else if (byOwner && ownedIn(slug, places)) { out.add(name); owned.add(name); }   // registered elsewhere, owned from a place avoided
                    else if (p == null || !p.placed()) nowhere++;
                }
                s = new Served(model, slugs.size(), List.copyOf(out), List.copyOf(owned), nowhere, true);
            }
        } catch (IOException | RuntimeException e) { /* not known */ }
        // known: kept for the day; not readable now: not asked again for ten minutes, so a list of models does not wait on each one
        SERVED.put(key, new Object[]{s, s.known() ? NOW.getAsLong() : NOW.getAsLong() - EVERY_MS + 600_000L});
        return s;
    }

    /** The settings' names as a person writes them. */
    static final String SETTING_NAME = "avoid_places", BY_OWNER_NAME = "avoid_by_owner";

    /**
     * What a person is told when nobody is left to serve a model: what happened, the places, and how to allow it by this library's
     * own settings (the router's answer points at its own settings page, which is not where these providers were left out).
     * {@code served} not known: the router said so itself, and which providers serve the model could not be read.
     */
    public static String noneLeft(Served served) {
        Chrome c = Chrome.now();
        String places = Avoided.names(Avoided.places(), c);
        String what = served.known()
                ? (Avoided.byOwner()
                    ? c.t("Every provider that serves {0} through OpenRouter is based or runs in a place this library keeps away from, or is owned by a company based in one, so nothing was sent.", served.model())
                    : c.t("Every provider that serves {0} through OpenRouter is based or runs in a place this library keeps away from, so nothing was sent.", served.model()))
                : c.t("OpenRouter found no provider for {0} among those this library allows: this library leaves out the providers of the places it avoids.", served.model());
        String how = served.known() && served.noneButOwned()
                ? c.t("To let the providers left out only because of the company that owns them serve it ({0}): researchzosho settings set {1} off. To allow every provider: researchzosho settings set {2} none. Or choose another model.", String.join(c.sep(), served.byOwner()), BY_OWNER_NAME, SETTING_NAME)
                : Avoided.byOwner() && !served.known()
                ? c.t("To allow the providers left out only because of the company that owns them: researchzosho settings set {0} off. To allow every provider: researchzosho settings set {1} none (or name the places to keep away from). Or choose another model.", BY_OWNER_NAME, SETTING_NAME)
                : c.t("To let them serve it, change the places this library avoids: researchzosho settings set {0} none (or name the places to keep away from), or choose another model.", SETTING_NAME);
        return c.sentences(List.of(what, c.t("The places: {0}.", places), how));
    }

    /** How one model is served, for the line a person reads when they add it or list their models; "" when the router's list of its providers cannot be read now. */
    public static String said(String model) {
        Served s = served(model);
        if (!s.known() || s.providers() == 0) return "";
        Chrome c = Chrome.now();
        if (s.none()) return s.noneButOwned()
                ? c.t("Every provider of this model is left out for a place this library avoids or for its owner's: it will not answer unless you allow the providers left out only because of the company that owns them ({0}: researchzosho settings set {1} off) or all of them (researchzosho settings set {2} none).", String.join(c.sep(), s.byOwner()), BY_OWNER_NAME, SETTING_NAME)
                : c.t("Every provider of this model is based or runs in a place this library avoids: it will not answer unless you allow them (researchzosho settings set {0} none).", SETTING_NAME);
        // each piece a sentence of the language files with its own end mark, put one after another as the language sets sentences
        String by = s.providers() == 1 ? c.t("Served by 1 provider.") : c.t("Served by {0} providers.", s.providers());
        List<String> forPlace = new ArrayList<>(s.leftOut()); forPlace.removeAll(s.byOwner());
        String out = s.leftOut().isEmpty() ? (Avoided.places().isEmpty() ? "" : c.t("None of them is left out."))
                : forPlace.isEmpty() ? "" : forPlace.size() == 1 ? c.t("1 is left out for the place it is based or runs in ({0}).", forPlace.get(0)) : c.t("{0} are left out for the place they are based or run in ({1}).", forPlace.size(), String.join(c.sep(), forPlace));
        String owned = s.byOwner().isEmpty() ? "" : s.byOwner().size() == 1 ? c.t("1 is left out for its owner's place ({0}); researchzosho settings set {1} off lets it serve.", s.byOwner().get(0), BY_OWNER_NAME)
                : c.t("{0} are left out for their owner's place ({1}); researchzosho settings set {2} off lets them serve.", s.byOwner().size(), String.join(c.sep(), s.byOwner()), BY_OWNER_NAME);
        String nowhere = s.unplaced() == 0 ? "" : s.unplaced() == 1 ? c.t("1 states no place and is not left out.") : c.t("{0} state no place and are not left out.", s.unplaced());
        return c.sentences(List.of(by, out, owned, nowhere));
    }

    /** One line on the router's list itself, for the models list: how many providers it holds, how many are left out and why, how many state no place, the places, and when each list was read. */
    public static String listSaid() {
        Chrome c = Chrome.now();
        Set<String> places = Avoided.places();
        if (places.isEmpty()) return c.t("OpenRouter: no place is avoided, so no provider is left out (settings: {0}).", SETTING_NAME);
        List<String> owned = Avoided.byOwner() ? ignoredByOwner(places) : List.of();
        String line = c.t("OpenRouter providers: {0}; left out of every request for the place they are based or run in: {1}; stating no place, not left out: {2} (the list as read on {3}).",
                count(), ignored(places, false).size(), unplaced(), readOn().isEmpty() ? c.t("an unknown day") : readOn());
        String more = owned.isEmpty() ? "" : c.t("Left out as well, because the company that owns them is based in such a place: {0} ({1}); by a table recorded on {2} with a public source for each row; researchzosho settings set {3} off lifts this.",
                owned.size(), String.join(c.sep(), owned), controllersRecordedOn(), BY_OWNER_NAME);
        return c.sentences(List.of(line, more, c.t("The places this library avoids: {0} (settings: {1}).", Avoided.names(places, c), SETTING_NAME)));
    }
}
