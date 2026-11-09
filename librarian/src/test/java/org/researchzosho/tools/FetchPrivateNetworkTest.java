package org.researchzosho.tools;

import java.net.URI;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A library at home may read its own network; a library behind a trusted proxy may not, unless the keeper says so. */
class FetchPrivateNetworkTest {

    @Test
    void theRuleFollowsTheSettingAndElseTheProxy() {
        assertFalse(Fetch.denyPrivate("", ""), "at home, with nothing set, the own network is readable");
        assertTrue(Fetch.denyPrivate("", "10.0.0.0/8"), "behind a trusted proxy, with nothing set, it is not");
        assertFalse(Fetch.denyPrivate("allow", "10.0.0.0/8"), "the keeper's allow wins behind a proxy");
        assertTrue(Fetch.denyPrivate("deny", ""), "the keeper's deny wins at home");
        assertTrue(Fetch.denyPrivate(" DENY ", null));
    }

    @Test
    void everyPrivateRangeIsRefusedWhenTheRuleSaysSo() {
        for (String u : new String[]{"http://10.1.2.3/", "http://172.20.0.5/", "http://192.168.1.10/", "http://100.101.102.103/", "http://[fd12:3456::1]/"}) {
            String why = Fetch.refusal(URI.create(u), true);
            assertNotNull(why, u + " is a private address");
            assertTrue(why.contains("private network"), why);
            assertNull(Fetch.refusal(URI.create(u), false), u + " is readable at home");
        }
        assertNull(Fetch.refusal(URI.create("http://100.128.0.1/"), true), "100.128/9 is outside the shared range");
        assertNull(Fetch.refusal(URI.create("http://93.184.215.14/"), true), "a public address stays readable");
    }

    @Test
    void theMetadataAddressIsRefusedAtHomeToo() {
        assertNotNull(Fetch.refusal(URI.create("http://169.254.169.254/latest/meta-data/"), false), "link-local is never fetched");
    }
}
