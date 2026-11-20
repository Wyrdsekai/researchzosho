package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The accounts mode is Linux only in 0.8.0 (its libraries' sockets are proven by SO_PEERCRED, its processes read from /proc), and the
 * service says so when it is asked to start it anywhere else, in one sentence, and starts nothing (round G: the public CI on macOS).
 */
class AccountsLinuxOnlyTest {
    @AfterEach void back() { AccountsCli.OS_NAME = () -> System.getProperty("os.name", ""); }

    @Test
    void theServiceAndAMachineOfItRefuseToStartOffLinuxAndSaySo() throws Exception {
        assertNull(AccountsCli.notHere("Linux"));
        for (String os : new String[]{"Mac OS X", "Windows 11", "FreeBSD"}) {
            assertEquals("Several accounts run on Linux only in this release.", AccountsCli.notHere(os), os);
            AccountsCli.OS_NAME = () -> os;
            for (String verb : new String[]{"serve", "node"}) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
                int rc = verb.equals("serve")
                        ? AccountsCli.serve(new String[]{"librarian", "serve", "--port", "0"}, "http://127.0.0.1:1", new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                        : AccountsCli.node(new String[]{"librarian", "node", "--port", "0"}, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
                assertEquals(1, rc, verb + " on " + os);
                assertEquals("Several accounts run on Linux only in this release.", err.toString(StandardCharsets.UTF_8).strip(), verb + " on " + os);
                assertTrue(out.toString(StandardCharsets.UTF_8).isEmpty(), "nothing else said: " + out);
            }
        }
    }
}
