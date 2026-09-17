package org.researchzosho.librarian;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * The terminal chat's input and output. On a real terminal: line editing, a history the up and down
 * arrows walk through (kept in the library, so it survives the program), Ctrl-R to search it, and
 * notices printed above the prompt without disturbing what is being typed. When input is a pipe or a
 * file: a plain reader, so scripts work as before.
 */
public interface ChatIo extends AutoCloseable {

    /** One line from the person; null when input has ended (Ctrl-D, or the pipe closed). */
    String readLine(String prompt);

    /** A line from another thread while the person may be typing: it appears above the prompt. */
    void notifyLine(String s);

    @Override void close();

    /** Where the history is kept: in the library, beside the conversations. */
    static Path historyFile(LibraryStore store) { return store.root().resolve("catalog").resolve("chat").resolve("history"); }

    /** A line editor when there is a terminal, a plain reader otherwise. The check comes first so that a pipe never makes the terminal library complain. */
    static ChatIo open(LibraryStore store) {
        if (System.console() == null) return new Plain();
        try {
            Terminal t = TerminalBuilder.builder().system(true).build();
            if (t.getType() != null && t.getType().startsWith(Terminal.TYPE_DUMB)) { t.close(); return new Plain(); }
            return new Editor(t, historyFile(store));
        } catch (IOException | RuntimeException e) {
            return new Plain();
        }
    }

    /** A pipe, a file, or a terminal the editor cannot drive. */
    final class Plain implements ChatIo {
        private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        @Override public String readLine(String prompt) {
            synchronized (System.out) { System.out.print(prompt); System.out.flush(); }
            try { return in.readLine(); } catch (IOException e) { return null; }
        }
        @Override public void notifyLine(String s) { synchronized (System.out) { System.out.println("\n" + s); System.out.print("> "); System.out.flush(); } }
        @Override public void close() { }
    }

    /** A real terminal: editing, history, notices above the prompt. */
    final class Editor implements ChatIo {
        private final Terminal terminal;
        private final LineReader reader;

        Editor(Terminal terminal, Path history) {
            this.terminal = terminal;
            try { java.nio.file.Files.createDirectories(history.getParent()); } catch (IOException ignored) { }
            this.reader = LineReaderBuilder.builder().terminal(terminal)
                    .variable(LineReader.HISTORY_FILE, history)
                    .variable(LineReader.HISTORY_SIZE, 2000)
                    .option(LineReader.Option.HISTORY_IGNORE_DUPS, true)
                    .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)   // "!" in a question is a "!", not a history command
                    .build();
        }

        @Override public String readLine(String prompt) {
            try { return reader.readLine(prompt); }
            catch (UserInterruptException e) { return ""; }     // Ctrl-C clears the line and stays in the conversation
            catch (EndOfFileException e) { return null; }       // Ctrl-D leaves
        }

        @Override public void notifyLine(String s) {
            try { reader.printAbove(s); }
            catch (RuntimeException e) { terminal.writer().println(s); terminal.writer().flush(); }
        }

        @Override public void close() {
            try { reader.getHistory().save(); } catch (IOException | RuntimeException ignored) { }
            try { terminal.close(); } catch (IOException ignored) { }
        }
    }
}
