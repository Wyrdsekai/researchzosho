package org.researchzosho.librarian;

import org.researchzosho.Config;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Where a library of the account service runs (0.8.0). The service asks for a library's address and sends the request there; how the
 * library comes to answer at that address is this interface's business and nobody else's: a process of its own ({@link ChildLibraries})
 * or a container of its own ({@link ContainerLibraries}) on a machine whose node keeps it ({@link Node}), and the service's way to
 * the nodes of several machines ({@link Nodes}) answer the same calls.
 */
public interface LibraryRunner {
    /** The address the library answers at (http://host:port), started first when it is not answering; an exception says in a sentence why it could not be. */
    String ensure(AccountStore.Library library) throws IOException;

    /** What is known of the library is forgotten, because a request sent to its address was not taken: the next {@link #ensure} looks again. */
    void lost(String libraryId);

    /** Every library this runner started, stopped: the service is ending. */
    void stopAll();

    /** Which start of the library the last {@link #ensure} answered with, by the registry's count; 0 from a runner that keeps none. Every statement made for the library carries it. */
    default long epoch(String libraryId) { return 0; }

    /**
     * The address of a library that is running now, and null for one that is not: it is never started for this. For what a page asks
     * by itself, which wakes no library. A runner that never puts a library to sleep answers as {@link #ensure} does.
     */
    default String ifAwake(AccountStore.Library library) throws IOException { return ensure(library); }

    /** Whether the library is running now. A runner that never puts a library to sleep says yes. */
    default boolean awake(String libraryId) { return true; }

    /** The request that {@link #ensure} was asked for has been answered to its end: a library with none open may be put to sleep. */
    default void done(String libraryId) { }

    /**
     * The library's archive to take home, written to {@code out} as it is made (stage 4), by whatever holds the library's folder; {@code from}
     * is the service's address, written into the archive. A runner that cannot pack its libraries says so.
     */
    default void archive(AccountStore.Library library, OutputStream out, String from) throws IOException {
        throw new IOException(Pages.t("This service cannot pack its libraries to take home."));
    }

    /** Which form a node starts its libraries in: a process each ({@code process}, unset) or a container each ({@code container}). */
    String FORM = "RESEARCHZOSHO_ACCOUNTS_RUNNER";

    /**
     * The libraries of one machine, as a node starts and stops them (0.8.0): each a process of its own ({@link ChildLibraries}) or each
     * in a container of its own ({@link ContainerLibraries}), by the setting {@link #FORM}. The node keeps the leases, decides the
     * sleep and the night, and asks for these calls; it does not know which form answers them.
     */
    interface Local extends LibraryRunner {
        /** The library started as the registry's start {@code epoch} (0: none kept); its address once it answers there with its id. */
        String start(AccountStore.Library lib, long epoch) throws IOException;

        /** Whether the library this runner started or took back is still there. */
        boolean alive(String libraryId, long epoch);

        /** One library asked to stop, so it lets its folder's lease go; by force after 30 s. */
        void stop(String libraryId);

        /** One that a node which ended without stopping it left running, taken back when it answers at its address as this library and this start. */
        boolean adopt(AccountStore.Library lib, String address, long epoch);

        /** The number of the library's process, for whoever runs the service; 0 where there is none to give. */
        default long pid(String libraryId) { return 0; }

        /** The form the setting names, for libraries that reach the service at {@code service}. */
        static Local of(AccountStore store, String service) { return of(store, service, service); }

        /**
         * The same, where the node itself reaches the service at {@code reach}: the service's own loopback when the node is part of the
         * service's process, else the service's address. A library in a container reaches the service through a socket of the node's
         * that goes there, never through the web's gate (0.8.0 review).
         */
        static Local of(AccountStore store, String service, String reach) {
            return "container".equalsIgnoreCase(String.valueOf(Config.get(FORM)).strip()) ? new ContainerLibraries(store, service, reach) : new ChildLibraries(store, service);
        }
    }
}
