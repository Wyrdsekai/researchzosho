package org.researchzosho.librarian;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * What a command prints, caught for the browser chat. A field's chat command was written for the terminal and prints there; in the
 * browser its words have to come back as the reply. Once in place, System.out writes to a buffer on the thread that asked and to the
 * console on every other thread, and System.in gives nothing on that thread, so a command that would wait for a typed answer takes no
 * for one and never holds a server waiting for a keyboard. The library's log goes to System.err and is never caught.
 */
public final class ChatCapture {
    private ChatCapture() { }

    /** A command that prints; true when the line was its own. */
    public interface Command { boolean run() throws IOException; }

    private static final ThreadLocal<ByteArrayOutputStream> SINK = new ThreadLocal<>();
    private static boolean installed;

    private static synchronized void install() {
        if (installed) return;
        PrintStream console = System.out;
        InputStream keyboard = System.in;
        System.setOut(new PrintStream(new OutputStream() {
            @Override public void write(int b) { ByteArrayOutputStream s = SINK.get(); if (s != null) s.write(b); else console.write(b); }
            @Override public void write(byte[] b, int off, int len) { ByteArrayOutputStream s = SINK.get(); if (s != null) s.write(b, off, len); else console.write(b, off, len); }
            @Override public void flush() { if (SINK.get() == null) console.flush(); }
        }, true, StandardCharsets.UTF_8));
        System.setIn(new InputStream() {
            @Override public int read() throws IOException { return SINK.get() != null ? -1 : keyboard.read(); }
            @Override public int read(byte[] b, int off, int len) throws IOException { return SINK.get() != null ? -1 : keyboard.read(b, off, len); }
            @Override public int available() throws IOException { return SINK.get() != null ? 0 : keyboard.available(); }
        });
        installed = true;
    }

    /** Runs the command with what it prints on this thread caught: the words, or null when it said the line was not its own. */
    public static String run(Command c) throws IOException {
        install();
        ByteArrayOutputStream was = SINK.get(), buf = new ByteArrayOutputStream();
        SINK.set(buf);
        boolean took;
        try { took = c.run(); }
        finally { System.out.flush(); if (was == null) SINK.remove(); else SINK.set(was); }
        return took ? buf.toString(StandardCharsets.UTF_8) : null;
    }

    /** A reply printed for the terminal, as text for the browser: the two-space indent the terminal chat uses taken off each line. */
    public static String unindent(String printed) {
        StringBuilder b = new StringBuilder();
        for (String l : printed.split("\\R", -1)) b.append(l.startsWith("  ") ? l.substring(2) : l).append('\n');
        return b.toString().strip();
    }
}
