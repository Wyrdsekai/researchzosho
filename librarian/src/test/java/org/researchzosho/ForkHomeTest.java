package org.researchzosho;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Each fork of the test task starts in an empty home: nothing a test of an earlier run set there reaches this run. On 2026-11-26 a run
 * stopped in the middle of a test left the nights off in its fork's settings, and the next whole run's fork of the same number found
 * them there: AccountsCliTest's two tests went red, and passed alone and in the run after.
 */
class ForkHomeTest {

    @Test
    void eachForkOfTheTestTaskStartsInAnEmptyHome() {
        assumeTrue(ForkHome.startedEmpty() != null, "a JVM the Gradle test task started");
        assertEquals(Boolean.TRUE, ForkHome.startedEmpty(), "this JVM's home under the suite's scratch home already held files from an earlier run: "
                + System.getProperty("user.home"));
    }
}
