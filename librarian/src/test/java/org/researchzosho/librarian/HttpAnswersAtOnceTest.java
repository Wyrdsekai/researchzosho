package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A library's server answers at once on every Java the program runs on (round G: Java 21's own HTTP server sent an answer's head and its
 * body as two small packets without TCP_NODELAY, and each answer waited for the caller's delayed acknowledgement, about 40 ms; the public
 * CI on Java 21 ran a test of 2,300 questions past its statement's sixty seconds). Asked in a process of its own, as the program starts:
 * the setting is read when the first server of the JDK's is made.
 */
class HttpAnswersAtOnceTest {

    @Test
    void aLibrarysServerAnswersWithinMilliseconds(@TempDir Path tmp) throws Exception {
        Process p = CrossProcess.start(tmp.resolve("home"), "answers", tmp.resolve("lib").toString());
        String out = CrossProcess.output(p);
        String line = out.lines().filter(l -> l.startsWith("median ")).findFirst().orElse("");
        assertTrue(!line.isEmpty(), "the child said nothing: " + out);
        long ms = Long.parseLong(line.substring(7).strip());
        assertTrue(ms < 20, "an answer took " + ms + " ms (Java " + System.getProperty("java.version") + "); with the caller's delayed acknowledgement it is about 40");
    }
}
