package org.researchzosho;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.ConsoleAppender;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.researchzosho.drive.DriveClient;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

/**
 * After every test and every test class (registered for all of them in META-INF/services, with autodetection on in
 * junit-platform.properties): the process's logging is put back as the fork began with it. A command a test runs in the test's own
 * process changes the logging of the whole process and never puts it back, as a program that ends after it has no need to: serve,
 * accounts serve and node with no terminal attached turn the log's console to stdout, and stderr with it (Logs.toStdout); the chat and
 * images turn the library's notes down to warnings (Logs.warnOnly); serve marks the process a service (DriveClient.service). Every test
 * after such a one in its fork read a log that was not the program's (2026-11-26: AccountsLinuxOnlyTest's serve turned the console to
 * stdout, and DriveConsoleTest, 150 classes later in the same fork, found none of the service's lines on stderr). Put back here,
 * so no test has to remember.
 */
public final class LogsStayPut implements BeforeAllCallback, AfterEachCallback, AfterAllCallback {

    /** The process's logging at a moment: its two streams, each logger's own level, each console appender's target, whether it is a service. */
    record As(PrintStream out, PrintStream err, Map<String, Level> levels, Map<ConsoleAppender<ILoggingEvent>, String> targets, boolean service) { }

    /** As the fork began: taken before its first class's first test. */
    private static volatile As began;

    @Override public void beforeAll(ExtensionContext context) { if (began == null) began = now(); }
    @Override public void afterEach(ExtensionContext context) { putBack(); }
    @Override public void afterAll(ExtensionContext context) { putBack(); }

    static As now() {
        Map<String, Level> levels = new HashMap<>();
        Map<ConsoleAppender<ILoggingEvent>, String> targets = new HashMap<>();
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext logs) {
            for (Logger l : logs.getLoggerList()) {
                levels.put(l.getName(), l.getLevel());
                for (Iterator<Appender<ILoggingEvent>> it = l.iteratorForAppenders(); it.hasNext(); )
                    if (it.next() instanceof ConsoleAppender<ILoggingEvent> c) targets.put(c, c.getTarget());
            }
        }
        return new As(System.out, System.err, levels, targets, DriveClient.service);
    }

    /** The logging as the fork began; nothing before the fork's first class. */
    static void putBack() {
        As was = began;
        if (was == null) return;
        if (System.out != was.out()) System.setOut(was.out());
        if (System.err != was.err()) System.setErr(was.err());
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext logs)
            for (Logger l : logs.getLoggerList()) {
                Level level = was.levels().get(l.getName());   // a logger made since has no level of its own
                if (!Objects.equals(l.getLevel(), level)) l.setLevel(level);
            }
        was.targets().forEach((c, target) -> { if (!target.equals(c.getTarget())) { c.stop(); c.setTarget(target); c.start(); } });
        DriveClient.service = was.service();
    }
}
