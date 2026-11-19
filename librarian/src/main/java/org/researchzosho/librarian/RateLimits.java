package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import org.researchzosho.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How often an address or an account may ask the account service (0.8.0, stage 4). The rare acts are counted in the store, one row an
 * act, so every process of the service reads the same count: failed sign-ins, sign-ups and join links opened, by address; new accounts
 * made by letting a new name into a library, and changes to a password or an own key, by account. The frequent ones are counted in each
 * process's memory, a bucket a key that fills again at the limit's pace, each process holding its share: the limit divided among the
 * processes that wrote themselves down in the store in the last minute. Requests with nothing that says who they are count by address;
 * a program's (a key) and a browser's (a session) by account. Behind a proxy named in {@code trusted_proxy} the address is the one it
 * forwards; an IPv6 address counts by its first 64 bits, the network one household or one machine is given.
 *
 * <p>Each limit is a setting, {@code limit.<name>}: "120 per minute", "10 per 10 minutes", "120 per minute, burst 30", or {@code off}.
 * A request over one is answered 429 with Retry-After and one sentence that says what was reached and when to ask again. The single-person
 * program has none of these.
 */
final class RateLimits {
    private static final Logger LOG = LoggerFactory.getLogger(RateLimits.class);

    static final String PREFIX = "RESEARCHZOSHO_LIMIT_";

    /** A limit: its name (the setting {@code limit.<name>}), whether the store counts it, and what it is unless set. */
    enum Limit {
        SIGNIN("signin", true, "10 per 10 minutes"),
        SIGNUP("signup", true, "5 per hour"),
        JOIN("join", true, "20 per hour"),
        CHANGES("changes", true, "20 per hour"),
        ADDRESS("address", false, "120 per minute"),
        API("api", false, "120 per minute, burst 30"),
        PAGES("pages", false, "300 per minute");

        final String word, standard;
        final boolean stored;
        Limit(String word, boolean stored, String standard) { this.word = word; this.stored = stored; this.standard = standard; }
        String setting() { return PREFIX + word.toUpperCase(Locale.ROOT); }
    }

    /** A limit's rule: {@code n} in each {@code periodMs}, and up to {@code burst} at once ({@code n} unless said). */
    record Rule(long n, long periodMs, long burst) { double perMs() { return (double) n / periodMs; } }

    private static final Pattern RULE = Pattern.compile("(?i)^(\\d+)\\s+per\\s+(?:(\\d+)\\s+)?(second|minute|hour|day)s?(?:\\s*,\\s*burst\\s+(\\d+))?$");

    /** A rule as written, or null when it cannot be read. {@code off} is not a rule: see {@link #rule}. */
    static Rule parse(String v) {
        if (v == null) return null;
        Matcher m = RULE.matcher(v.strip());
        if (!m.matches()) return null;
        long n = Long.parseLong(m.group(1)), k = m.group(2) == null ? 1 : Long.parseLong(m.group(2));
        long unit = switch (m.group(3).toLowerCase(Locale.ROOT)) { case "second" -> 1_000L; case "minute" -> 60_000L; case "hour" -> 3_600_000L; default -> 86_400_000L; };
        long burst = m.group(4) == null ? n : Long.parseLong(m.group(4));
        if (n < 1 || k < 1 || burst < 1) return null;
        return new Rule(n, k * unit, burst);
    }

    /** Why a value cannot be a limit's, in a sentence; "" when it can. */
    static String refuse(String shortName, String v) {
        String s = v == null ? "" : v.strip();
        return s.isEmpty() || s.equalsIgnoreCase("off") || parse(s) != null ? "" : shortName + " is a number per second, minute, hour or day, with a burst if wanted, or off: for example 120 per minute, burst 30.";
    }

    private static final Map<String, Optional<Rule>> READ = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> WARNED = new ConcurrentHashMap<>();

    /** A limit's rule now, from its setting, read live; null when it is off. A value that cannot be read is said once, and the default holds. */
    static Rule rule(Limit l) {
        String v = Config.live(l.setting());
        String s = v == null || v.isBlank() ? l.standard : v.strip();
        if (s.equalsIgnoreCase("off")) return null;
        Optional<Rule> r = READ.computeIfAbsent(s, k -> Optional.ofNullable(parse(k)));
        if (r.isPresent()) return r.get();
        if (WARNED.putIfAbsent(l.setting() + "=" + s, Boolean.TRUE) == null) LOG.warn("The setting limit.{} reads \"{}\", which is not a limit; {} holds.", l.word, s, l.standard);
        return parse(l.standard);
    }

