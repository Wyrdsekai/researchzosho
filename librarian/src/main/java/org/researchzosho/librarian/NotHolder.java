package org.researchzosho.librarian;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A library this process no longer holds the lease of ({@link Lease}): from the moment the daemon finds another process holding it, no
 * write of this process goes into the library's folder. The writers the library's work goes through ask here first ({@link AtomicWrite},
 * the job records, the kept work, the logs, the ledgers, the access check of a request that writes) and are refused with the sentence
 * that says who holds the library.
 */
final class NotHolder {

    private NotHolder() { }

    /** A write refused because another process holds the library: its message is the sentence the daemon says it with. */
    static final class Refused extends IOException {
        Refused(String why) { super(why); }
    }

    private static final Map<Path, String> GIVEN_UP = new ConcurrentHashMap<>();

    /** No write goes into {@code root} from now on in this process, for {@code why}. */
    static void giveUp(Path root, String why) { GIVEN_UP.put(root.toAbsolutePath().normalize(), why); }

    /** Why a write to {@code p} is refused; null when it is not in a library this process gave up. */
    static String why(Path p) {
        if (GIVEN_UP.isEmpty() || p == null) return null;
        Path a = p.toAbsolutePath().normalize();
        for (Map.Entry<Path, String> e : GIVEN_UP.entrySet()) if (a.startsWith(e.getKey())) return e.getValue();
        return null;
    }

    /** Throws {@link Refused} when {@code p} is in a library this process gave up. */
    static void check(Path p) throws Refused {
        String why = why(p);
        if (why != null) throw new Refused(why);
    }
}
