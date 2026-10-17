package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code researchzosho models auto} reaches best judgement: it printed the table of local models instead (0.6.2). */
class ModelsAutoCommandTest {
    @TempDir Path home;

    @Test
    void modelsAutoShowsThePickNotTheTableOfLocalModels() throws Exception {
        Path h = home.resolve("h");
        LibraryStore lib = new LibraryStore(h.resolve("researchzosho-library")); lib.init();
        cli(h, "models", "add", "glm", "openrouter", "z-ai/glm-5.3", "--prices", "0.08/4.8");
        cli(h, "models", "add", "kimi", "openrouter", "moonshotai/kimi-k3", "--prices", "0.3/12");
        String out = cli(h, "models", "auto");
        assertTrue(out.contains("candidates:"), out);
        assertFalse(out.contains("All rows: researchzosho models --all"), "the table of local models is `researchzosho models` with no word after it");
    }

    private String cli(Path h, String... words) throws Exception {
        String realHome = System.getProperty("user.home");
        PrintStream was = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setProperty("user.home", h.toString());
        QuietHome.quiet(h);
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        try {
            String[] args = new String[words.length + 1];
            args[0] = "librarian";
            System.arraycopy(words, 0, args, 1, words.length);
            LibrarianCli.run(args, "http://127.0.0.1:1", "m");
        } finally { System.setOut(was); System.setProperty("user.home", realHome); }
        return out.toString(StandardCharsets.UTF_8);
    }
}
