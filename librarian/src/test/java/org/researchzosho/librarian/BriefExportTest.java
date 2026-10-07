package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** `export --brief`: the answer alone, without the run's own checks, evidence and worker findings. */
class BriefExportTest {

    @Test
    void theBriefExportIsTheAnswerAlone(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        store.write(new Investigation("I-0001-harpsichord-tuning", "Harpsichord tuning", Finding.State.accepted, "patron:person", Instant.now().toString(), List.of(), List.of(),
                "## Question\n\nHow?\n\n## Answer\n\nBy meantone [1].\n\n## Conflicts and uncertainty\n\nNone.\n\n## Sources\n\n[1] https://example.org/h\n\n"
                + "## Cite-check\n\n1 cited sentence read.\n\n## Worker findings (fan sub-investigations, verbatim)\n\nSUB-QUESTION: how\n- a note\n"));
        String whole = new String(Export.markdown(store, "I-0001-harpsichord-tuning", null, false).bytes(), StandardCharsets.UTF_8);
        String brief = new String(Export.markdown(store, "I-0001-harpsichord-tuning", null, true).bytes(), StandardCharsets.UTF_8);
        assertTrue(whole.contains("Worker findings") && whole.contains("Cite-check"));
        assertTrue(brief.contains("By meantone [1].") && brief.contains("Conflicts and uncertainty"), brief);
        assertFalse(brief.contains("Worker findings") || brief.contains("Cite-check") || brief.contains("SUB-QUESTION"), brief);
        assertTrue(brief.length() < whole.length());
    }
}
