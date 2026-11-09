package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * How many searches and page reads the library made through each service, by month, with the credits the paid ones charged and the
 * dollars a service said a call cost: kept in {@code search-tally.json} in the library's home, so a person can see what a month of
 * research cost before the bill does. Every backend is counted, the free ones with no credits; a failure to write the tally is never a
 * failure of the search.
 *
 * <p>A paid search place has a monthly limit (0.7.0): a setting per service, by default the service's free allowance or what keeps it
 * under a few dollars a month, checked here before each call. A call is reserved before it goes and counted when it was made, so the
 * workers of a run searching at once never go past the limit together; at the limit the place says so in one sentence and is left off
 * the table until the month turns.
 */
public final class SearchTally {
    private SearchTally() { }

    static final ObjectMapper M = new ObjectMapper();
    /** The tally file; a test points it elsewhere. */
    public static volatile Path override = null;

    static Path file() { return override != null ? override : Config.home().resolve("search-tally.json"); }

    /** One more call through {@code service} this month, charged {@code credits} (0 for a free one). */
    public static synchronized void count(String service, int credits) { count(service, credits, 0); }

    /** The same, with the dollars the service said the call cost (0 when it says nothing). */
    public static synchronized void count(String service, int credits, double dollars) {
        try {
            Path f = file();
            ObjectNode all = Files.isRegularFile(f) ? (ObjectNode) M.readTree(f.toFile()) : M.createObjectNode();
            ObjectNode month = all.has(month()) && all.get(month()).isObject() ? (ObjectNode) all.get(month()) : all.putObject(month());
            ObjectNode s = month.has(service) && month.get(service).isObject() ? (ObjectNode) month.get(service) : month.putObject(service);
            s.put("calls", s.path("calls").asInt(0) + 1).put("credits", s.path("credits").asInt(0) + Math.max(0, credits));
            if (dollars > 0) s.put("dollars", Math.round((s.path("dollars").asDouble(0) + dollars) * 1_000_000d) / 1_000_000d);
            Files.createDirectories(f.getParent());
            Files.writeString(f, M.writerWithDefaultPrettyPrinter().writeValueAsString(all), StandardCharsets.UTF_8);
        } catch (Exception ignored) { /* the tally is a convenience; the search stands */ }
    }

    static String month() { return LocalDate.now().toString().substring(0, 7); }

    // ---------------------------------------------------------------- the daily counts

    /** The YouTube Data API's day: its allowance of 10,000 units turns at midnight Pacific time, whatever the library's own clock says. */
    public static String pacificDay() { return LocalDate.now(ZoneId.of("America/Los_Angeles")).toString(); }

    /**
     * One more call through a service whose allowance is daily, charged {@code units} of that day's allowance, under the Pacific day;
     * counted in the month as well, so the month's summary shows it. The last seven days are kept.
     */
    public static synchronized void countToday(String service, int units) {
        count(service, units, 0);
        try {
            Path f = file();
            ObjectNode all = Files.isRegularFile(f) ? (ObjectNode) M.readTree(f.toFile()) : M.createObjectNode();
            ObjectNode days = all.has("days") && all.get("days").isObject() ? (ObjectNode) all.get("days") : all.putObject("days");
            String today = pacificDay();
            ObjectNode day = days.has(today) && days.get(today).isObject() ? (ObjectNode) days.get(today) : days.putObject(today);
            ObjectNode s = day.has(service) && day.get(service).isObject() ? (ObjectNode) day.get(service) : day.putObject(service);
            s.put("calls", s.path("calls").asInt(0) + 1).put("units", s.path("units").asInt(0) + Math.max(0, units));
            List<String> old = new ArrayList<>();
            days.fieldNames().forEachRemaining(d -> { if (d.compareTo(LocalDate.parse(today).minusDays(7).toString()) < 0) old.add(d); });
            for (String d : old) days.remove(d);
            Files.createDirectories(f.getParent());
            Files.writeString(f, M.writerWithDefaultPrettyPrinter().writeValueAsString(all), StandardCharsets.UTF_8);
        } catch (Exception ignored) { /* the tally is a convenience; the call stands */ }
    }

