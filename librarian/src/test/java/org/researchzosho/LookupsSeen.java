package org.researchzosho;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The suite's eye on the names this process asks a resolver for (registered in META-INF/services as the JVM's resolver provider, in
 * the test process and in every child process started on the test class path). Only the names of this machine go on to the JDK's
 * own resolver: localhost, a name under .localhost, and the machine's own name. Every other name is answered "no such host" here, as a
 * machine with no network answers it, so that no test reaches a server on the network or sends a name to a name server: a test that
 * needs a site's answers stands in for the site on the loopback. Before this, tests of the suite asked GitHub, Wikidata, Crossref,
 * arXiv, Open Library and OpenRouter, and sent the made-up names of their stand-ins to DNS (2026-11-23).
 *
 * <p>A test that wants to know whether a name was looked up says which names it watches ({@link #watch}). A watched name is written
 * down, and answered "no such host" too, so a test of what must never be looked up asks no name server anything, even while the code
 * under test still does the looking. A name written as numbers is never handed to a resolver by the JDK, so nothing is seen for one.
 */
public final class LookupsSeen extends InetAddressResolverProvider {
    private static volatile Predicate<String> watched;
    private static final List<String> SEEN = new CopyOnWriteArrayList<>();

    /** From here on the names {@code which} holds for are written down and not looked up; what was seen before is forgotten. */
    public static void watch(Predicate<String> which) { SEEN.clear(); watched = which; }

    /** The watched names that were asked for since {@link #watch}, in order; the watch ends. */
    public static List<String> seen() { watched = null; return List.copyOf(SEEN); }

    @Override public InetAddressResolver get(Configuration configuration) {
        InetAddressResolver builtin = configuration.builtinResolver();
        return new InetAddressResolver() {
            @Override public Stream<InetAddress> lookupByName(String host, LookupPolicy policy) throws UnknownHostException {
                Predicate<String> w = watched;
                if (w != null && host != null && w.test(host)) { SEEN.add(host); throw new UnknownHostException(host); }
                if (host != null && !ofThisMachine(host, configuration)) throw new UnknownHostException(host + " (the test suite looks up no name outside this machine)");
                return builtin.lookupByName(host, policy);
            }
            @Override public String lookupByAddress(byte[] address) throws UnknownHostException { return builtin.lookupByAddress(address); }
        };
    }

    /** Whether a name is one of this machine's own: localhost, a name under .localhost, or the machine's name. */
    static boolean ofThisMachine(String host, Configuration configuration) {
        String h = host.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
        return h.equals("localhost") || h.endsWith(".localhost") || h.equalsIgnoreCase(configuration.lookupLocalHostName());
    }

    @Override public String name() { return "the suite's record of the names it looks up"; }
}
