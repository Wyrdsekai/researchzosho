package org.researchzosho.librarian;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The suite runs no tool of the machine it runs on and uses none of its servers. The runners the program starts docker, nvidia-smi, an
 * archive tool, a Python and pip through ({@link Searx#runner}, and the model server's {@link ModelServer#runner}) are, in the test
 * process, stand-ins that answer as a machine without the tool; and the machine's own servers, its LaBSE
 * ({@link TranslationScores#LOCAL_LABSE}) and the containers the idle rule looks after ({@link ModelsAwake#CONTAINERS}), are ports where
 * nothing listens. Both are set
 * when the test process opens its session, before any test class is made (registered as a launcher session listener in
 * META-INF/services), and again before every test (registered for all of them, with autodetection on, as {@code NoLiveChecks} is). A
 * test that needs a tool's answers or a LaBSE sets its own and puts back the one it found; one that leaves another in place fails by
 * name, and the suite's is put back, so that the tests after it reach nothing of the machine either.
 *
 * <p>Before this, both were the machine's: translation tests asked docker for the LaBSE container and started it, the idle rule of a
 * test process stopped it again, and a translation scored its units with whatever LaBSE answered on the machine's port, on a machine
 * whose owner's library uses that container (2026-11-23).
 */
public final class NoMachineTools implements LauncherSessionListener, BeforeEachCallback, AfterEachCallback {

    /** The suite's runner: every command fails as a program the machine does not have, the way the real runner fails on such a machine. */
    public static final Searx.Runner NOT_HERE = cmd -> { throw new IOException("Cannot run program \"" + (cmd.isEmpty() ? "" : cmd.get(0)) + "\": the test suite runs no tool of the machine"); };

    /** The same for the model server's commands. */
    public static final ModelServer.Runner MODEL_NOT_HERE = cmd -> { throw new IOException("Cannot run program \"" + (cmd.isEmpty() ? "" : cmd.get(0)) + "\": the test suite runs no tool of the machine"); };

    /**
     * The suite's address of the machine's own LaBSE: a port of the loopback where nothing listens (not port 1, which tests use as a
     * model server that is not there: the idle rule would take a call to it for a call to LaBSE).
     */
    public static final String NO_LABSE = "http://127.0.0.1:3";

    /** The suite's containers of the machine: LaBSE at {@link #NO_LABSE}, the embeddings server at another port where nothing listens. */
    public static final Map<String, String> NO_CONTAINERS = Map.of(TranslationScores.LABSE_CONTAINER, NO_LABSE, Embed.CONTAINER, "http://127.0.0.1:4");

    @Override public void launcherSessionOpened(LauncherSession session) { standIn(); }

    @Override public void beforeEach(ExtensionContext context) { standIn(); }

    static void standIn() { Searx.runner = NOT_HERE; ModelServer.runner = MODEL_NOT_HERE; TranslationScores.LOCAL_LABSE = NO_LABSE; ModelsAwake.CONTAINERS = NO_CONTAINERS; }

    @Override public void afterEach(ExtensionContext context) {
        String said = leftOver(context.getRequiredTestClass().getSimpleName() + "." + context.getDisplayName());
        if (said != null) fail(said);
    }

    /** What a test named {@code test} left in place of the suite's runner, said, with the suite's runner put back; null when it left nothing. */
    static String leftOver(String test) {
        List<String> left = new ArrayList<>();
        if (Searx.runner != NOT_HERE) { Searx.runner = NOT_HERE; left.add("Searx.runner"); }
        if (ModelServer.runner != MODEL_NOT_HERE) { ModelServer.runner = MODEL_NOT_HERE; left.add("ModelServer.runner"); }
        if (!NO_LABSE.equals(TranslationScores.LOCAL_LABSE)) { TranslationScores.LOCAL_LABSE = NO_LABSE; left.add("TranslationScores.LOCAL_LABSE"); }
        if (ModelsAwake.CONTAINERS != NO_CONTAINERS) { ModelsAwake.CONTAINERS = NO_CONTAINERS; left.add("ModelsAwake.CONTAINERS"); }
        return left.isEmpty() ? null : test + " left " + String.join(", ", left) + " in place of the suite's, which reach nothing of the machine: a test that stands in for a tool or a server puts back the one it found";
    }
}
