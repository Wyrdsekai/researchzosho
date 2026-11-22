package org.researchzosho;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The suite's eye on the names this process asks a resolver for (registered in META-INF/services as the JVM's resolver provider):
 * every lookup goes on to the JDK's own resolver as before, and a test that wants to know whether a name was looked up says which
 * names it watches ({@link #watch}). A watched name is written down and answered "no such host" here, so a test of what must never
 * be looked up asks no name server anything, even while the code under test still does the looking.
 *
 * <p>A name written as numbers is never handed to a resolver by the JDK, so nothing is seen for one: what is seen is a name that
 * would have gone to DNS, where the server of the name's domain reads it.
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
                return builtin.lookupByName(host, policy);
            }
            @Override public String lookupByAddress(byte[] address) throws UnknownHostException { return builtin.lookupByAddress(address); }
        };
    }

    @Override public String name() { return "the suite's record of the names it looks up"; }
}
