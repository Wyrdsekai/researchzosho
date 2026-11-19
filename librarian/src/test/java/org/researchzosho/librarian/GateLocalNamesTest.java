package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.researchzosho.Config;
import org.researchzosho.tools.Addresses;
import org.researchzosho.tools.Fetch;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In a container, this machine by a name never passes the fetcher and never goes past the gate (round F, the second review, S4, proved
 * on c8e057ed): the fetcher skipped its check for any name with the gate on, and the JVM's own list sent localhost and every 127.* name
 * direct, so a page's link to http://localhost:&lt;relay&gt;/gate/… reached the node's relays and was answered with the operator's key.
 */
class GateLocalNamesTest {
    @AfterEach void back() { Config.provide(Map.of()); }

    @Test
    void thisMachineByANameIsRefusedWithTheGateOn() {
        Config.provide(Map.of(Addresses.THROUGH_GATE, "on", "RESEARCHZOSHO_FETCH_PRIVATE", "deny"));
        assertTrue(Fetch.throughGate());
        for (String u : List.of("http://localhost:4701/gate/x/slots", "http://LOCALHOST.:4701/", "http://relay.localhost:4701/", "http://ip6-localhost:4701/", "http://127.0.0.1:4701/gate/x/slots", "http://127.1:4701/"))
            assertNotNull(Fetch.refusal(URI.create(u)), u);
        assertNull(Fetch.refusal(URI.create("http://example.org/")), "a name on the web is the gate's to resolve");
    }

    @Test
    void theProgramsALibraryRunsSendLocalhostThroughTheGate() throws Exception {
        // git and the like go by NO_PROXY: only the service's own address is left out of the gate, never localhost (Java's own client sends a
        // loopback name direct whatever its list says, which is why the fetcher refuses it by name above)
        assertTrue(ContainerLibraries.NO_PROXY.equals("127.0.0.1") && !ContainerLibraries.NO_PROXY.contains("localhost"), ContainerLibraries.NO_PROXY);
    }
}
