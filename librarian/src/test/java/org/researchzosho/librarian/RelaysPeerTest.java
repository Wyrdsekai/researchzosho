package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The node passes the service's requests to a library in a container only through a socket it can prove is the library's own (0.8.0
 * review, round A): a link put in the library's sockets' folder in place of its socket is refused, and so is a socket whose listening
 * process is not the library's own (the folder's owner, under the library's own group), before a byte of the request is written.
 */
class RelaysPeerTest {
    static String ask(int port) throws IOException {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.getOutputStream().write("POST /containers/create HTTP/1.1\r\nHost: docker\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            s.setSoTimeout(5_000);
            return new String(s.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        } catch (SocketException reset) { return ""; }   // closed before it read the request: refused
    }

    /** A socket that writes down what reaches it and answers. */
    static void listening(Relays r, Path sock, String answer, List<String> heard) throws IOException {
        r.unix(sock, ch -> {
            ByteBuffer b = ByteBuffer.allocate(4096);
            int n = ch.read(b);
            heard.add(n <= 0 ? "" : new String(b.array(), 0, n, StandardCharsets.US_ASCII));
            ch.write(ByteBuffer.wrap(answer.getBytes(StandardCharsets.US_ASCII)));
            ch.close();
        });
    }

    @Test
    void theNodeWritesNothingToASocketItCannotProveIsTheLibrarysOwn(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("s")), other = Files.createDirectories(tmp.resolve("o"));
        UserPrincipal owner = Files.getOwner(tmp);
        GroupPrincipal ours = FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByGroupName(String.valueOf(Files.getAttribute(tmp, "unix:gid")));
        GroupPrincipal libraries = FileSystems.getDefault().getUserPrincipalLookupService().lookupPrincipalByGroupName("2000123456");
        List<String> elsewhereHeard = new CopyOnWriteArrayList<>(), libraryHeard = new CopyOnWriteArrayList<>();
        Relays elsewhere = new Relays(), library = new Relays(), node = new Relays();
        try {
            listening(elsewhere, other.resolve("t.sock"), "SOMEONE ELSE'S SOCKET\n", elsewhereHeard);

            // code in the container puts a link where the library's socket goes: the node writes nothing to where it leads
            Files.createSymbolicLink(dir.resolve(Relays.IN), other.resolve("t.sock"));
            int port = node.tcpToUnix(InetAddress.getLoopbackAddress(), 0, dir.resolve(Relays.IN), new Relays.Peer(owner, ours));
            assertEquals("", ask(port), "refused");
            Thread.sleep(300);
            assertEquals(List.of(), elsewhereHeard, "not a byte reached the other socket");

            // a socket by the right name whose listening process is not the library's own (another user or group): refused, before a byte
            Files.delete(dir.resolve(Relays.IN));
            listening(library, dir.resolve(Relays.IN), "THE LIBRARY\n", libraryHeard);
            int strict = node.tcpToUnix(InetAddress.getLoopbackAddress(), 0, dir.resolve(Relays.IN), new Relays.Peer(owner, libraries));
            assertEquals("", ask(strict));
            Thread.sleep(300);
            assertTrue(libraryHeard.stream().allMatch(String::isEmpty), "connected to ask who listens, and closed without a byte: " + libraryHeard);

            // the library's own socket, its own process listening: passed both ways
            assertEquals("THE LIBRARY\n", ask(port));
            assertEquals(1, libraryHeard.stream().filter(h -> !h.isEmpty()).count());
            assertTrue(libraryHeard.stream().anyMatch(h -> h.startsWith("POST /containers/create")), libraryHeard.toString());
        } finally { elsewhere.close(); library.close(); node.close(); }
    }

    @Test
    void aLibrarysContainerRunsUnderAGroupOfItsOwn() {
        long a = ContainerLibraries.libraryGroup("lib_0123456789abcdef"), b = ContainerLibraries.libraryGroup("lib_fedcba9876543210");
        assertTrue(a >= ContainerLibraries.GROUP_BASE && a < ContainerLibraries.GROUP_BASE + ContainerLibraries.GROUPS && a != b, a + " " + b);
        assertEquals(a, ContainerLibraries.libraryGroup("lib_0123456789abcdef"), "the same at every start, and for a node that takes it back");
    }
}
