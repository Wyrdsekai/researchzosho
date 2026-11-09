package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.tools.SearchTally;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Census tract's figures on a home's card, from the Census Bureau's American Community Survey, the five-year estimates: the median
 * household income, the median age, the owners' and renters' shares of the homes, the median home value and the median rent. The Bureau
 * asks for a key, free from its site; without one the card says so and where to get one. One call per tract, kept a year under the library.
 */
public final class HomeCensus {
    public static final String KEY = "RESEARCHZOSHO_CENSUS_KEY";
    public static final String SIGN_UP = "api.census.gov/data/key_signup.html";
    static final String SERVICE = "Census";
    /** The survey's years tried, newest first: the five-year estimates come out each December for the year before. */
    static final int[] YEARS = {2024, 2023, 2022};
    private static final ObjectMapper M = new ObjectMapper();
    /** The key, from the settings; a test puts its own here. */
    static volatile Supplier<String> KEY_VALUE = () -> Config.get(KEY);
    /** A plain fetch of the Bureau's API, the body or null: the home sensors' own fetch, so a test's stand-in there covers this too. */
    static volatile Function<String, String> FETCH = url -> HomeSensors.FETCH.apply(url);

    private HomeCensus() { }

    public static boolean keySet() { String k = KEY_VALUE.get(); return k != null && !k.isBlank(); }

    /** The figures of one tract for one survey; a figure the survey does not give is 0. */
    public record Figures(int year, long income, double age, int owners, int renters, long value, long rent) {
        public int homes() { return owners + renters; }
        public int ownersShare() { return homes() == 0 ? 0 : (int) Math.round(100.0 * owners / homes()); }
    }

    static Path file(LibraryStore store, String tract) { return store.root().resolve("catalog").resolve("home-cache").resolve("census-" + tract + ".json"); }

    /** The figures for an 11-digit tract (state, county, tract), kept a year; null without a key, for a tract of another shape, or when the Bureau did not answer. */
    public static Figures tract(LibraryStore store, String tract) {
        if (!keySet() || tract == null || !tract.matches("\\d{11}")) return null;
        Path f = store == null ? null : file(store, tract);
        try {
            if (f != null && Files.isRegularFile(f)) {
                JsonNode j = M.readTree(f.toFile());
                if (LocalDate.parse(j.path("at").asText("2000-01-01")).plusDays(365).isAfter(LocalDate.now())) return of(j);
            }
        } catch (IOException | RuntimeException stale) { /* read again */ }
        Figures got = null;
        for (int year : YEARS) { got = fetch(year, tract); if (got != null) break; }
        if (got == null || f == null) return got;
        try {
            Files.createDirectories(f.getParent());
            ObjectNode o = M.createObjectNode();
            o.put("at", LocalDate.now().toString()).put("tract", tract).put("year", got.year()).put("income", got.income()).put("age", got.age()).put("owners", got.owners()).put("renters", got.renters()).put("value", got.value()).put("rent", got.rent());
            Files.writeString(f, M.writerWithDefaultPrettyPrinter().writeValueAsString(o));
        } catch (IOException ignored) { }
        return got;
    }

    static Figures of(JsonNode j) { return new Figures(j.path("year").asInt(), j.path("income").asLong(), j.path("age").asDouble(), j.path("owners").asInt(), j.path("renters").asInt(), j.path("value").asLong(), j.path("rent").asLong()); }

    /** One call to the survey of one year, counted in the tally; null when it did not answer or has no such tract that year. */
    static Figures fetch(int year, String tract) {
        String url = "https://api.census.gov/data/" + year + "/acs/acs5?get=B19013_001E,B01002_001E,B25003_002E,B25003_003E,B25077_001E,B25064_001E"
                + "&for=tract:" + tract.substring(5) + "&in=state:" + tract.substring(0, 2) + "%20county:" + tract.substring(2, 5) + "&key=" + KEY_VALUE.get();
        String body = FETCH.apply(url);
        SearchTally.count(SERVICE, 1);
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode a = M.readTree(body);
            if (!a.isArray() || a.size() < 2 || !a.get(1).isArray() || a.get(1).size() < 6) return null;
            JsonNode r = a.get(1);
            double age = r.get(1) == null || r.get(1).isNull() ? 0 : r.get(1).asDouble(0);
            return new Figures(year, figure(r.get(0)), age < 0 ? 0 : age, (int) figure(r.get(2)), (int) figure(r.get(3)), figure(r.get(4)), figure(r.get(5)));
        } catch (IOException | RuntimeException e) { return null; }
    }

    /** A survey figure as a number: the survey's marks for "no estimate" (large negative numbers) and nulls are 0. */
    static long figure(JsonNode n) {
        if (n == null || n.isNull()) return 0;
        long v = Math.round(n.asDouble(0));
        return v < 0 ? 0 : v;
    }

    /** The card's line for the tract: the figures in words, or what is needed to read them. */
    public static String line(String tract, Figures f) {
        if (!keySet()) return "Census tract " + tract + ": the tract's figures (income, age, owners and renters, home value, rent) need a Census key (" + KEY + "), free from " + SIGN_UP + "; not set.";
        if (f == null) return "Census tract " + tract + ": the Census Bureau's survey did not answer for it.";
        List<String> parts = new ArrayList<>();
        if (f.income() > 0) parts.add("median household income $" + String.format(Locale.US, "%,d", f.income()));
        if (f.age() > 0) parts.add("median age " + (f.age() % 1 == 0 ? String.valueOf((int) f.age()) : String.format(Locale.US, "%.1f", f.age())));
        if (f.homes() > 0) parts.add(f.ownersShare() + " % of the homes owned, " + (100 - f.ownersShare()) + " % rented");
        if (f.value() > 0) parts.add("median home value $" + String.format(Locale.US, "%,d", f.value()));
        if (f.rent() > 0) parts.add("median rent $" + String.format(Locale.US, "%,d", f.rent()) + " a month");
        return "Census tract " + tract + " (American Community Survey " + (f.year() - 4) + "-" + f.year() + "): " + (parts.isEmpty() ? "no figures given for it" : String.join("; ", parts)) + ".";
    }
}
