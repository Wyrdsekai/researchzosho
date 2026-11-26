package org.researchzosho.tools;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What one test says of a host of the loopback does not reach the next test of the same process. The guard keeps the hosts said to be the
 * owner's and the tests' stand-ins by host and port, for the process; a test's stub server on the loopback is said to be one (a model
 * client made for it says so), and a later test whose own server the system gave the same port found that port the owner's: a hop to the
 * host its call's words named was let through (OutboundTest, a red suite of 2026-11-25; made to happen on purpose with an earlier class
 * that said every port of the system's range). The suite forgets both before each test (NoLiveChecks). The two tests run in this order.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboundSaidBeforeTest {
    static final int OWNERS_PORT = 41817, STAND_IN_PORT = 41818;

    @Test @Order(1)
    void anEarlierTestSaysItsStubServersOnTheLoopbackAreTheOwnersAndItsStandIns() {
        Outbound.owners("http://127.0.0.1:" + OWNERS_PORT + "/v1");   // as a model client made for a stub server says it
        Outbound.standIn("127.0.0.1:" + STAND_IN_PORT);
        assertTrue(Outbound.fixed("127.0.0.1", OWNERS_PORT) && Outbound.fixed("127.0.0.1", STAND_IN_PORT), "the control: within the test that said them, they hold");
    }

    @Test @Order(2)
    void theNextTestFindsNeitherThoughItsOwnServerMayHaveTheSamePort() {
        assertFalse(Outbound.fixed("127.0.0.1", OWNERS_PORT), "an earlier test's owner: " + Outbound.ownersNow());
        assertFalse(Outbound.fixed("127.0.0.1", STAND_IN_PORT), "an earlier test's stand-in");
    }
}
