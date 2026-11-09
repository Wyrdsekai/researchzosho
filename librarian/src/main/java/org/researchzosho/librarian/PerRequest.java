package org.researchzosho.librarian;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * An exchange whose attributes are its own. The JDK's HttpServer keeps an exchange's attributes in its context's map, which every request
 * the context serves shares, so a mark the daemon set for one request stayed set for the next one from anywhere else: after one request
 * from a trusted proxy, every later request had its forwarded and relay identity headers believed, whoever sent them; a relay's login
 * named the next visitor who asked to be let in; and the body of one passkey call could be read by another. The daemon wraps each request
 * in this, and its marks (from a trusted proxy, through the door, the body, the relay's login) live and die with it.
 */
final class PerRequest extends HttpExchange {
    private final HttpExchange x;
    private final Map<String, Object> attributes = new HashMap<>();

    PerRequest(HttpExchange x) { this.x = x; }

    @Override public Object getAttribute(String name) { return attributes.get(name); }
    @Override public void setAttribute(String name, Object value) { if (value == null) attributes.remove(name); else attributes.put(name, value); }

    @Override public Headers getRequestHeaders() { return x.getRequestHeaders(); }
    @Override public Headers getResponseHeaders() { return x.getResponseHeaders(); }
    @Override public URI getRequestURI() { return x.getRequestURI(); }
    @Override public String getRequestMethod() { return x.getRequestMethod(); }
    @Override public HttpContext getHttpContext() { return x.getHttpContext(); }
    @Override public void close() { x.close(); }
    @Override public InputStream getRequestBody() { return x.getRequestBody(); }
    @Override public OutputStream getResponseBody() { return x.getResponseBody(); }
    @Override public void sendResponseHeaders(int code, long length) throws IOException { x.sendResponseHeaders(code, length); }
    @Override public InetSocketAddress getRemoteAddress() { return x.getRemoteAddress(); }
    @Override public int getResponseCode() { return x.getResponseCode(); }
    @Override public InetSocketAddress getLocalAddress() { return x.getLocalAddress(); }
    @Override public String getProtocol() { return x.getProtocol(); }
    @Override public void setStreams(InputStream in, OutputStream out) { x.setStreams(in, out); }
    @Override public HttpPrincipal getPrincipal() { return x.getPrincipal(); }
}
