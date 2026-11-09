package org.researchzosho.librarian;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one line a home's card says about its records: which source answered, with its platform and how new its rows are, or that none
 * is known for the place and what the library looked at, or that the county publishes its records only through a lookup page. Full
 * sentences, so a person reading the card knows where the figures came from, or why there are none, without opening a log.
 */
final class HomeRecordsLine {
    private HomeRecordsLine() { }

    static final Map<String, String> STATE = Map.ofEntries(Map.entry("AL", "Alabama"), Map.entry("AK", "Alaska"), Map.entry("AZ", "Arizona"), Map.entry("AR", "Arkansas"), Map.entry("CA", "California"),
            Map.entry("CO", "Colorado"), Map.entry("CT", "Connecticut"), Map.entry("DE", "Delaware"), Map.entry("DC", "the District of Columbia"), Map.entry("FL", "Florida"), Map.entry("GA", "Georgia"),
            Map.entry("HI", "Hawaii"), Map.entry("ID", "Idaho"), Map.entry("IL", "Illinois"), Map.entry("IN", "Indiana"), Map.entry("IA", "Iowa"), Map.entry("KS", "Kansas"), Map.entry("KY", "Kentucky"),
            Map.entry("LA", "Louisiana"), Map.entry("ME", "Maine"), Map.entry("MD", "Maryland"), Map.entry("MA", "Massachusetts"), Map.entry("MI", "Michigan"), Map.entry("MN", "Minnesota"),
            Map.entry("MS", "Mississippi"), Map.entry("MO", "Missouri"), Map.entry("MT", "Montana"), Map.entry("NE", "Nebraska"), Map.entry("NV", "Nevada"), Map.entry("NH", "New Hampshire"),
            Map.entry("NJ", "New Jersey"), Map.entry("NM", "New Mexico"), Map.entry("NY", "New York"), Map.entry("NC", "North Carolina"), Map.entry("ND", "North Dakota"), Map.entry("OH", "Ohio"),
            Map.entry("OK", "Oklahoma"), Map.entry("OR", "Oregon"), Map.entry("PA", "Pennsylvania"), Map.entry("RI", "Rhode Island"), Map.entry("SC", "South Carolina"), Map.entry("SD", "South Dakota"),
            Map.entry("TN", "Tennessee"), Map.entry("TX", "Texas"), Map.entry("UT", "Utah"), Map.entry("VT", "Vermont"), Map.entry("VA", "Virginia"), Map.entry("WA", "Washington"),
            Map.entry("WV", "West Virginia"), Map.entry("WI", "Wisconsin"), Map.entry("WY", "Wyoming"));

    static String stateName(String st) { return STATE.getOrDefault(st == null ? "" : st.toUpperCase(Locale.ROOT), st == null ? "" : st); }

    /** The place as the line names it: the city and its county, or the city and its state when the county is not known. */
    static String place(String city, String state, String county) {
        String c = city == null || city.isBlank() ? "the place" : city;
        return c + ", " + (county == null || county.isBlank() ? stateName(state) : county);
    }

    /** A dataset in the line's words: its name, its platform, and how new its rows are: "newest row 2026", or for an assessor's roll "roll of 2024". */
    static String describe(HomeRecords.Dataset d) { return describe(d, true); }

    static String describe(HomeRecords.Dataset d, boolean sales) {
        if (d == null) return "";
        int y = d.latestYear();
        boolean roll = sales && d.field("price").isEmpty() && !d.field("value").isEmpty();
        return d.name() + ", " + d.platform() + (y > 0 ? (roll ? ", roll of " : ", newest row ") + y : "");
    }

    /** What the library looked at, from the record of its look: only what was asked, in the order it names them. */
    static List<String> lookedAt(List<String> looked, String state) {
        List<String> out = new ArrayList<>();
        boolean socrata = false, arcgis = false, ckan = false, stateLayer = false, web = false;
        for (String l : looked == null ? List.<String>of() : looked) {
            if (l.contains("not asked")) continue;
            if (l.contains("Socrata")) socrata = true;
            if (l.contains("ArcGIS")) arcgis = true;
            if (l.contains("CKAN")) ckan = true;
            if (l.contains("the state's own parcels")) stateLayer = true;
            if (l.contains("web search")) web = true;
        }
        if (socrata) out.add("Socrata");
        if (arcgis) out.add("ArcGIS");
        if (ckan) out.add("CKAN");
        if (stateLayer) out.add(stateName(state) + "'s state layer");
        if (web) out.add("the web for the place's own portal");
        return out;
    }

    static String and(List<String> xs) {
        if (xs.isEmpty()) return "";
        if (xs.size() == 1) return xs.get(0);
        return String.join(", ", xs.subList(0, xs.size() - 1)) + " and " + xs.get(xs.size() - 1);
    }

    /**
     * The line. {@code written} names a reader written for the place ("" when none); {@code sales} and {@code permits} are the datasets
     * the platforms gave (null when none); {@code looked} is the record of the look; {@code lookup} is the address of a lookup page the
     * county publishes its records through, when the research found one and nothing a program reads ("" when none).
     */
    static String line(String city, String state, String county, String written, HomeRecords.Dataset sales, HomeRecords.Dataset permits, List<String> looked, String lookup) { return line(city, state, county, written, sales, permits, looked, lookup, ""); }

    /** The same, with the address of a map file the county publishes ("" when none): said when nothing a program reads answered. */
    static String line(String city, String state, String county, String written, HomeRecords.Dataset sales, HomeRecords.Dataset permits, List<String> looked, String lookup, String mapFile) {
        String where = place(city, state, county);
        if (written != null && !written.isEmpty()) {
            return "Records: " + written + ", read by the library's own reader" + (written.startsWith("WPRDC") ? "; the county's portal has no permits the library reads." : ".");
        }
        if (sales != null && permits != null) return "Records: " + describe(sales) + "; permits from " + describe(permits, false) + ".";
        if (sales != null) return "Records: " + describe(sales) + ". No permits source is known for " + where + ".";
        if (permits != null) return "Records: permits from " + describe(permits, false) + ". No sales or values source is known for " + where + ".";
        if (lookup != null && !lookup.isEmpty()) return "No records source a program can read is known for " + where + "; the county publishes its records through a lookup page at " + lookup + "."
                + (mapFile == null || mapFile.isEmpty() ? "" : " It also publishes a map file the library does not read, at " + mapFile + ".");
        if (mapFile != null && !mapFile.isEmpty()) return "No records source a program can read is known for " + where + "; the county publishes a map file the library does not read, at " + mapFile + ".";
        List<String> at = lookedAt(looked, state);
        return "No records source is known for " + where + "." + (at.isEmpty() ? "" : " The library looked at " + and(at) + ".");
    }
}
