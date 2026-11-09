package org.researchzosho.librarian;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import org.researchzosho.Config;
import org.slf4j.LoggerFactory;

/**
 * Where the service's log lines go. At a terminal they go to stderr, as every command's do, so a program reading the
 * command's output never gets them. In a container or under a cloud runner, where no terminal is attached, they go
 * to stdout, which is where such platforms read a service's log; {@code RESEARCHZOSHO_LOG=stdout} or {@code stderr}
 * says so outright. A log file ({@code serve --log}) takes both streams and is not changed by this.
 */
public final class Logs {
    private Logs() { }

    public static final String SETTING = "RESEARCHZOSHO_LOG";

    /** Whether {@code serve} writes its lines to stdout: the setting says so, or nothing says and no terminal is attached. */
    public static boolean wantsStdout(boolean logFile) { return wantsStdout(Config.get(SETTING, ""), logFile, Interaction.interactive()); }

    static boolean wantsStdout(String setting, boolean logFile, boolean terminal) {
        if (logFile) return false;
        if (setting != null && !setting.isBlank()) return setting.strip().equalsIgnoreCase("stdout");
        return !terminal;
    }

    /** The log's console appender turned to stdout, and stderr with it, so every line of this process goes to one stream. */
    public static void toStdout() {
        if (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) instanceof Logger root) {
            Appender<ILoggingEvent> a = root.getAppender("STDOUT");
            if (a instanceof ConsoleAppender<ILoggingEvent> c) { c.stop(); c.setTarget("System.out"); c.start(); }
        }
        System.setErr(System.out);
    }
}
