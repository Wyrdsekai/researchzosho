package org.researchzosho.librarian;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What an account may spend (0.8.0, stage 3): the service's caps and each account's own, kept in the store. An account's own cap
 * outranks the service's, and the service's outranks the default. A cap of -1 is no cap. Dollars are whole millionths.
 */
final class Caps {
    private Caps() { }

    /** A month's dollars of the service's model (the person's own key's work is not counted against it). */
    static final String MONTH_DOLLARS = "month_dollars";
    /** A month's searches through the service's paid search places. */
    static final String SEARCHES = "searches";
    /** A month's pages fetched. */
    static final String FETCHES = "fetches";
    /** Jobs (research runs, translations and the like) going at once. */
    static final String JOBS_AT_ONCE = "jobs_at_once";
    /** One job's minutes, and its dollars. */
    static final String JOB_MINUTES = "job_minutes", JOB_DOLLARS = "job_dollars";
    /** The library's folder, in megabytes. */
    static final String STORAGE_MB = "storage_mb";
    /** How many libraries an account keeps, those it makes itself and those made for it (stage 4); it holds with a spending plug-in too. */
    static final String LIBRARIES = "libraries_per_account";

    /** The defaults, for a service open to the public: the operator changes each for the service or for one account. */
    static final Map<String, Long> DEFAULTS = defaults();

    private static Map<String, Long> defaults() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put(MONTH_DOLLARS, 5_000_000L);
        m.put(SEARCHES, 300L);
        m.put(FETCHES, 3_000L);
        m.put(JOBS_AT_ONCE, 1L);
        m.put(JOB_MINUTES, 30L);
        m.put(JOB_DOLLARS, 1_500_000L);
        m.put(STORAGE_MB, 2_048L);
        m.put(LIBRARIES, 3L);
        return m;
    }

    /** Whether a name is a cap's. */
    static boolean known(String name) { return DEFAULTS.containsKey(name); }

    /** An account's caps as they stand: its own, else the service's, else the default. */
    static Map<String, Long> of(AccountStore store, String account) throws IOException {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String name : DEFAULTS.keySet()) {
            Long own = account == null ? null : store.cap(account, name), service = store.cap("*", name);
            out.put(name, own != null ? own : service != null ? service : DEFAULTS.get(name));
        }
        return out;
    }

    /** A cap's value as a person writes it: dollars as $5.00, minutes and counts as numbers, -1 as "no cap". */
    static String said(String name, long value) {
        if (value < 0) return "no cap";
        return name.equals(MONTH_DOLLARS) || name.equals(JOB_DOLLARS) ? dollars(value) : String.valueOf(value);
    }

    /** A value written by a person: "off" for no cap, "$5" or "5.00" for dollars, a whole number otherwise. Throws when it is not one. */
    static long parse(String name, String written) {
        String w = written.strip().toLowerCase(Locale.ROOT);
        if (w.equals("off") || w.equals("none") || w.equals("-1")) return -1;
        if (name.equals(MONTH_DOLLARS) || name.equals(JOB_DOLLARS)) {
            double d = Double.parseDouble((w.startsWith("$") ? w.substring(1) : w).replace(',', '.'));   // 5.00 or 5,00, as people write a decimal
            if (d < 0) throw new NumberFormatException(written);
            return Math.round(d * 1_000_000);
        }
        long n = Long.parseLong(w);
        if (n < 0) throw new NumberFormatException(written);
        return n;
    }

    /** A size in megabytes as a person reads it: 2.0 GB, 750 MB. */
    static String size(long mb) { return mb >= 1024 ? String.format(Locale.ROOT, "%.1f GB", mb / 1024.0) : mb + " MB"; }

    static String dollars(long micros) { return String.format(Locale.ROOT, "$%.2f", micros / 1_000_000.0); }
}
