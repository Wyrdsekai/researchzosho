package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.profiles.PodcastsProfile;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The podcasts field joins a run by the question's words and sends the workers to the episodes' own words (0.5.5). */
class PodcastsProfileTest {

    @Test
    void itJoinsAQuestionAboutAPodcastAndNotAnOrdinaryOne(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        assertNotNull(Profiles.named("podcasts"), "the field is registered");
        List<Profile> on = Fields.forRun(store, new Researcher.Ask("What are Scott Galloway's (Pivot podcast host) thoughts on the AI boom?", "broad", 0, List.of()));
        assertTrue(on.stream().anyMatch(p -> p.name().equals("podcasts")), "a question naming a podcast host: " + on.stream().map(Profile::name).toList());
        List<Profile> off = Fields.forRun(store, new Researcher.Ask("When was lead paint banned in the United States?", "broad", 0, List.of()));
        assertFalse(off.stream().anyMatch(p -> p.name().equals("podcasts")), "an ordinary question: " + off.stream().map(Profile::name).toList());
        PodcastsProfile p = new PodcastsProfile();
        assertTrue(p.applies("ポッドキャストで何と言ったか") && p.applies("the episode where she explains it") && !p.applies("a question about radio"));
        assertTrue(p.register().contains("podcast_search") && p.register().contains("kind=transcript") && p.register().contains("time"));
        assertTrue(p.planRules().contains("podcast_search") && p.criticRules().contains("transcript") && p.writerRules().contains("time"));
        assertFalse(p.joinsOnlyWhenAsked(), "on by itself, like the YouTube field");
    }
}
