package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The proof that a process a record names is the one meant (0.8.0): alive, started no later than the record was last written, and its
 * whole command line accepted. The command line is read on each system as it shows it whole; on Windows by a command pinned here.
 */
class ProcessProofTest {
    @AfterEach void back() { ProcessProof.READ = null; }

    @Test
    void theServersCommandLineIsThisProgramsServe() {
        assertTrue(ProcessProof.server(List.of("/usr/lib/jvm/bin/java", "-Xmx1g", "-cp", "/opt/researchzosho/lib/*", "org.researchzosho.Main", "serve", "--port", "4649")));
        assertTrue(ProcessProof.server(List.of("\"C:\\Program", "Files\\Java\\bin\\java.exe\"", "-cp", "\"C:\\rz\\lib\\*\"", "org.researchzosho.Main", "serve")), "a Windows command line, split at its spaces");
        assertFalse(ProcessProof.server(List.of("java", "org.researchzosho.Main", "translate", "x")), "not serve");
        assertFalse(ProcessProof.server(List.of("java", "org.researchzosho.Main", "serve", "--vouched-by", "http://127.0.0.1:4649")), "a library an account service started is not the server");
        assertFalse(ProcessProof.server(List.of("java", "-jar", "someone-elses.jar", "serve")), "another program");
        assertFalse(ProcessProof.server(List.of()), "nothing read, nothing proven");
    }

    @Test
    void onWindowsTheCommandLineIsReadFromCim() {
        assertEquals(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", "(Get-CimInstance Win32_Process -Filter \"ProcessId=4242\").CommandLine"), ProcessProof.windowsCommand(4242));
    }

    /** The record's time may be later than the start by any amount, and earlier by at most two seconds: coarse file times, a boot time in whole seconds. */
    @Test
    void aRecordWrittenUpToTwoSecondsBeforeTheStartStillProvesIt(@TempDir Path tmp) throws Exception {
        Instant start = Instant.parse("2026-11-17T09:00:00.700Z");
        assertTrue(ProcessProof.startedBy(start, start.plusSeconds(30)), "written after the start");
        assertTrue(ProcessProof.startedBy(start, start), "at the start");
        assertTrue(ProcessProof.startedBy(start, start.minusMillis(1500)), "1.5 s before: inside the allowance");
        assertTrue(ProcessProof.startedBy(start, start.minusMillis(2000)), "2 s before: the edge");
        assertFalse(ProcessProof.startedBy(start, start.minusMillis(3000)), "3 s before: outside");
        assertFalse(ProcessProof.startedBy(null, start), "a start not known proves nothing");
        // and the same with a real process and a real record's time
        Process p = new ProcessBuilder("sleep", "600").start();
        try {
            Thread.sleep(200);
            Instant started = ProcessHandle.of(p.pid()).orElseThrow().info().startInstant().orElseThrow();
            Path record = Files.writeString(tmp.resolve("x.pid"), p.pid() + "\n", StandardCharsets.UTF_8);
            Files.setLastModifiedTime(record, FileTime.from(started.minusMillis(1500)));
            assertTrue(ProcessProof.proven(p.pid(), record, w -> true), "1.5 s inside");
            Files.setLastModifiedTime(record, FileTime.from(started.minusMillis(3000)));
            assertFalse(ProcessProof.proven(p.pid(), record, w -> true), "3 s outside");
        } finally { p.destroy(); p.waitFor(); }
    }

    @Test
    void aProcessIsTheOneARecordNamesOnlyWhenItStartedBeforeTheRecordAndItsCommandLineIsAccepted(@TempDir Path tmp) throws Exception {
        Process p = new ProcessBuilder("sleep", "600").start();
        try {
            Thread.sleep(200);
            Path record = Files.writeString(tmp.resolve("x.pid"), p.pid() + "\n", StandardCharsets.UTF_8);
            assertEquals(List.of("sleep", "600"), ProcessProof.words(p.pid()), "the whole command line, as /proc gives it");
            assertTrue(ProcessProof.proven(p.pid(), record, w -> w.equals(List.of("sleep", "600"))));
            assertFalse(ProcessProof.proven(p.pid(), record, w -> false), "its command line not accepted");
            Files.setLastModifiedTime(record, FileTime.fromMillis(System.currentTimeMillis() - 3_600_000));
            assertFalse(ProcessProof.proven(p.pid(), record, w -> true), "it started after the record was written: the number was given to it since");
            assertFalse(ProcessProof.proven(ProcessHandle.current().pid(), record, w -> true), "never this process");
            assertFalse(ProcessProof.proven(p.pid(), tmp.resolve("no-such-record"), w -> true), "no record, nothing proven");
        } finally { p.destroy(); p.waitFor(); }
        assertFalse(ProcessProof.proven(p.pid(), tmp.resolve("x.pid"), w -> true), "a process that has ended is no process");
    }
}
