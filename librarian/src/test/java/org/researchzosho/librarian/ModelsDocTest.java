package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The public MODELS.md carries every measured row the `models` verb carries: the doc and the code say the same list. */
class ModelsDocTest {
    @Test
    void everyMeasuredChoiceAndInstallRowIsInTheDoc() throws Exception {
        Path doc = null;
        for (String c : new String[]{"../docs/public/MODELS.md", "../docs/MODELS.md", "docs/public/MODELS.md", "docs/MODELS.md"}) if (Files.exists(Path.of(c))) { doc = Path.of(c); break; }
        assertTrue(doc != null, "MODELS.md not found from " + Path.of("").toAbsolutePath());
        String text = Files.readString(doc, StandardCharsets.UTF_8);
        for (Models.Tier t : Models.TIERS) {
            assertTrue(text.contains("**" + t.card() + "**"), "tier heading missing: " + t.card());
            for (Models.Choice c : t.choices()) assertTrue(text.contains("`" + c.file() + "`"), "measured choice missing from MODELS.md: " + c.file());
        }
        for (ModelServer.Row r : ModelServer.ROWS) assertTrue(text.contains("`" + r.file() + "`"), "install row missing from MODELS.md: " + r.file());
    }
}
