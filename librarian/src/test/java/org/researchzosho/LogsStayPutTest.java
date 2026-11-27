package org.researchzosho;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.ConsoleAppender;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.researchzosho.drive.DriveClient;
import org.researchzosho.librarian.Logs;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The suite's guard LogsStayPut: what serve, the chat and images change in the logging of the process that runs them is put back when
 * the test that ran them ends, so the test after it reads the log as the program sets it (2026-11-26: DriveConsoleTest found no line of
 * the service's log on stderr after AccountsLinuxOnlyTest had run serve in its fork).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LogsStayPutTest {

    static PrintStream errBefore;

    static ConsoleAppender<?> console() {
        return (ConsoleAppender<?>) ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).getAppender("STDOUT");
    }

    @Test @Order(1)
    void aCommandRunInTheTestsOwnProcessChangesItsLogging() {
        errBefore = System.err;
        Logs.toStdout(); Logs.warnOnly("org.researchzosho"); DriveClient.service = true;   // what serve with no terminal and the chat do
        assertEquals("System.out", console().getTarget());
        assertSame(System.out, System.err);
    }

    @Test @Order(2)
    void theTestAfterItReadsTheLogAsTheProgramSetsIt() {
        assertEquals("System.err", console().getTarget(), "the console is stderr, as logback.xml has it");
        if (errBefore != null) assertSame(errBefore, System.err, "stderr is the fork's own again");
        assertNull(((Logger) LoggerFactory.getLogger("org.researchzosho")).getLevel(), "the library's notes are at the level the program gives them");
        assertFalse(DriveClient.service, "the process is not a service");
    }
}
