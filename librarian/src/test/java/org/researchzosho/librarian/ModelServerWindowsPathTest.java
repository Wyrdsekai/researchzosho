package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The model server on Windows, installed under a user folder with a space in its name (C:\Users\Ann Hale): the logon task, the start
 * right after install and the script's own Start-Process must each hand the whole path on. The command is followed the way Windows
 * handles it, with the same steps as {@link ServiceWindowsPathTest}.
 */
class ModelServerWindowsPathTest {

    static final Path DIR = Path.of("C:\\Users\\Ann Hale\\.researchzosho\\model");

    final ModelServer.Runner realRunner = ModelServer.runner;
    final ModelServer.Detacher realDetach = ModelServer.detach;

    @AfterEach void restore() { ModelServer.runner = realRunner; ModelServer.detach = realDetach; }

    private static ModelServer.Plan plan() {
        return ModelServer.plan(ModelServer.Os.windows, ModelServer.rowFor(16, 16), Path.of("C:\\Users\\Ann Hale\\models\\gpt-oss-20b-F16.gguf"), DIR, DIR.resolve("start.ps1"), "all", 45, false, null);
    }

    @Test
    void theLogonTaskAndTheStartNowRunTheScriptInAUserFolderWithASpace() throws Exception {
        ModelServer.Plan p = plan();
        List<List<String>> ran = new ArrayList<>();
        ModelServer.runner = cmd -> { ran.add(cmd); return new ModelServer.Result(0, ""); };
        ModelServer.detach = ran::add;
        assertEquals("", ModelServer.startService(ModelServer.Os.windows, p));
        List<String> schtasks = ran.stream().filter(c -> c.get(0).equals("schtasks")).findFirst().orElseThrow();
        List<String> line = ServiceWindowsPathTest.argv(ServiceWindowsPathTest.commandLine(schtasks));
        String tr = line.get(line.indexOf("/tr") + 1);
        List<String> task = ServiceWindowsPathTest.argv(tr);
        assertEquals("powershell", task.get(0), tr);
        assertEquals(p.unit().toString(), task.get(task.indexOf("-File") + 1), "the task's command line: " + tr);
        List<String> now = ServiceWindowsPathTest.argv(ServiceWindowsPathTest.commandLine(ran.stream().filter(c -> c.get(0).equals("powershell")).findFirst().orElseThrow()));
        assertEquals(p.unit().toString(), now.get(now.indexOf("-File") + 1), now.toString());
    }

    @Test
    void theScriptStartsTheProxyWithItsWholeConfigPath() {
        ModelServer.Plan p = plan();
        // Start-Process joins its argument list with spaces and quotes nothing; llama-swap then splits its command line by the C runtime's rules
        Matcher m = Pattern.compile("-ArgumentList\\s+(.*?)\\s+-WindowStyle").matcher(p.unitText());
        assertTrue(m.find(), p.unitText());
        Matcher part = Pattern.compile("'((?:[^']|'')*)'").matcher(m.group(1));
        List<String> parts = new ArrayList<>();
        while (part.find()) parts.add(part.group(1).replace("''", "'"));
        List<String> swap = ServiceWindowsPathTest.argv(String.join(" ", parts));
        assertEquals(Arrays.asList("--config", DIR.resolve("config.yaml").toString(), "--listen", "127.0.0.1:8211"), swap, p.unitText());
    }
}
