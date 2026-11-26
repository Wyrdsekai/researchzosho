package org.researchzosho.librarian;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A model host registered in one place and owned from another (CodeZaiku's row of 2026-11-24, brought here): api.moonshot.ai, where the
 * host words moonshot and kimi point, is run by Moonshot AI PTE. LTD. in Singapore by its own privacy policy, and the company's own site
 * gives its address in Beijing. Only moonshot.cn was in the table, so the international host was placed nowhere and nothing was said.
 */
class HostOwnerTest {
    String realHome;

    @BeforeEach void up(@TempDir Path home) { realHome = System.getProperty("user.home"); System.setProperty("user.home", home.toString()); Config.invalidate(); }
    @AfterEach void down() { Chrome.clear(); System.setProperty("user.home", realHome); Config.invalidate(); }

    static ModelEntries.Entry moonshot() { return new ModelEntries.Entry("kimi", "https://api.moonshot.ai/v1", "kimi-k3", null, "", 0, ""); }

    @Test
    void moonshotsInternationalHostIsRunFromSingaporeAndOwnedFromChina() throws Exception {
        assertEquals("SG", ModelPlaces.placeOfHost("api.moonshot.ai"), "the registered place");
        assertEquals("SG", ModelPlaces.placeOfHost("moonshot.ai"));
        assertEquals("CN", ModelPlaces.placeOfHost("api.moonshot.cn"), "the Chinese host stays as it was");
        assertEquals("", ModelPlaces.placeOfHost("notmoonshot.ai"), "a look-alike is not the host");
        assertEquals("https://api.moonshot.ai/v1", ModelEntries.knownHost("kimi"), "the host the words moonshot and kimi name");
        // while China is avoided and a provider owned from an avoided place is left out (both on by default), the person is told
        assertEquals("api.moonshot.ai is run by Moonshot AI PTE. LTD., a company registered in Singapore and owned by a company based in China, a place this library keeps its content away from: "
                + "what the library asks this model goes to that company. You named the host, so it is used; the setting avoid_by_owner leaves out a router's providers for their owner's place, not a host you name.",
                ModelPlaces.said(moonshot()));
        // with the owner's place let in, or no place avoided, nothing is said
        Config.set(Avoided.BY_OWNER, "off");
        assertEquals("", ModelPlaces.said(moonshot()));
        Config.set(Avoided.BY_OWNER, "");
        Config.set(Avoided.SETTING, "none");
        assertEquals("", ModelPlaces.said(moonshot()));
        Config.set(Avoided.SETTING, "");
    }

    @Test
    void theRowNamesItsSourcesAndTheDayTheyWereRead() {
        ModelPlaces.HostOwner row = ModelPlaces.ownerOfHost("api.moonshot.ai");
        assertNotNull(row);
        assertEquals("Moonshot AI PTE. LTD.", row.runBy()); assertEquals("SG", row.registered()); assertEquals("CN", row.country());
        assertEquals("2026-11-25", row.recordedOn());
        assertEquals("www.moonshot.ai/about", row.source()); assertTrue(row.says().contains("Haidian District, Beijing"));
        assertTrue(row.also().stream().anyMatch(a -> a[0].equals("platform.kimi.ai/docs/agreement/userprivacy") && a[1].contains("MOONSHOT AI PTE. LTD.") && a[1].contains("in Singapore")));
        assertSame(row, ModelPlaces.ownerOfHost("API.MOONSHOT.AI."), "by the end of the name, any case, a final dot");
    }

    @Test
    void theSentenceIsSaidInEveryLanguageTheLibrarySpeaks() {
        for (String language : List.of("de", "es", "fr", "it", "ja", "ko", "pt", "ru", "zh")) {
            Chrome.here(language);
            String said = ModelPlaces.said(moonshot());
            assertFalse(said.isEmpty() || said.startsWith("api.moonshot.ai is run by"), language + ": " + said);
            assertTrue(said.contains("api.moonshot.ai") && said.contains("Moonshot AI PTE. LTD.") && said.contains("avoid_by_owner"), language + ": " + said);
            assertFalse(said.matches("(?s).*\\{\\d\\}.*"), "every place filled: " + said);
        }
    }
}
