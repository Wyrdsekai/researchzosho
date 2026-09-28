package org.researchzosho.librarian;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Whether a person is at the keyboard to answer a question. A command run by a script, in a pipe, by the service or by another program
 * never asks and never waits: nobody could answer.
 */
public final class Interaction {

    private Interaction() { }

    /** Tests set this to answer as a person at the keyboard would; null asks the terminal. */
    static volatile Boolean OVERRIDE = null;
    /** Tests give the answer here; null reads the terminal. */
    static volatile BufferedReader INPUT = null;

    /**
     * True when the command runs in a terminal a person can type into. From Java 22 a console can exist with its input or output redirected,
     * so {@code Console.isTerminal} is asked too, where the running Java has it.
     */
    public static boolean interactive() {
        if (OVERRIDE != null) return OVERRIDE;
        Console c = System.console();
        if (c == null) return false;
        try {
            Method m = Console.class.getMethod("isTerminal");
            return (Boolean) m.invoke(c);
        } catch (ReflectiveOperationException e) { return true; }   // Java 21: a console exists only for a terminal
    }

    /** One line the person types, or null at the end of the input. */
    public static String readLine() throws IOException {
        if (INPUT != null) return INPUT.readLine();
        Console c = System.console();
        if (c != null) return c.readLine();
        return new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
    }

    /** A reader of what the person types, for a sitting of several questions. */
    public static BufferedReader reader() {
        if (INPUT != null) return INPUT;
        Console c = System.console();
        return new BufferedReader(c != null ? c.reader() : new InputStreamReader(System.in, StandardCharsets.UTF_8));
    }

    /** The question asked, with no as the answer given by Enter: true only for a clear yes. */
    public static boolean yes(String question) throws IOException {
        System.out.print(question + " ");
        System.out.flush();
        String line = readLine();
        return line != null && Librarian.yesWord(line) != null;
    }
}
