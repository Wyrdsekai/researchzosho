package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `researchzosho jobs [<J-…>]`: the CLI named it after every `research ask` through 0.1.8, and it did not exist. */
class JobsVerbTest {
    @TempDir Path tmp;

    @Test
    void listsFiledRunsAndShowsOne() throws Exception {
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        var ask = new ObjectMapper().createObjectNode();
        ask.put("question", "How were the gears of the Antikythera mechanism cut?"); ask.put("mode", "broad"); ask.put("sources", "web");
        ask.putObject("patron").put("did", "person").put("name", "test").put("runtime", "cli");
        String id = new LibraryProtocol(store).research(ask).path("job_id").asText();
        assertTrue(id.startsWith("J-"), id);

        String list = run(store, "jobs");
        assertTrue(list.contains("jobs: 0 running, 1 queued"), list);
        assertTrue(list.contains(id + " [queued] research") && list.contains("Antikythera"), list);

        String one = run(store, "jobs", id);
        assertTrue(one.startsWith(id + "  queued  research"), one);
        assertTrue(one.contains("question: How were the gears of the Antikythera mechanism cut?"), one);

        String missing = run(store, "jobs", "J-9999");
        assertTrue(missing.isBlank() || !missing.contains("queued"), missing);
    }

    private static String run(LibraryStore store, String... verbAndArgs) throws Exception {
        String[] args = new String[verbAndArgs.length + 1];
        args[0] = "librarian"; System.arraycopy(verbAndArgs, 0, args, 1, verbAndArgs.length);
        PrintStream oldOut = System.out, oldErr = System.err;
        var out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true)); System.setErr(new PrintStream(new ByteArrayOutputStream()));
        try { LibrarianCli.jobs(store, args); } finally { System.setOut(oldOut); System.setErr(oldErr); }
        return out.toString();
    }
}
