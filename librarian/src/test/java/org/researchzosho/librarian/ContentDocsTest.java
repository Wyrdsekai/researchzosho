package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The guide, the security page, the protocol and the 0.5.0 changelog say what the content checks do now. */
class ContentDocsTest {

    static String doc(String name) throws Exception {
        for (String c : new String[]{"../docs/public/" + name, "docs/public/" + name, "../docs/" + name, "docs/" + name, "../" + name, name}) if (Files.exists(Path.of(c))) return Files.readString(Path.of(c));
        fail(name + " not found from " + Path.of("").toAbsolutePath());
        return "";
    }

    @Test
    void theGuideListsEveryYesTheLibraryAccepts() throws Exception {
        String guide = doc("LIBRARIAN_HOWTOUSE.md");
        for (String yes : Librarian.YES) assertTrue(guide.contains("`" + yes + "`"), "the guide lists the yes " + yes);
        assertFalse(guide.contains("Only `y` or `yes` lets it in."), "it is not only y or yes");
    }

    @Test
    void aFailedDownloadKeepsTheListOnDiskAndThePersonsPagesAreSavedUnchecked() throws Exception {
        for (String name : List.of("LIBRARIAN_HOWTOUSE.md", "SECURITY.md")) {
            String d = doc(name);
            assertTrue(d.contains("the list already on disk goes on") || d.contains("the list already on disk goes on\n  being used"), name + " says the list on disk goes on being used");
            assertFalse(d.contains("and whenever a download fails, a smaller list") || d.contains("and whenever it fails,\n  a smaller list"), name + " no longer says a failed download goes back to the small list");
        }
        String protocol = doc("LIBRARY_PROTOCOL.md");
        assertTrue(protocol.contains("(no model, except the page check on a url, below)"), "library_add is not said to need no model");
        assertTrue(protocol.contains("| `confirm` | -32007 |"));
        assertTrue(doc("LIBRARIAN_HOWTOUSE.md").contains("not checked yet: no\n  model answered"), "the guide says how a page nothing could check is marked");
        String changelog = ChangelogTest.release(ChangelogTest.changelog(), "0.5.0");
        assertFalse(changelog.contains("is not saved: start the model and add it again"), "the changelog says what the program does now");
        assertFalse(changelog.contains("a program gets `state: not_started`"));
        assertTrue(changelog.contains("the new error `confirm`"));
    }
}
