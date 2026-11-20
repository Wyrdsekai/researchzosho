package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * The program's HTTP servers, made in one place so that each answers at once. Java 21's own server writes an answer's head and its body
 * as two small packets on a connection without TCP_NODELAY, so the second waits for the caller's acknowledgement of the first, which the
 * caller delays: every answer took about 40 ms more (measured 2026-11-19 on Linux: 42.7 ms a request on Java 21, 1.7 ms with the setting;
 * 2.6 ms on Java 25, whose server sends them together). A page, an API call and every call through a node's gates paid it, and a test that
 * asks some 2,300 questions on one sixty-second statement ran out of its time on Java 21. The setting is read once, when the JDK's server
 * is first made in a process, so it is set before that, unless whoever runs the program set it otherwise.
 */
public final class HttpServers {
    private HttpServers() { }

    static final String NO_DELAY = "sun.net.httpserver.nodelay";

    static { noDelay(); }

    /** Answers at once: set before any of the JDK's HTTP servers is made in this process (the program's start calls it first). */
    public static void noDelay() { if (System.getProperty(NO_DELAY) == null) System.setProperty(NO_DELAY, "true"); }

    /** {@link HttpServer#create(InetSocketAddress, int)}, its answers sent at once. */
    public static HttpServer create(InetSocketAddress at, int backlog) throws IOException { return HttpServer.create(at, backlog); }
}