    // ---- a refusal and what it says ----

    /** A request a limit refused: which limit, whether it was an account's or an address's, and the seconds until it lets go. */
    record Refused(Limit limit, boolean account, long seconds) {
        /** The sentence, in the language of the request being answered. */
        String said() { return RateLimits.said(limit, account, seconds); }
    }

    /** The sentence a refused request is told: what was reached, and when to ask again. */
    static String said(Limit l, boolean account, long seconds) {
        String in = in(seconds);
        return switch (l) {
            case SIGNIN -> Pages.t("Too many failed sign-ins from this address. Try again in {0}.", in);
            case SIGNUP -> account ? Pages.t("Too many people invited by this account just now. Try again in {0}.", in) : Pages.t("Too many new accounts from this address. Try again in {0}.", in);
            case JOIN -> Pages.t("Too many join links opened from this address. Try again in {0}.", in);
            case CHANGES -> Pages.t("Too many changes to this account's password and keys. Try again in {0}.", in);
            case ADDRESS -> Pages.t("Too many requests from this address. Try again in {0}.", in);
            case API, PAGES -> Pages.t("Too many requests from this account. Try again in {0}.", in);
        };
    }

    /** A wait in words: seconds under two minutes, minutes under two hours, hours beyond. */
    static String in(long seconds) {
        long s = Math.max(1, seconds);
        if (s < 120) return Pages.n(s, "{0} second", "{0} seconds");
        long minutes = (s + 59) / 60;
        if (minutes < 120) return Pages.n(minutes, "{0} minute", "{0} minutes");
        return Pages.n((minutes + 59) / 60, "{0} hour", "{0} hours");
    }

    /** The answer's header that says when to ask again. */
    static void retryAfter(HttpExchange x, Refused r) { x.getResponseHeaders().set("Retry-After", String.valueOf(r.seconds())); }

    private static Refused refused(Limit l, String key, long waitMs) { return new Refused(l, key.startsWith(ACCOUNT), Math.max(1, (waitMs + 999) / 1000)); }

    // ---- the keys ----

    /** A key's kinds: an address's, an account's. */
    static final String ADDRESSED = "addr|", ACCOUNT = "acct|";

    static String ofAccount(String id) { return ACCOUNT + id; }
    static String ofAddress(HttpExchange x) { return ADDRESSED + address(x); }

    /**
     * The address a request counts against: behind a trusted proxy the one it forwards (a trusted proxy that forwards none counts as the
     * proxy itself), else the connection's; an IPv6 address by its first 64 bits.
     */
    static String address(HttpExchange x) {
        InetAddress a = Proxy.client(x);
        if (a == null) { InetSocketAddress r = x.getRemoteAddress(); a = r == null ? null : r.getAddress(); }
        return a == null ? "unknown" : counted(a);
    }

    static String counted(InetAddress a) {
        if (!(a instanceof Inet6Address)) return a.getHostAddress();
        byte[] b = Arrays.copyOf(Arrays.copyOf(a.getAddress(), 8), 16);
        try { return InetAddress.getByAddress(b).getHostAddress() + "/64"; } catch (UnknownHostException e) { return a.getHostAddress(); }
    }

    // ---- one process's counting ----

    final AccountStore store;
    /** This process, as it writes itself down among the service's. */
    final String id = "p-" + UUID.randomUUID();
    /** How often a process writes itself down and counts the others; tests shorten it. */
    static volatile long SEEN_MS = 20_000L;
    /** How long a process counts among the service's after it last wrote itself down. */
    static final long PRESENT_MS = 60_000L;

    private volatile int processes = 1;
    private volatile long seenAt;
    private volatile long prunedAt;

    RateLimits(AccountStore store) { this.store = store; seen(System.currentTimeMillis()); }

    /** This process stopped: it is taken out of the count at once, so the others hold the whole limit. */
    void stop() { try { store.processGone(id); } catch (IOException e) { /* forgotten within a minute */ } }

    /** How many processes of the service share the frequent limits now: this one and those that wrote themselves down in the last minute. */
    int processes() {
        long now = System.currentTimeMillis();
        if (now - seenAt >= SEEN_MS) seen(now);
        return Math.max(1, processes);
    }

    private synchronized void seen(long now) {
        if (seenAt != 0 && now - seenAt < SEEN_MS) return;
        seenAt = now;
        try { store.processSeen(id, now); processes = Math.max(1, store.processesSince(now - PRESENT_MS)); }
        catch (IOException e) { LOG.warn("The service's processes could not be counted; each keeps its last share: {}", e.getMessage()); }
    }

