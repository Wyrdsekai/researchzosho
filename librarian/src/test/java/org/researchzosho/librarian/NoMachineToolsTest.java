package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The suite's guard against the machine's own tools ({@link NoMachineTools}): what a test finds, and what it is told when it leaves a runner of its own. */
class NoMachineToolsTest {

    @Test void aTestFindsTheSuitesRunnerWhichRunsNoToolOfTheMachine() {
        assertSame(NoMachineTools.NOT_HERE, Searx.runner, "set before every test");
        IOException e = assertThrows(IOException.class, () -> Searx.runner.run(List.of("docker", "version")));
        assertTrue(e.getMessage().startsWith("Cannot run program \"docker\""), "as a machine without the program answers: " + e.getMessage());
        assertFalse(Searx.haveDocker(), "so the program finds no docker on the machine the suite runs on");
        assertSame(NoMachineTools.MODEL_NOT_HERE, ModelServer.runner, "and the model server's runner is the suite's too");
        assertThrows(IOException.class, () -> ModelServer.runner.run(List.of("nvidia-smi")), "so the machine's card is not asked about either");
        assertEquals(NoMachineTools.NO_LABSE, TranslationScores.LOCAL_LABSE, "and the machine's own LaBSE is a port where nothing listens");
        assertNull(TranslationScores.labseAddress(true), "so a translation finds no LaBSE of the machine's and starts none");
        assertSame(NoMachineTools.NO_CONTAINERS, ModelsAwake.CONTAINERS, "and the containers the idle rule looks after answer at ports where nothing listens");
        assertFalse(ModelsAwake.startForCall(NoMachineTools.NO_LABSE), "so a call to one starts nothing");
        assertSame(NoMachineTools.NOWHERE, ModelServer.reach, "and a request for a pinned file (a download, the release's check) goes to a port where nothing listens");
        assertEquals("http://127.0.0.1:6/huggingface.co/unsloth/x/resolve/main/x.gguf", ModelServer.reach.apply("https://huggingface.co/unsloth/x/resolve/main/x.gguf"));
    }

    @Test void aTestThatLeavesItsOwnRunnerIsNamedAndTheSuitesIsPutBack() {
        Searx.runner = cmd -> new Searx.Result(0, "a test's own answer");
        String said = NoMachineTools.leftOver("SomeTest.itsTest()");
        assertSame(NoMachineTools.NOT_HERE, Searx.runner, "put back, so the tests after it run no tool either");
        assertEquals("SomeTest.itsTest() left Searx.runner in place of the suite's, which reach nothing of the machine: a test that stands in for a tool or a server puts back the one it found", said);
        TranslationScores.LOCAL_LABSE = "http://127.0.0.1:" + TranslationScores.LABSE_PORT;
        Searx.runner = cmd -> new Searx.Result(0, "");
        assertEquals("SomeTest.itsTest() left Searx.runner, TranslationScores.LOCAL_LABSE in place of the suite's, which reach nothing of the machine: a test that stands in for a tool or a server puts back the one it found", NoMachineTools.leftOver("SomeTest.itsTest()"));
        assertEquals(NoMachineTools.NO_LABSE, TranslationScores.LOCAL_LABSE);
        assertNull(NoMachineTools.leftOver("SomeTest.itsTest()"), "and a test that leaves the suite's runner is told nothing");
    }
}