    /** The units a daily-allowance service has been charged today (the Pacific day), as kept. */
    public static synchronized int usedToday(String service) {
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) return 0;
            return M.readTree(f.toFile()).path("days").path(pacificDay()).path(service).path("units").asInt(0);
        } catch (Exception e) { return 0; }
    }

    /** This month's calls per service, as kept. */
    public static Map<String, int[]> thisMonth() {
        Map<String, int[]> out = new TreeMap<>();
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) return out;
            JsonNode month = M.readTree(f.toFile()).path(month());
            month.properties().forEach(e -> out.put(e.getKey(), new int[]{e.getValue().path("calls").asInt(0), e.getValue().path("credits").asInt(0)}));
        } catch (Exception ignored) { }
        return out;
    }

    /** The dollars a service said this month's calls cost, as kept; 0 for a service that does not say. */
    public static double dollarsThisMonth(String service) {
        try {
            Path f = file();
            if (!Files.isRegularFile(f)) return 0;
            return M.readTree(f.toFile()).path(month()).path(service).path("dollars").asDouble(0);
        } catch (Exception e) { return 0; }
    }

    // ---------------------------------------------------------------- the monthly limits

    /** A service the library stops at a number of calls a month: the name it is counted under, the setting that holds the number, and the number when the setting is unset (-1: no limit unless one is set). */
    public record Limit(String service, String setting, int byDefault) {
        /** The setting's name as a person types it: exa_monthly_limit. */
        public String shortName() { return setting.replaceFirst("^RESEARCHZOSHO_", "").toLowerCase(Locale.ROOT); }
    }

    /**
     * Exa: 714 searches keep it under $5 a month at $7 per 1,000, the price of a search with up to ten results and their passages as read
     * on Exa's pricing page on 2026-11-04. Tavily: 1,000, its free monthly credits as read on Tavily's pricing page the same day, one a
     * search. The keeper's own search service, the Google keys (Serper, SerpApi), Brave and Firecrawl: none, unless one is set, so a
     * library whose keeper set nothing searches as it did before the limits.
     */
    public static final List<Limit> LIMITS = List.of(
            new Limit(ExaSearchTool.TALLY, ExaSearchTool.LIMIT, 714),
            new Limit(TavilySearchTool.TALLY, TavilySearchTool.LIMIT, 1000),
            new Limit(CustomSearchTool.TALLY, CustomSearchTool.LIMIT, -1),
            new Limit(WebSearchTool.SERPER, WebSearchTool.SERPER_LIMIT, -1),
            new Limit(WebSearchTool.SERPAPI, WebSearchTool.SERPAPI_LIMIT, -1),
            new Limit(WebSearchTool.BRAVE, WebSearchTool.BRAVE_LIMIT, -1),
            new Limit(Firecrawl.TALLY, Firecrawl.LIMIT, -1));

    public static Limit limitOf(String service) {
        for (Limit l : LIMITS) if (l.service().equals(service)) return l;
        return null;
    }

    /** The limit in force for a service this month: the setting when it holds a number, else the default; -1 when there is none. */
    public static int limit(String service) {
        Limit l = limitOf(service);
        if (l == null) return -1;
        String v = Config.get(l.setting());
        if (v != null && v.strip().matches("\\d{1,9}")) return Integer.parseInt(v.strip());
        return l.byDefault();
    }

    /** Calls reserved and not yet settled, by service. */
    private static final Map<String, Integer> UNDER_WAY = new HashMap<>();

    /** The calls made this month through a service, and those under way. */
    public static synchronized int used(String service) {
        return thisMonth().getOrDefault(service, new int[]{0, 0})[0] + UNDER_WAY.getOrDefault(service, 0);
    }

    // ---------------------------------------------------------------- a run that asked for no paid service

    /**
     * Whether the work on this thread, and on the threads it starts, is a run the person asked to use no paid service (the chat's "use
     * no paid services for this run", the research call's {@code paid: false}, the command's {@code --no-paid}): every service that has a
     * monthly limit is then left off at this gate, as it is at its month's limit, and says so in its own sentence. The run sets it on its
     * own thread when it starts and clears it when it ends; a thread the run starts inherits it. The pools that outlive a run (the peers',
     * the webhooks', the MCP readers') never reach this gate.
     */
    private static final InheritableThreadLocal<Boolean> PAID_OFF = new InheritableThreadLocal<>();

    /** This thread's work, and the threads it starts from now, use no paid service ({@code off}); or do again. */
    public static void paidOff(boolean off) { if (off) PAID_OFF.set(Boolean.TRUE); else PAID_OFF.remove(); }

    /** Whether this thread's work was asked to use no paid service. */
    public static boolean paidOff() { return Boolean.TRUE.equals(PAID_OFF.get()); }

    /** Whether a service with a monthly limit is left off for this thread's run, because the run asked for no paid service. */
    private static boolean offForThisRun(String service) { return paidOff() && limitOf(service) != null; }

    /** Whether this month's calls through a service have reached its limit, or the run on this thread asked for no paid service. */
    public static boolean reached(String service) {
        if (offForThisRun(service)) return true;
        int l = limit(service);
        return l >= 0 && used(service) >= l;
    }

    /** One call through a service may go: true, held until {@link #settle}; false when this month's limit is reached, or the run on this thread asked for no paid service. */
    public static synchronized boolean reserve(String service) {
        if (offForThisRun(service)) return false;
        int l = limit(service);
        if (l >= 0 && used(service) >= l) return false;
        UNDER_WAY.merge(service, 1, Integer::sum);
        return true;
    }

    /** The reserved call is over: counted when it was made (the service answered and charged), with its credits and dollars. */
    public static synchronized void settle(String service, boolean made, int credits, double dollars) {
        UNDER_WAY.computeIfPresent(service, (k, v) -> v <= 1 ? null : v - 1);
        if (made) count(service, credits, dollars);
    }

    /** What a place says, in one sentence, when this month's calls through its service are used: searches, or for Firecrawl pages; or that this run asked for no paid service. */
    public static String usedUp(String service, String place) {
        if (offForThisRun(service)) return "This run asked for no paid service, so " + place + " is left off for it.";
        Limit l = limitOf(service);
        int n = limit(service);
        String what = service.equals(Firecrawl.TALLY) ? (n == 1 ? " page" : " pages") : (n == 1 ? " search" : " searches");
        return "This month's " + n + what + " through " + place + (n == 1 ? " is used" : " are used") + ", so " + place + " is left off until the month turns"
                + (l == null ? "." : "; the library's keeper can raise " + l.shortName() + " in the settings.");
    }

    /** The month's tally in sentences: "This month: 123 searches through Serper, 123 credits; 40 through Brave; 12 pages through Firecrawl, 12 credits." */
    public static String summary() {
        Map<String, int[]> m = thisMonth();
        if (m.isEmpty()) return "Nothing counted this month yet: the tally in " + file() + " counts every search and every page read through a service, with the credits the paid ones charge.";
        List<String> parts = new ArrayList<>();
        for (var e : m.entrySet()) {
            int calls = e.getValue()[0], credits = e.getValue()[1];
            boolean pages = e.getKey().equals("Firecrawl"), listings = e.getKey().equals("RentCast"), reads = e.getKey().equals(PubMed.TALLY), mcp = e.getKey().startsWith("MCP ");
            if (mcp) { parts.add(calls + (calls == 1 ? " call" : " calls") + " to the MCP server " + e.getKey().substring(4)); continue; }   // a keeper's MCP server: every tools/call counts
            double dollars = dollarsThisMonth(e.getKey());
            int limit = limit(e.getKey());
            parts.add(calls + (pages ? (calls == 1 ? " page" : " pages") : listings ? (calls == 1 ? " call for listings" : " calls for listings")
                    : reads ? (calls == 1 ? " search or article" : " searches and articles") : (calls == 1 ? " search" : " searches"))
                    + " through " + e.getKey()
                    + (listings ? " (50 a month are free)" : "") + (limit >= 0 ? " (the library stops at " + limit + " a month)" : "")
                    + (credits > 0 ? ", " + credits + (credits == 1 ? " credit" : " credits") : "") + (dollars > 0 ? ", " + money(dollars) : ""));
        }
        return "This month, " + month() + ": " + String.join("; ", parts) + ". The tally is in " + file() + ".";
    }

    /** Dollars as a person reads them: "$0.014" under a dollar, "$3.50" over. */
    public static String money(double d) { return d < 0.995 ? String.format(Locale.US, "$%.3f", d) : String.format(Locale.US, "$%.2f", d); }
}
