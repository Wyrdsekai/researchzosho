package org.researchzosho.librarian;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stand-in for the server `serve` leaves running: a Java process whose command line has the server's shape (this program's main
 * class, then {@code serve}), and which only sleeps. Since 0.8.0 the program stops, or takes for its running server, only a process
 * whose command line proves it is the server; a plain Java program that sleeps is another program. The stand-in's main class is
 * compiled into a folder of the test's own, so nothing of the real program runs.
 */
final class StandInServer {
    private StandInServer() { }

    static Process start(Path dir) throws Exception {
        Path src = dir.resolve("stand-in-src").resolve("org").resolve("researchzosho").resolve("Main.java"), classes = dir.resolve("stand-in-classes");
        if (!Files.isRegularFile(classes.resolve("org").resolve("researchzosho").resolve("Main.class"))) {
            Files.createDirectories(src.getParent()); Files.createDirectories(classes);
            Files.writeString(src, "package org.researchzosho; public class Main { public static void main(String[] a) throws Exception { Thread.sleep(600_000); } }", StandardCharsets.UTF_8);
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            assertEquals(0, javac.run(null, null, err, "-d", classes.toString(), src.toString()), err.toString());
        }
        Process p = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), "-cp", classes.toString(), ProcessProof.MAIN, "serve", "--port", "0")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Thread.sleep(300);
        assertTrue(p.isAlive());
        assertTrue(ProcessProof.server(ProcessProof.words(p.pid())), "the stand-in has the server's command line: " + ProcessProof.words(p.pid()));
        return p;
    }

    /** A Java program that sleeps and is not the server: another program the system may have given the number. */
    static Process other(Path dir) throws Exception {
        Path src = dir.resolve("SomeoneElses.java");
        Files.writeString(src, "public class SomeoneElses { public static void main(String[] a) throws Exception { Thread.sleep(600_000); } }", StandardCharsets.UTF_8);
        Process p = new ProcessBuilder(ProcessHandle.current().info().command().orElse("java"), src.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Thread.sleep(300);
        assertTrue(p.isAlive());
        return p;
    }

    static List<String> words(Process p) { return ProcessProof.words(p.pid()); }
}