    /**
     * One request against a limit: null when it may go on, counted; the refusal when the limit is reached, not counted. A limit that is
     * off lets everything through; a store that cannot count lets the request through and says so in the log.
     */
    Refused take(Limit l, String key) {
        if (l.stored) { Refused f = full(l, key); if (f == null) mark(l, key); return f; }
        Rule r = rule(l);
        if (r == null) return null;
        int k = processes();
        double cap = Math.max(1.0, Math.ceil((double) r.burst() / k)), rate = r.perMs() / k;
        long now = System.currentTimeMillis();
        sweep(now);
        Bucket b = buckets.computeIfAbsent(l.word + "|" + key, x -> new Bucket(cap, now));
        synchronized (b) {
            b.tokens = Math.min(cap, b.tokens + (now - b.at) * rate);
            b.at = now;
            if (b.tokens >= 1.0) { b.tokens -= 1.0; return null; }
            return refused(l, key, (long) Math.ceil((1.0 - b.tokens) / rate));
        }
    }

    /** Whether a limit the store counts is reached for a key: the refusal, else null. Counts nothing. */
    Refused full(Limit l, String key) {
        Rule r = rule(l);
        if (r == null) return null;
        long now = System.currentTimeMillis();
        try {
            List<Long> at = store.marks(l.word + "|" + key, now - r.periodMs());
            if (at.size() < r.n()) return null;
            // the one that has to age out before one more fits; with more than n (two processes counted together) a later one
            return refused(l, key, at.get((int) (at.size() - r.n())) + r.periodMs() - now);
        } catch (IOException e) { LOG.warn("The limit {} could not be read; the request goes on: {}", l.word, e.getMessage()); return null; }
    }

    /** A rare act counted against a key now. The counts older than a day are removed once an hour. */
    void mark(Limit l, String key) {
        if (rule(l) == null) return;
        long now = System.currentTimeMillis();
        try {
            store.mark(l.word + "|" + key);
            if (now - prunedAt >= 3_600_000L) { prunedAt = now; store.marksPrune(now - longest()); }
        } catch (IOException e) { LOG.warn("The limit {} could not count: {}", l.word, e.getMessage()); }
    }

    /** The longest window of the limits the store counts, and a day at least: older counts hold nothing back. */
    private static long longest() {
        long w = 86_400_000L;
        for (Limit l : Limit.values()) if (l.stored) { Rule r = rule(l); if (r != null) w = Math.max(w, r.periodMs()); }
        return w;
    }

    private static final class Bucket { double tokens; long at; Bucket(double tokens, long at) { this.tokens = tokens; this.at = at; } }
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private volatile long sweptAt;

    /** The buckets that are full again forgotten, at most every ten seconds once there are many: a full one is the same as none. */
    private void sweep(long now) {
        if (buckets.size() < 10_000 || now - sweptAt < 10_000L) return;
        sweptAt = now;
        buckets.entrySet().removeIf(e -> {
            Limit l = Arrays.stream(Limit.values()).filter(x -> e.getKey().startsWith(x.word + "|")).findFirst().orElse(null);
            Rule r = l == null ? null : rule(l);
            if (r == null) return true;
            int k = Math.max(1, processes);
            Bucket b = e.getValue();
            synchronized (b) { return b.tokens + (now - b.at) * (r.perMs() / k) >= Math.ceil((double) r.burst() / k); }
        });
    }

    // ---- the audit log ----

    private final Map<String, Long> notedMinute = new ConcurrentHashMap<>();

    /**
     * A limit an account reached, in the audit log at most once a minute an account, whatever limit and whichever process: the entry's id
     * is the account's and the minute's, so a second process's entry for the same minute is not written.
     */
    void noted(AccountStore.Account a, AccountActs.By by, Limit l) {
        if (a == null) return;
        long minute = System.currentTimeMillis() / 60_000L;
        Long before = notedMinute.put(a.id(), minute);
        if (before != null && before == minute) return;
        ObjectNode d = AccountActs.detail();
        d.put("limit", l.word);
        try { store.auditOnce("lim-" + a.id() + "-" + minute, by.actor(), by.via(), "limit_reached", a.id(), "", by.address(), d.toString()); }
        catch (IOException e) { LOG.warn("The audit log could not be written (limit_reached {}): {}", a.id(), e.getMessage()); }
    }

    /** A refusal carried as an exception through code that answers with a sentence: a sign-up through the provider, refused. */
    static final class Refusal extends IOException {
        final Refused refused;
        Refusal(Refused r) { super(r.said()); this.refused = r; }
    }
}
