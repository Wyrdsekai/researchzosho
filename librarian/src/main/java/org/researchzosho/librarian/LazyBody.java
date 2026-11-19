package org.researchzosho.librarian;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;

/**
 * An answer's body that begins when its first byte is written (0.8.0): until then nothing is sent, so a failure before it can still be
 * answered with an error of its own; from then on the answer is 200, sent as it is written. For an archive made as it is sent.
 */
final class LazyBody extends OutputStream {
    private final HttpExchange x;
    private final String type;
    private OutputStream body;

    LazyBody(HttpExchange x, String type) { this.x = x; this.type = type; }

    /** Whether the answer has begun: its status and headers are sent. */
    boolean started() { return body != null; }

    private OutputStream body() throws IOException {
        if (body == null) {
            x.getResponseHeaders().set("Content-Type", type);
            x.sendResponseHeaders(200, 0);
            body = x.getResponseBody();
        }
        return body;
    }

    @Override public void write(int b) throws IOException { body().write(b); }
    @Override public void write(byte[] b, int off, int len) throws IOException { if (len > 0) body().write(b, off, len); }
    @Override public void flush() throws IOException { if (body != null) body.flush(); }
    @Override public void close() throws IOException { if (body != null) body.close(); }
}
