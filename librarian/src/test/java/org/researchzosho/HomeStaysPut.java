package org.researchzosho;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Around every test and every test class (registered for all of them in META-INF/services, with autodetection on in
 * junit-platform.properties): user.home is where the test task put it when a test ends, and when a class ends. A test swaps it for
 * a scratch folder of its own and puts it back; one that does not leaves every test after it reading and writing settings in a
 * folder that is not the suite's, or no longer exists — the kind of thing that passes on one box and fails on another (a leaked
 * home hid the suite's drive from the tests after it, 2026-10-12). The test or class that leaked is failed by name, and the home
 * is put back so the rest of the suite is not. A class that STARTS with a swapped home is failed too, naming the home it found:
 * then the swap came from outside any test — a thread an earlier class left running, or a hook after its end.
 */
public final class HomeStaysPut implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(HomeStaysPut.class);
    /** The home the test task set: the first one seen. */
    private static volatile String suiteHome;

    @Override public void beforeAll(ExtensionContext context) {
        String now = System.getProperty("user.home");
        if (suiteHome == null) suiteHome = now;
        context.getStore(NS).put("class", now);
        if (!suiteHome.equals(now)) {
            System.setProperty("user.home", suiteHome);
            Config.invalidate();
            context.getStore(NS).put("class", suiteHome);
            fail(context.getDisplayName() + " started with user.home at " + now + " (the suite's is " + suiteHome + "): swapped between classes, by a thread an earlier class left running or a hook after its end");
        }
    }

    @Override public void beforeEach(ExtensionContext context) {
        context.getStore(NS).put("test", System.getProperty("user.home"));
    }

    @Override public void afterEach(ExtensionContext context) {
        check(context, context.getStore(NS).get("test", String.class));
    }

    @Override public void afterAll(ExtensionContext context) {
        check(context, context.getStore(NS).get("class", String.class));
    }

    private static void check(ExtensionContext context, String was) {
        String now = System.getProperty("user.home");
        if (was == null || was.equals(now)) return;
        System.setProperty("user.home", was);
        Config.invalidate();
        fail(context.getDisplayName() + " left user.home at " + now + " (it was " + was + "): a test that swaps the home puts it back, in a finally or an @AfterEach");
    }
}
