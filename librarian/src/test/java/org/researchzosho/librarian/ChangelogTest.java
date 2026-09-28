package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The changelog says where each change belongs: what every library gets is not listed under family history, and nothing is left out. */
class ChangelogTest {

    static String changelog() throws Exception {
        for (String c : new String[]{"../docs/public/CHANGELOG.md", "docs/public/CHANGELOG.md", "../CHANGELOG.md", "CHANGELOG.md"}) if (Files.exists(Path.of(c))) return Files.readString(Path.of(c));
        fail("CHANGELOG.md not found from " + Path.of("").toAbsolutePath());
        return "";
    }

    /** The text of one release, from its heading to the next release's. */
    static String release(String all, String version) {
        int at = all.indexOf("## " + version + "\n");
        assertTrue(at >= 0, version);
        int next = all.indexOf("\n## ", at + 4);
        return all.substring(at, next < 0 ? all.length() : next);
    }

    /** The release notes of a version: docs/RELEASE_NOTES_<version>.md, which the release publishes as its text. */
    static String notes(String version) throws Exception {
        for (String c : new String[]{"../docs/RELEASE_NOTES_" + version + ".md", "docs/RELEASE_NOTES_" + version + ".md"}) if (Files.exists(Path.of(c))) return Files.readString(Path.of(c));
        // the release notes stay in the private tree; the public export has only the changelog, so there is nothing to compare
        assumeTrue(false, "RELEASE_NOTES_" + version + ".md is in the private tree only, not found from " + Path.of("").toAbsolutePath());
        return "";
    }

    @Test
    void theNewestReleaseNotesAreThatReleasesChangelogWordForWord() throws Exception {
        String all = changelog();
        int at = all.indexOf("\n## ");
        assertTrue(at >= 0);
        String version = all.substring(at + 4, all.indexOf('\n', at + 4)).strip();
        String release = release(all, version);
        String body = release.substring(release.indexOf('\n') + 1).strip();
        assertEquals("# ResearchZosho " + version + "\n\n" + body + "\n", notes(version), "docs/RELEASE_NOTES_" + version + ".md is the changelog's " + version + " section");
        for (String gone : List.of("by its rule: family words", "still offered to a run whose question names"))
            assertFalse(release.contains(gone), "the " + version + " changelog says something the program no longer does: " + gone);
    }

    @Test
    void the050ChangelogSaysGeniNeedsASignInAndThatAJobCanBeOffered() throws Exception {
        String r = release(changelog(), "0.5.0");
        assertFalse(r.contains("WikiTree, Geni and others"), "Geni is searched only after records login geni");
        assertTrue(r.contains("records login geni"), r);
        assertTrue(r.contains("`offered`"), "library_job and GET /v1/jobs can show the state offered");
    }

    @Test
    void theGeneralChangesOf050AreNotListedAsFamilyHistory() throws Exception {
        String r = release(changelog(), "0.5.0");
        int family = r.indexOf("**Family history**"), after = r.indexOf("\n**", family + 5);
        String familyPart = r.substring(family, after);
        for (String general : List.of("graph unmerge", "looked add", "`dispute` shows", "is not filed again when it comes back"))
            assertFalse(familyPart.contains(general), "listed under family history although every library has it: " + general);
        for (String general : List.of("graph unmerge", "looked add", "F-0012", "same subject, relation and object", "was cited"))
            assertTrue(r.contains(general), "the 0.5.0 changelog says: " + general);
        for (String gone : List.of("a person, not a middle", "never a person"))
            assertFalse(r.contains(gone), "a change that was taken back is not in the changelog: " + gone);
    }
}
