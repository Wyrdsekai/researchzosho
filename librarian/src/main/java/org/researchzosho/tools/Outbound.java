package org.researchzosho.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.researchzosho.Config;
import org.researchzosho.librarian.GivenAddresses;
import org.researchzosho.librarian.GivenIdentifiers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one place every request of this program passes (0.8.1): every {@link HttpClient} it builds is made through {@link #client}, and
 * a test reads the sources to see that none is built another way.
 *
 * <p>While a model's tool call is carried out ({@link #call}), a request is refused unless it is one of these ({@link #check}):
 * <ul>
 * <li>(a) an address that was given, as it was given ({@link GivenAddresses}: the person's words, a result from outside, a source the
 *     library recorded when it fetched it): the request is made in the spelling and with the scheme the address was given in, whichever
 *     tool built it and however the call wrote it ({@code Checked}); and another page of a given host that the tool itself asks for and
 *     the call's words do not spell out (its robots.txt, the PDF of an abstract's page);</li>
 * <li>(b) a host this program is written for (a host that stands in its own sources, {@code fixed-hosts.txt}) or one of the owner's
 *     (an address in their settings, a server they added): the model's words go there as words, in the places that service takes
 *     words, and never as an address the words spell out: a request to such a host whose path the call's own words hold is made only
 *     when the address was given;</li>
 *     this machine itself (the model server, the search service and the helpers this program starts answer on its own ports, set or
 *     by default) and a machine the owner's settings name are theirs too, at whatever port, when the call's words do not name them: a
 *     request to one of them leaves nothing, and which port is asked is the tool's own code's choice;</li>
 * <li>(c) where a server sent an allowed request on, decided by who chose the way ({@link Way}). A request of kind (a) carries
 *     nothing of the model's, so where its server sends it is the server's choice, as a link on its page would be: it is followed
 *     anywhere (the fetcher's own refusals aside), and the address it leads to is one that came from outside. A request of kind (b)
 *     may carry the call's words (a search's words, a name, a number), and a host that sends requests on by what they hold would let
 *     those words choose where a request ends: such a request is followed only to a host of kind (b), to its own site
 *     ({@link #site}), or to a given address. A resolver ({@link #RESOLVERS}) is followed wherever it sends a request for a name that
 *     was given. The guard follows a redirect itself ({@code Checked}), and a fetcher that follows its own tells the guard
 *     ({@link #sentOnTo}).</li>
 * </ul>
 * A tool that must ask hosts it finds while it runs is declared by name in {@link #FINDS}, with what holds it written beside it; a test
 * lists the declared tools, so that adding one is a visible change.
 *
 * <p>The check is made on the request as it leaves, so it does not depend on which argument a value came in, on how it was written (a
 * bare host, a quote after it, a list with spaces, a language code that is a host) or on what the tool did with it on the way. On
 * 2026-11-20 a language code given to the archive search became the host of its request, a feed's address with a quote after it was
 * fetched unjudged, and a street address's state became part of a host: each check had looked at the shape of one argument, and each
 * tool built its request afterwards. Until that day the rule here refused only a host the call's words named; a run of every tool
 * against the real services (186 requests) showed that all but one tool ask nothing but (a) and (b), and the rule became this one.
 *
 * <p>The check runs on the thread that carries out the call. The argument check ({@link GivenAddresses#unopened}) stays as the early
 * refusal a model reads; this is what holds when that one did not look. Every refusal is logged and kept with the call
 * ({@link Call#refusals}), and the run or the chat turn writes it into its trace with the tool, the host and the rule.
 */
public final class Outbound {
    private Outbound() { }

    private static final Logger log = LoggerFactory.getLogger(Outbound.class);

    /** A request this program did not send while a model's call was carried out, with the rule that refused it. */
    public static final class NotOpened extends IOException {
        private final String host, rule;
        NotOpened(String host, String rule, String why) {
            super("NOT OPENED: " + why);
            this.host = host; this.rule = rule;
        }
        public String host() { return host; }
        /** The rule's short name, for the trace: {@link #NAMED}, {@link #SPELLED}, {@link #NOT_LISTED} or {@link #SENT_ON}. */
        public String rule() { return rule; }
    }

    /** One refusal, for a trace: the tool whose call it was, the host that was not asked, the rule's name. */
    public record Refusal(String tool, String host, String rule) { }

    /** The rules' names in a trace: a host the call's words name; an address on a listed host that the call's words spell out; a host that is neither listed nor the owner's; a request that may carry the call's words, sent on to another site. */
    public static final String NAMED = "a host the call named", SPELLED = "an address the call spelled out", NOT_LISTED = "not a listed host", SENT_ON = "sent on to another site";

    /**
     * How a request of a call was let through, which decides where it may be sent on. {@code GIVEN}: it carries nothing of the
     * model's (a given address as it was given, a tool's own page of a given host, a host a declared tool found, where such a request
     * was sent on): where its server sends it is the server's choice. {@code WORDS}: it may carry the call's words (a host of the
     * program's list or the owner's, asked as that service is asked): it stays on such hosts and on its own site.
     */
    enum Way { GIVEN, WORDS }

    /** A request that was let through: how, to which host, and whether it asked a resolver for a name that may be followed anywhere. */
    private record Sent(Way way, String host, boolean resolved) { }

    /**
     * The hosts that exist to send a request on: a resolver of names. A request to one of them for a name and nothing else is followed
     * wherever the resolver sends it, and what it leads to may then be read, when the name was given (the person's words, a text from
     * outside) or the call's own words do not hold it (the tool read it in the person's file or in an answer). Any other request to
     * such a host is followed as a request of kind (b) is. The list is short on purpose and a test names it ({@code OutboundTest}).
     *
     * <p>{@code doi.org}: a work by its DOI, {@code https://doi.org/10.…/…}, with no query (the resolver adds a value of the query
     * to where it sends).
     */
    public static final Map<String, String> RESOLVERS = Map.of("doi.org", "a work by its DOI, no query: followed when the DOI was given or the call's own words do not hold it");

    /**
     * The tools that must ask hosts they find while they run, each with what holds it. Every other tool asks only a given address, a
     * host of the program's list or the owner's. A tool is added here by name, with its constraints, and the test that lists them
     * ({@code OutboundTest}) fails until the list there is changed too.
     *
     * <p>{@code records}: a street address looked up in the public records of its place. No list names every county's data portal, so
     * the lookup finds them: (1) the hosts it asks come only from the answers of its own web search and of the catalogues it reads
     * (Socrata's, ArcGIS Hub's, a portal's own), which the lookup hands to {@link #found} as it reads them; a host the call's words
     * name is never one of them, and a host made from the city's letters is asked only when such an answer named it; (2) what it asks
     * of such a host is a path the lookup's own code builds, never one the call's words spell out; (3) the words that go to such a
     * host are the street as the lookup itself parsed it, and every word and number of it has to come from the person's words or from
     * text that came from outside ({@code GivenWords}), which is checked before the lookup starts.
     *
     * <p>{@code board}: an employer's openings, read from its careers board. Most boards stand on a few services this program is
     * written for; which one an employer uses, and a board on the employer's own site, are found by a web search for the employer's
     * careers page and by reading that page (in the run of 2026-11-20 with the rule on, the board of Mozilla was found through
     * www.mozilla.org and careers.mozilla.vc, and those seven requests were what the rule refused). (1) The hosts it asks come only
     * from its own search's answers and from the pages it reads there, handed to {@link #found} as they are read; (2) what it asks of
     * such a host is an address one of those answers named, or the host's robots.txt or sitemap.xml; (3) no word of the call goes
     * to such a host: the employer's name goes to the search service and to the board services this program is written for, and the
     * words and towns of the call cut the openings after they are read.
     */
    public static final Map<String, String> FINDS = Map.of(
            "records", "the hosts its own search's and catalogues' answers name; paths its own code builds; a street the person or an outside text gave",
            "board", "the hosts its own search's answers and the pages it reads there name; the addresses those name, and robots.txt and sitemap.xml; no word of the call");

    /** An answer a declared tool ({@link #FINDS}) read while it runs: the hosts it names may be asked by this call. Nothing for any other tool. */
    public static void found(String answer) {
        Call c = CURRENT.get();
        if (c == null || answer == null || !FINDS.containsKey(c.tool)) return;
        String text = answer.length() > 2_000_000 ? answer.substring(0, 2_000_000) : answer;
        dotted(text.toLowerCase(Locale.ROOT).replace("\\/", "/"), c.found::add);
    }

    /** Whether a declared tool's call may ask this host: one of its own answers named it, and the call's words do not. */
    public static boolean wasFound(String host) {
        Call c = CURRENT.get();
        if (c == null) return true;   // no model's call: the program's own work
        String h = host(host);
        return (c.found.contains(h) || fixed(h, -1)) && !c.names(h);
    }

    private static final Set<String> STAND_INS = ConcurrentHashMap.newKeySet();
    /**
     * The hosts the owner chose that stand in no setting ({@link #owners}), each with when it was last said: a host is the owner's
     * while it is in use and for {@link #OWNERS_FOR} after its last use, and then no longer. Every user of such a host says it
     * again at each use (a client of the model server at each request, a connection to a keeper's server each time it is handed
     * out), so a host in use never lapses; a model server the owner stopped using does (until the second reading of 0.8.1 a host
     * once said stayed the owner's for as long as the program ran).
     */
    private static final Map<String, Long> OWNERS = new ConcurrentHashMap<>();
    static final long OWNERS_FOR = 12 * 60 * 60 * 1000L;
    /** The time, in milliseconds; a test stands in a clock of its own. */
    static volatile LongSupplier CLOCK = System::currentTimeMillis;

    private static boolean owner(String hostAndPort) {
        Long said = OWNERS.get(hostAndPort);
        if (said == null) return false;
        if (CLOCK.getAsLong() - said <= OWNERS_FOR) return true;
        OWNERS.remove(hostAndPort, said);
        return false;
    }

    /** The hosts that are the owner's now by having been said ({@link #owners}), each with its port when it was said with one. */
    static Set<String> ownersNow() { Set<String> out = new TreeSet<>(); for (String o : OWNERS.keySet()) if (owner(o)) out.add(o); return out; }

    /** This machine itself: localhost, 127.x.x.x, ::1. */
    public static boolean isLoopback(String host) {
        String h = host(host);
        return h.equals("localhost") || h.endsWith(".localhost") || h.matches("127(\\.\\d{1,3}){3}") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1");
    }

    /** This machine, or a machine the owner's settings or records name, whatever the port. */
    public static boolean ownMachine(String host) {
        String h = host(host);
        if (isLoopback(h)) return true;
        for (String o : OWNERS.keySet()) if ((o.equals(h) || o.startsWith(h + ":")) && owner(o)) return true;
        for (String v : ownersValues()) { Matcher m = IN_SETTINGS.matcher(v); while (m.find()) if (host(m.group(1)).equals(h)) return true; }
        return false;
    }

    /** Every value of the owner's settings, the environment's among them, and the search service's address. */
    private static List<String> ownersValues() {
        List<String> values = new ArrayList<>();
        try { values.add(WebSearchTool.endpoint()); } catch (RuntimeException e) { /* none */ }
        try { values.addAll(Config.storedWithPrefix(Config.PREFIX).values()); } catch (RuntimeException e) { /* no settings file */ }
        for (Map.Entry<String, String> e : System.getenv().entrySet()) if (e.getKey().startsWith(Config.PREFIX) || e.getKey().startsWith("CODEZAIKU_") || e.getKey().startsWith("LIBRARIAN_")) values.add(e.getValue());
        return values;
    }

    /**
     * An address a tool read out of an answer from outside while it carries out a call, and asks next (a caption track named in a
     * video's own record, a feed named in a page): it is given from here on, unless the call's own words wrote it. A tool calls this
     * with what an answer held, never with a value of its arguments.
     */
    public static void fromAnswer(String address) {
        if (address == null || address.isBlank()) return;
        Call c = CURRENT.get();
        if (c == null || !c.wrote(address)) GivenAddresses.receivedWhole(address);   // whole, as the answer named it: a value of its own, not a piece of a sentence
    }

    /**
     * An address the owner chose that is kept somewhere other than the settings (a server they added to the library, the library's own
     * port): its host, with its port, counts as theirs from here on, for as long as it is in use ({@link #OWNERS_FOR} after the last
     * time it was said). Called by the code that reads such a record or uses such a host, at each use, never with a value out of a
     * model's call that the person has not agreed to.
     */
    public static void owners(String address) {
        if (address == null) return;
        Matcher m = IN_SETTINGS.matcher(address.strip());
        if (m.find()) OWNERS.put(host(m.group(1)) + (m.group(2) == null ? "" : ":" + m.group(2)), CLOCK.getAsLong());
    }

    /** A test's stand-in for a host of the program's list: {@code 127.0.0.1:PORT}. Nothing in the program calls this. */
    public static void standIn(String hostAndPort) { if (hostAndPort != null) STAND_INS.add(hostAndPort.strip().toLowerCase(Locale.ROOT)); }

    /**
     * Forgets the hosts said to be the owner's ({@link #owners}) and the tests' stand-ins: the suite calls it before each test. Both are
     * kept for the process, by host and port, and a test's stub server on the loopback is said to be one; a later test of the same
     * process whose own server was given the same port by the system then found that port the owner's, and the guard let through a
     * request it was written to refuse (2026-11-25, OutboundTest). Nothing in the program calls this.
     */
    public static void forgetSaid() { OWNERS.clear(); STAND_INS.clear(); }
    public static void noStandIns() { STAND_INS.clear(); }

    private static final ThreadLocal<Call> CURRENT = new ThreadLocal<>();

    /** A model's tool call being carried out on this thread, from its arguments; closing it ends the call. */
    public static Call call(JsonNode args) { return call("", args); }

    /** The same, with the tool's name, for the record of what a call asked ({@link #REPORT}). */
    public static Call call(String tool, JsonNode args) {
        Call c = new Call(tool, args, CURRENT.get());
        CURRENT.set(c);
        return c;
    }

    /** What a call's arguments hold, with no call begun: for what an answer may give ({@link GivenAddresses#gave}). */
    public static Call of(JsonNode args) { return new Call("", args, null); }

    /** The call being carried out on this thread, or null. */
    public static Call current() { return CURRENT.get(); }

    /** A host as it is compared: small letters, no dot after it, no brackets around it. */
    static String host(String h) {
        if (h == null) return "";
        String s = h.strip().toLowerCase(Locale.ROOT);
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * Every host a text of small letters names: two or more names joined by dots, each of letters, digits and hyphens with a letter
     * or a digit at both its ends, with no such character before or after the whole. Read by hand, one character after another.
     * Until the second reading of 0.8.1 a pattern read it, and a pattern reads names joined by dots one level deeper for each name:
     * a call whose arguments held some 1,500 of them in a row ("0.0.0.0." and so on, as a model that repeats itself writes) ended
     * the turn with the thread's stack used up. What is found is the same as the pattern found ({@code OutboundReviewTest}).
     */
    static void dotted(String lower, Consumer<String> to) {
        int n = lower.length();
        for (int i = 0; i < n; ) {
            if (!ofAHost(lower.charAt(i))) { i++; continue; }
            int j = i;
            while (j < n && ofAHost(lower.charAt(j))) j++;
            // the run's parts between its dots: each stretch of two or more names in a row is a host
            int start = -1, names = 0;
            for (int at = i; at <= j; ) {
                int dot = at;
                while (dot < j && lower.charAt(dot) != '.') dot++;
                boolean name = dot > at && lower.charAt(at) != '-' && lower.charAt(dot - 1) != '-';
                if (name && names++ == 0) start = at;
                if (!name || dot >= j) { if (names >= 2) to.accept(lower.substring(start, name ? dot : at - 1)); names = 0; }
                at = dot + 1;
            }
            i = j;
        }
    }

    private static boolean ofAHost(char c) { return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-'; }
    private static final Pattern WRITTEN = Pattern.compile("(?i)https?://[^\\s<>\"`\\[\\]]+");
    private static final Pattern PERCENT = Pattern.compile("%([0-9a-fA-F]{2})"), BACKSLASH_U = Pattern.compile("\\\\u([0-9a-fA-F]{4})"), ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]{1,6});");

    /** The words of one call's arguments, in every reading a tool may give them, and what they name. */
    public static final class Call implements AutoCloseable {
        private final String words, tool;
        private final Set<String> hosts = new LinkedHashSet<>(), givenHosts = new HashSet<>();
        /** What came back from outside while this call was carried out, for the record only: the addresses written in the answers, and their hosts. */
        private final Set<String> arrived = new HashSet<>(), arrivedHosts = new HashSet<>();
        /** The hosts a declared tool's own answers named ({@link #found}). */
        private final Set<String> found = ConcurrentHashMap.newKeySet();
        /** What the guard refused while this call was carried out: "tool \u2192 host: rule", for the trace. */
        private final List<Refusal> refusals = new CopyOnWriteArrayList<>();
        private final Call outer;
        /** The requests of this call that were let through, by their address as it was sent: how each was, for where its server sends it on. */
        private final Map<String, Sent> asked = Collections.synchronizedMap(new LinkedHashMap<>() { @Override protected boolean removeEldestEntry(Map.Entry<String, Sent> e) { return size() > 512; } });
        /** Where a server sent a request of this call on, by the address as the server wrote it, with the request it was sent on from. */
        private final Map<String, Sent> sentOn = Collections.synchronizedMap(new LinkedHashMap<>() { @Override protected boolean removeEldestEntry(Map.Entry<String, Sent> e) { return size() > 512; } });
        /** The last request of this call that was let through. */
        private volatile Sent last;

        private Call(String tool, JsonNode args, Call outer) {
            this.outer = outer; this.tool = tool == null ? "" : tool;
            StringBuilder raw = new StringBuilder();
            gather(args, raw);
            String folded = GivenAddresses.fold(raw.toString()).toLowerCase(Locale.ROOT);
            // as written; with its %XX escapes read; with its numeric entities read; with its backslash escapes read: a tool may read any of them
            String percent = replace(PERCENT, folded, m -> String.valueOf((char) Integer.parseInt(m.group(1), 16)));
            String entity = replace(ENTITY, folded, m -> { try { return new String(Character.toChars(Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16))); } catch (RuntimeException e) { return m.group(); } }).replace("&amp;", "&");
            String slash = replace(BACKSLASH_U, folded, m -> String.valueOf((char) Integer.parseInt(m.group(1), 16))).replace("\\/", "/");
            this.words = folded + "\n" + percent.toLowerCase(Locale.ROOT) + "\n" + entity.toLowerCase(Locale.ROOT) + "\n" + slash.toLowerCase(Locale.ROOT);
            dotted(words, hosts::add);
            // the hosts of the given addresses the arguments hold: another page of such a host is the tool's to ask for
            Matcher w = WRITTEN.matcher(raw);
            while (w.find()) remember(w.group());
            if (args != null && args.isObject()) for (String k : new String[]{"url", "address"}) if (args.path(k).isTextual()) remember(args.path(k).asText());
        }

        private void remember(String address) {
            String a = address.strip();
            if (a.isEmpty() || !GivenAddresses.given(a.matches("(?is)^[a-z][a-z0-9+.-]*://.*") ? a : "https://" + a)) return;
            try { String h = URI.create(a.matches("(?is)^[a-z][a-z0-9+.-]*://.*") ? a.replace(" ", "%20") : "https://" + a.replace(" ", "%20")).getHost(); if (h != null) givenHosts.add(host(h)); }
            catch (IllegalArgumentException notAnAddress) { /* a given address java.net.URI cannot read is asked for by nobody */ }
        }

        private static void gather(JsonNode v, StringBuilder out) {
            if (v == null) return;
            if (v.isTextual()) out.append(v.asText()).append('\n');
            else if (v.isNumber()) out.append(v.asText()).append('\n');   // a host written as one number
            else if (v.isContainerNode()) { if (v.isObject()) v.fieldNames().forEachRemaining(n -> out.append(n).append('\n')); for (JsonNode x : v) gather(x, out); }
        }

        private static String replace(Pattern p, String s, Function<Matcher, String> with) {
            Matcher m = p.matcher(s);
            StringBuilder b = new StringBuilder();
            while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(with.apply(m)));
            m.appendTail(b);
            return b.toString();
        }

        /** The arguments' words as they were read, every reading of them: what a text that came back from this call does not make given. */
        public String said() { return words; }

        /** Whether the arguments name this host: it, a name under it, or a name it is under; a host of one word, as a word of its own. */
        public boolean names(String host) {
            String h = host(host);
            if (h.isEmpty()) return false;
            for (String t : hosts) if (h.equals(t) || h.endsWith("." + t) || t.endsWith("." + h)) return true;
            if (h.indexOf('.') < 0) return Pattern.compile("(?<![a-z0-9.-])" + Pattern.quote(h) + "(?![a-z0-9-])").matcher(words).find();   // localhost, a machine's own name, a host written as one number
            return false;
        }

        /** Whether the arguments spell out this path and query: nothing after the host counts as spelled out, since the host alone is the address. */
        public boolean holds(String pathAndQuery) {
            String p = pathAndQuery == null ? "" : pathAndQuery.toLowerCase(Locale.ROOT);
            while (p.startsWith("/")) p = p.substring(1);
            while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
            if (p.isEmpty()) return true;
            return words.contains(p) || words.contains(GivenAddresses.unescaped(p).toLowerCase(Locale.ROOT));
        }

        /**
         * Whether the arguments spell out this path and query as an address would: they hold it, and it is more than one short word (a
         * service's own one-word path, "search", is in many a question without anybody having written an address).
         */
        public boolean spells(String pathAndQuery) {
            String p = pathAndQuery == null ? "" : pathAndQuery;
            while (p.startsWith("/")) p = p.substring(1);
            while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
            return !p.isEmpty() && (p.length() >= 12 || p.indexOf('/') >= 0 || p.indexOf('?') >= 0) && holds(p);
        }

        /** What the guard refused while this call was carried out, each as "tool \u2192 host: rule"; a nested call's are its outer call's too. */
        public List<Refusal> refusals() { return List.copyOf(refusals); }

        /** Whether the arguments hold a given address on this host. */
        boolean gaveHost(String host) { String h = host(host); for (String g : givenHosts) if (h.equals(g) || h.equals("www." + g) || g.equals("www." + h)) return true; return false; }

        /**
         * Whether an address is the arguments' own, written back: its host named in them and what follows the host spelled out in them.
         * An answer that holds such an address holds it only because the call did, however the tool put it together ("https://" before a
         * word of the query, a host made of a language code), and the answer does not make it given.
         */
        public boolean wrote(String address) {
            if (address == null) return false;
            String a = GivenAddresses.fold(address).strip();
            Matcher scheme = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://").matcher(a);
            String rest = scheme.find() ? a.substring(scheme.end()) : a;
            int hash = rest.indexOf('#'); if (hash >= 0) rest = rest.substring(0, hash);
            int cut = rest.length();
            for (char c : new char[]{'/', '?'}) { int i = rest.indexOf(c); if (i >= 0 && i < cut) cut = i; }
            String authority = rest.substring(0, cut), after = rest.substring(cut);
            int at = authority.lastIndexOf('@'); if (at >= 0) authority = authority.substring(at + 1);
            String h = authority.replaceFirst(":\\d{1,5}$", "");
            return names(h) && holds(after);
        }

        @Override public void close() { if (outer == null) CURRENT.remove(); else { outer.refusals.addAll(refusals); CURRENT.set(outer); } }
    }

    // ---- the hosts this program is written for, and the owner's

    private static volatile Set<String> written;

    /** The hosts that stand in this program's own sources ({@code fixed-hosts.txt}, kept in step with them by a test): a name, or {@code *.name} for one label before it. */
    static Set<String> written() {
        Set<String> w = written;
        if (w != null) return w;
        Set<String> out = new HashSet<>();
        try (InputStream in = Outbound.class.getResourceAsStream("fixed-hosts.txt")) {
            if (in != null) try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) { String t = l.strip(); if (!t.isEmpty() && !t.startsWith("#")) out.add(t); }
            }
        } catch (IOException e) { /* none read: no host is taken as the program's own, and a named one must be given */ }
        return written = out;
    }

    private static final Pattern IN_SETTINGS = Pattern.compile("(?i)[a-z][a-z0-9+.-]*://(?:[^\\s/@]*@)?(\\[[0-9a-f:.]+\\]|[^\\s/:?#\"']+)(?::(\\d{1,5}))?");
    private static final Pattern NUMBERS = Pattern.compile("^[0-9.]+$|^[0-9a-f:]*:[0-9a-f:.]*$|^0x[0-9a-f]+$");

    /** A host of this machine or of a private network, or written as numbers: such a host is the program's own only by the owner's settings, with its port. */
    public static boolean local(String h) { return NUMBERS.matcher(h).matches() || h.equals("localhost") || h.endsWith(".localhost") || h.indexOf('.') < 0 || h.endsWith(".local") || h.endsWith(".internal") || h.endsWith(".lan"); }

    /** Whether a request to this host is one the program is written to make, or one the owner's settings name. */
    static boolean fixed(String host, int port) {
        String h = host(host);
        if (!local(h)) {
            Set<String> w = written();
            if (w.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot > 0 && w.contains("*" + h.substring(dot))) return true;
        }
        String withPort = h + (port > 0 ? ":" + port : "");
        if (STAND_INS.contains(withPort) || owner(withPort) || defaultPort(port) && (owner(h) || owner(h + ":80") || owner(h + ":443"))) return true;
        // the owner's settings: every address written in them, the environment's among them; a host of this machine with its port. The
        // search service is one of them when no setting names it (the SearXNG this program sets up on this machine)
        List<String> values = new ArrayList<>();
        try { values.add(WebSearchTool.endpoint()); } catch (RuntimeException e) { /* none */ }
        try { values.addAll(Config.storedWithPrefix(Config.PREFIX).values()); } catch (RuntimeException e) { /* no settings file */ }
        for (Map.Entry<String, String> e : System.getenv().entrySet()) if (e.getKey().startsWith(Config.PREFIX) || e.getKey().startsWith("CODEZAIKU_") || e.getKey().startsWith("LIBRARIAN_")) values.add(e.getValue());
        for (String v : values) {
            Matcher m = IN_SETTINGS.matcher(v);
            while (m.find()) {
                if (!host(m.group(1)).equals(h)) continue;
                if (!local(h)) return true;
                int p = m.group(2) == null ? -1 : Integer.parseInt(m.group(2));
                if (p == port || (defaultPort(p) && defaultPort(port))) return true;
            }
        }
        return false;
    }

    /**
     * A port a scheme has by itself, written out or left off: the same service either way, in a setting and in a request alike. Until
     * the second reading of 0.8.1 only a setting that left the port off matched both: a setting that wrote {@code :443} out did not
     * match a request for the same address without it.
     */
    private static boolean defaultPort(int port) { return port < 0 || port == 80 || port == 443; }

    /** A request's address as it goes out: its fragment off, what is not ASCII escaped. */
    private static String wire(URI u) { String a = u.toASCIIString(); int hash = a.indexOf('#'); return hash < 0 ? a : a.substring(0, hash); }

    private static final Set<String> SECOND_LEVEL = Set.of("co", "com", "org", "net", "ac", "gov", "edu", "or", "ne", "go", "gr", "lg", "ed", "ad", "mil", "nom", "sch", "ltd", "plc", "gob", "nic");
    /** Names under which each further name is somebody else's: a host there is a site of its own. */
    private static final Set<String> SHARED = Set.of("github.io", "gitlab.io", "githubusercontent.com", "blogspot.com", "wordpress.com", "tumblr.com", "substack.com", "herokuapp.com", "appspot.com",
            "web.app", "firebaseapp.com", "pages.dev", "workers.dev", "vercel.app", "netlify.app", "amazonaws.com", "cloudfront.net", "azurewebsites.net", "windows.net", "googleusercontent.com",
            "neocities.org", "glitch.me", "onrender.com", "fly.dev", "repl.co", "ngrok.io", "ngrok-free.app", "trycloudflare.com", "ts.net", "duckdns.org", "wixsite.com", "weebly.com", "livejournal.com");

    /**
     * The site a host belongs to: the name its owner registered, with every name under it (www.example.org, m.example.org and
     * api.example.org are example.org; a country's second level counts with the name, so example.co.uk is a site). No list of every
     * registry's rules is kept: a host written as numbers, a machine's own name and a host under a name where each further name is
     * somebody else's ({@link #SHARED}) are each a site of their own.
     */
    static String site(String host) {
        String h = host(host);
        if (h.isEmpty() || local(h)) return h;
        String[] l = h.split("\\.");
        int keep = l.length >= 3 && l[l.length - 1].length() == 2 && SECOND_LEVEL.contains(l[l.length - 2]) ? 3 : 2;
        if (l.length <= keep) return h;
        String s = String.join(".", Arrays.copyOfRange(l, l.length - keep, l.length));
        return SHARED.contains(s) ? h : s;
    }

    /** The check itself: nothing while no model's call is carried out on this thread. */
    public static void check(URI uri) throws NotOpened { allowed(CURRENT.get(), uri, null); }

    /** The same for a call that is named: a request asked on another thread for a call begun on this one (a redirect followed in the background). */
    static void check(Call c, URI uri) throws NotOpened { allowed(c, uri, null); }

    /** {@code sentOn}: the request is where a server sent the call's last request on. */
    static void check(Call c, URI uri, boolean sentOn) throws NotOpened { allowed(c, uri, sentOn && c != null ? c.last : null); }

    /**
     * Lets a request of a call through, or refuses it; {@code from}: the request a server sent on to this one, when the guard follows
     * the hop itself (a hop a fetcher follows is known by its address, {@link #sentOnTo}). How it was let through is kept with the
     * call, for where this request is sent on in its turn.
     */
    private static Way allowed(Call c, URI uri, Sent from) throws NotOpened {
        if (c == null || uri == null || uri.getHost() == null) return Way.GIVEN;
        String h = host(uri.getHost());
        if (REPORT != null) report(c, uri, h);
        String after = (uri.getRawPath() == null ? "" : uri.getRawPath()) + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        boolean named = c.names(h), listed = fixed(h, uri.getPort()), spelled = named && c.spells(after);
        Sent hop = from != null ? from : c.sentOn.get(wire(uri));
        Way way = null;
        if (GivenAddresses.given(uri.toString())) way = Way.GIVEN;                              // (a) an address that was given
        else if (hop != null && (hop.way() == Way.GIVEN || hop.resolved())) way = Way.GIVEN;   // (c) where the server of a request that carried nothing of the model's sent it on: the server's choice
        else if (listed && !spelled) way = Way.WORDS;                                         // (b) a host the program is written for, or the owner's: the model's words go there as words
        else if (!named && ownMachine(h) && (hop == null || ownMachine(hop.host()) || site(hop.host()).equals(site(h)))) way = Way.WORDS;   // (b) this machine itself, or a machine of the owner's settings, at a port the tool's own code chose; sent on to only by one of the owner's own machines: a host of the public web does not send this program's requests into the house
        else if (hop == null && c.gaveHost(h) && !c.holds(after)) way = Way.GIVEN;            // (a) another page of a host the call was given, which its arguments do not spell out
        else if (FINDS.containsKey(c.tool) && !named && c.found.contains(h) && !c.spells(after)) way = Way.GIVEN;   // a declared tool, at a host its own answers named: its words were given ones
        else if (hop != null && !spelled && site(hop.host()).equals(site(h))) way = Way.WORDS;   // (c) a request that may carry the call's words, sent on within its own site
        if (way != null) {
            boolean resolved = way == Way.WORDS && RESOLVERS.containsKey(h) && GivenIdentifiers.resolves(uri, c.holds(after));
            Sent sent = new Sent(way, h, resolved);
            c.asked.put(wire(uri), sent);
            c.last = sent;
            return way;
        }
        String rule = hop != null ? SENT_ON : listed ? SPELLED : named ? NAMED : NOT_LISTED;
        String why = switch (rule) {
            case SENT_ON -> hop.host() + " sent this request on to " + h + ", which is neither a host this tool is written to ask nor a part of the site that was asked, and the address is not one that the person gave or that a search, a page or a tool returned. A request that may carry a call's own words is followed only within such hosts.";
            case SPELLED -> "this call's own words spell out an address on " + h + " that nobody gave. Give an address as the person wrote it or as a search, a page or a tool returned it.";
            case NAMED -> "this call's own words name " + h + ", and what was to be asked of it is not an address that the person gave or that a search, a page or a tool returned; the library asks a host a model named only for an address that was given.";
            default -> h + " is not a host this tool is written to ask and not one in the owner's settings, and the address is not one that the person gave or that a search, a page or a tool returned.";
        };
        NotOpened refused = new NotOpened(h, rule, why);
        c.refusals.add(new Refusal(c.tool, h, rule));
        log.warn("a request was not sent while the tool {} was carried out: {} ({})", c.tool.isEmpty() ? "(unnamed)" : c.tool, h, rule);
        throw refused;
    }

    /**
     * A file for the record, named by the environment ({@code RESEARCHZOSHO_OUTBOUND_REPORT}); none unless it is set. It changes
     * nothing that is asked. Every request made while a model's call is carried out is written to it with what a rule that lets
     * nothing through by default would say of it: a host of the program's or the owner's (fixed), an address that was given
     * (given), an address or a host that an answer from outside held earlier in the same call (arrived), or none of these (none),
     * and whether the call's own words name the host. It is how the cost of such a rule was measured before deciding on it.
     */
    static final String REPORT = System.getenv("RESEARCHZOSHO_OUTBOUND_REPORT");

    private static void report(Call c, URI uri, String h) {
        try {
            String kind = fixed(h, uri.getPort()) ? "fixed" : GivenAddresses.given(uri.toString()) ? "given" : c.arrived.contains(GivenAddresses.fold(uri.toString())) ? "arrived-address" : c.arrivedHosts.contains(h) ? "arrived-host" : "none";
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String line = c.tool + "\t" + kind + "\t" + (c.names(h) ? "named" : "not-named") + "\t" + h + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + "\t" + (path.length() > 60 ? path.substring(0, 60) + "…" : path) + "\n";
            synchronized (Outbound.class) { Files.writeString(Path.of(REPORT), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        } catch (IOException | RuntimeException e) { /* the record is not the work */ }
    }

    /** For the record: what an answer from outside held, while a call was carried out on this thread. */
    private static <T> HttpResponse<T> noted(Call c, HttpResponse<T> r) {
        if (REPORT == null || c == null || r == null) return r;
        Object body = r.body();
        String text = body instanceof String s ? s : body instanceof byte[] b ? new String(b, 0, Math.min(b.length, 2_000_000), StandardCharsets.UTF_8) : null;
        r.headers().firstValue("location").ifPresent(l -> { try { URI to = r.uri().resolve(l); c.arrived.add(GivenAddresses.fold(to.toString())); if (to.getHost() != null) c.arrivedHosts.add(host(to.getHost())); } catch (RuntimeException e) { /* no address */ } });
        if (text == null) return r;
        Matcher m = WRITTEN.matcher(text.replace("\\/", "/"));
        while (m.find()) { String a = m.group(); c.arrived.add(GivenAddresses.fold(a)); try { String ah = URI.create(a.replaceAll("[^\\x21-\\x7e]", "")).getHost(); if (ah != null) c.arrivedHosts.add(host(ah)); } catch (RuntimeException e) { /* no address */ } }
        return r;
    }

    /**
     * An address a server sent the program on to (a redirect a fetcher follows itself), asked for next: where the call's last request
     * was sent on. With no call it is an address from outside. In a call it is one when the request that was sent on carried nothing
     * of the model's ({@link Way#GIVEN}, or a resolver asked for a given name); otherwise the next request is held to where a request
     * with the call's words may be sent on.
     */
    public static void sentOnTo(String address) { sentOnTo(null, address); }

    /** The same, naming the request that was sent on. */
    public static void sentOnTo(URI from, String address) {
        if (address == null || address.isBlank()) return;
        Call c = CURRENT.get();
        if (c == null) { GivenAddresses.receivedWhole(address); return; }
        Sent asked = from == null ? c.last : c.asked.getOrDefault(wire(from), c.last);
        if (asked == null) return;
        try { c.sentOn.put(wire(URI.create(address.strip())), asked); } catch (IllegalArgumentException notAnAddress) { return; }
        if (asked.way() == Way.GIVEN || asked.resolved()) GivenAddresses.receivedWhole(address);   // the server's own choice: an address from outside, as a link on its page is
    }

    /** A given address as a request for it is made: in the spelling it was given in; null when {@code spelling} cannot be a request's address. */
    private static URI inSpelling(String spelling) {
        try { return URI.create(spelling); } catch (IllegalArgumentException notAUri) { /* a character an address cannot hold as it stands: written as its escape */ }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < spelling.length(); i++) {
            char ch = spelling.charAt(i);
            if (ch <= ' ' || "\"<>\\^`{|}".indexOf(ch) >= 0) b.append('%').append(String.format("%02X", (int) ch)); else b.append(ch);
        }
        try { return URI.create(b.toString()); } catch (IllegalArgumentException notAUri) { return null; }
    }

    /**
     * The request as it goes out while a call is carried out: an address that was given is asked for in the spelling and with the
     * scheme it was given in, whatever the tool built ({@link GivenAddresses#asRequested}); where a server sent the call on is asked
     * for as the server wrote it; anything else is the tool's own request and is judged as it stands.
     */
    private static HttpRequest asGiven(Call c, HttpRequest request) {
        URI u = request.uri();
        if (u == null || u.getHost() == null || c.sentOn.containsKey(wire(u))) return request;
        String as = GivenAddresses.asRequested(u.toString());
        URI want = as == null ? null : inSpelling(as);
        if (want == null || want.getHost() == null || wire(want).equals(wire(u))) return request;
        return HttpRequest.newBuilder(request, (name, value) -> true).uri(want).build();
    }

    // ---- the client every request goes through

    /** {@code real}, with every request checked before it is sent. */
    public static HttpClient client(HttpClient real) { return real instanceof Checked ? real : new Checked(real); }

    /** How many times a request is sent on before the answer is handed back as it is, as the JDK's own client counts them. */
    static final int HOPS = 5;

    private static final class Checked extends HttpClient {
        private final HttpClient real;
        Checked(HttpClient real) { this.real = real; }
        /** The same client with no following of its own, made when first needed: for a call's requests, whose every hop is asked from here. */
        private volatile HttpClient plain;

        private HttpClient plain() {
            HttpClient p = plain;
            if (p != null) return p;
            synchronized (this) {
                if (plain != null) return plain;
                HttpClient.Builder b = HttpClient.newBuilder().followRedirects(Redirect.NEVER).version(real.version()).sslContext(real.sslContext()).sslParameters(real.sslParameters());
                real.connectTimeout().ifPresent(b::connectTimeout);
                real.proxy().ifPresent(b::proxy);
                real.authenticator().ifPresent(b::authenticator);
                real.cookieHandler().ifPresent(b::cookieHandler);
                real.executor().ifPresent(b::executor);
                return plain = b.build();
            }
        }

        /**
         * The request an answer sends the program on to, or null when it is not one to follow: by the rules the JDK's own client follows
         * a redirect by (301, 302, 303, 307, 308 with a Location; never from https down to http unless the client was built to follow
         * always; a POST answered 301 or 302 asked again as a GET, anything answered 303 as a GET; at most {@link #HOPS} times), and with
         * one rule of this program's: a header that carries a key goes to the host it was written for and no further.
         */
        private HttpRequest onward(HttpRequest asked, HttpResponse.ResponseInfo info, int hop) {
            int code = info.statusCode();
            if (hop >= HOPS || !(code == 301 || code == 302 || code == 303 || code == 307 || code == 308)) return null;
            String to = info.headers().firstValue("location").orElse(null);
            if (to == null || to.isBlank()) return null;
            URI was = asked.uri(), target;
            try { target = was.resolve(to.strip()); } catch (IllegalArgumentException e) { return null; }
            String scheme = target.getScheme() == null ? "" : target.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || target.getHost() == null) return null;
            if (real.followRedirects() != Redirect.ALWAYS && "https".equalsIgnoreCase(was.getScheme()) && scheme.equals("http")) return null;
            String method = code == 303 ? (asked.method().equals("HEAD") ? "HEAD" : "GET") : (code == 301 || code == 302) && asked.method().equals("POST") ? "GET" : asked.method();
            HttpRequest.Builder b = HttpRequest.newBuilder(target).expectContinue(asked.expectContinue());
            asked.timeout().ifPresent(b::timeout);
            asked.version().ifPresent(b::version);
            boolean sameHost = target.getHost().equalsIgnoreCase(was.getHost()) && target.getPort() == was.getPort() && scheme.equalsIgnoreCase(was.getScheme());
            asked.headers().map().forEach((name, values) -> {
                String n = name.toLowerCase(Locale.ROOT);
                if (!sameHost && (n.equals("authorization") || n.equals("cookie") || n.contains("key") || n.contains("token") || n.contains("secret"))) return;
                for (String v : values) b.header(name, v);
            });
            boolean sameBody = method.equals(asked.method()) && asked.bodyPublisher().isPresent();
            return b.method(method, sameBody ? asked.bodyPublisher().get() : HttpRequest.BodyPublishers.noBody()).build();
        }

        /** Whether this client's redirects are followed here: while a model's call is carried out, when the client would follow them by itself. */
        private boolean byHand(Call c) { return c != null && real.followRedirects() != Redirect.NEVER; }

        @Override public <T> HttpResponse<T> send(HttpRequest asIs, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
            Call c = CURRENT.get();
            if (c == null) return real.send(asIs, handler);   // no model's call: the program's own work
            HttpRequest request = asGiven(c, asIs);            // a given address goes out as it was given, whatever the tool built
            allowed(c, request.uri(), null);
            if (!byHand(c)) return noted(c, real.send(request, handler));
            // a client that follows redirects by itself asks the hops where no check sees them. While a model's call is carried out they
            // are asked from here, each one checked as where the request before it was sent on: anywhere, when that request carried
            // nothing of the model's; within the program's own hosts and the asked site, when it may carry the call's words (a
            // service that redirects to whatever a value of its query says would otherwise let those words choose where a request ends)
            HttpRequest next = request;
            for (int hop = 0; ; hop++) {
                HttpRequest[] to = new HttpRequest[1];
                HttpRequest asked = next;
                int n = hop;
                HttpResponse<T> r = noted(c, plain().send(asked, info -> (to[0] = onward(asked, info, n)) != null ? HttpResponse.BodySubscribers.replacing(null) : handler.apply(info)));
                if (to[0] == null) return r;
                followed(c, asked.uri(), to[0].uri());
                next = to[0];
            }
        }

        /** Checks a hop the guard follows; where a request that carried nothing of the model's was sent on is an address from outside from here on. */
        private static void followed(Call c, URI from, URI to) throws NotOpened {
            Sent asked = c.asked.get(wire(from));
            allowed(c, to, asked == null ? c.last : asked);
            if (asked != null && (asked.way() == Way.GIVEN || asked.resolved())) GivenAddresses.receivedWhole(to.toString());
        }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest asIs, HttpResponse.BodyHandler<T> handler) {
            Call c = CURRENT.get();
            if (c == null) return real.sendAsync(asIs, handler);
            HttpRequest request = asGiven(c, asIs);
            try { allowed(c, request.uri(), null); } catch (NotOpened e) { return CompletableFuture.failedFuture(e); }
            return byHand(c) ? hop(c, request, handler, 0) : real.sendAsync(request, handler);
        }
        private <T> CompletableFuture<HttpResponse<T>> hop(Call c, HttpRequest asked, HttpResponse.BodyHandler<T> handler, int n) {
            HttpRequest[] to = new HttpRequest[1];
            return plain().sendAsync(asked, (HttpResponse.BodyHandler<T>) info -> (to[0] = onward(asked, info, n)) != null ? HttpResponse.BodySubscribers.replacing(null) : handler.apply(info)).thenCompose(r -> {
                noted(c, r);
                if (to[0] == null) return CompletableFuture.completedFuture(r);
                try { followed(c, asked.uri(), to[0].uri()); } catch (NotOpened e) { return CompletableFuture.<HttpResponse<T>>failedFuture(e); }
                return hop(c, to[0], handler, n + 1);
            });
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest asIs, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
            Call c = CURRENT.get();
            if (c == null) return real.sendAsync(asIs, handler, push);
            HttpRequest request = asGiven(c, asIs);
            try { allowed(c, request.uri(), null); } catch (NotOpened e) { return CompletableFuture.failedFuture(e); }
            return byHand(c) ? hop(c, request, handler, 0) : real.sendAsync(request, handler, push);   // no client of this program's takes a server's pushes
        }
        @Override public Optional<CookieHandler> cookieHandler() { return real.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return real.connectTimeout(); }
        @Override public Redirect followRedirects() { return real.followRedirects(); }
        @Override public Optional<ProxySelector> proxy() { return real.proxy(); }
        @Override public SSLContext sslContext() { return real.sslContext(); }
        @Override public SSLParameters sslParameters() { return real.sslParameters(); }
        @Override public Optional<Authenticator> authenticator() { return real.authenticator(); }
        @Override public Version version() { return real.version(); }
        @Override public Optional<Executor> executor() { return real.executor(); }
        @Override public WebSocket.Builder newWebSocketBuilder() { return real.newWebSocketBuilder(); }
        @Override public void shutdown() { real.shutdown(); }
        @Override public boolean awaitTermination(Duration duration) throws InterruptedException { return real.awaitTermination(duration); }
        @Override public boolean isTerminated() { return real.isTerminated(); }
        @Override public void shutdownNow() { real.shutdownNow(); }
        @Override public void close() { real.close(); }
    }
}
