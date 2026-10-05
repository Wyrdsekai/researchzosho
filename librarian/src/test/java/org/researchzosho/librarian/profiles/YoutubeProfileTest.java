package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Profiles;
import org.researchzosho.librarian.Video;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** The youtube field: which questions are its, what it tells a run with and without the video helper, and that the library knows it. */
class YoutubeProfileTest {

    @Test
    void whichQuestionsAreItsOwn() {
        YoutubeProfile p = new YoutubeProfile();
        assertTrue(p.applies("Which YouTube channels teach iaido well?"));
        assertTrue(p.applies("find videos of Ohtani pitching from the centre-field camera"));
        assertTrue(p.applies("居合道の動画でおすすめのチャンネルは？"));
        assertFalse(p.applies("How did the transatlantic cable get laid?"), "an ordinary question is not the field's");
        assertFalse(p.joinsOnlyWhenAsked(), "it joins by its words, like the software field");
    }

    @Test
    void whatItTellsARunWithTheHelper() {
        BooleanSupplier was = Video.installedProbe;
        Video.installedProbe = () -> true;
        try {
            YoutubeProfile p = new YoutubeProfile();
            assertTrue(p.register().contains("never ranked on") && p.register().contains("kind=channels") && p.register().contains("&t=<seconds>s"), p.register());
            assertTrue(p.register().contains("never from their face"), "who is on screen comes from a name, not a face: " + p.register());
            assertTrue(p.planRules().contains("small and the recent channels") && p.criticRules().contains("last upload") && p.writerRules().contains("not by subscribers"));
            assertFalse(p.register().contains("web_fetch"), "with the helper the run has the video tools, so the rules name them");
            assertEquals(6, p.predicates().size());
        } finally { Video.installedProbe = was; }
    }

    /** The reader opens a channel from the table: every channel is named with its address, and the table's first column links to it. */
    @Test
    void channelsCarryTheirAddress() {
        for (boolean helper : new boolean[]{true, false}) {
            BooleanSupplier was = Video.installedProbe;
            Video.installedProbe = () -> helper;
            try {
                YoutubeProfile p = new YoutubeProfile();
                assertTrue(p.writerRules().contains("[name](https://www.youtube.com/channel/<id>)"), "the table links each channel: " + p.writerRules());
                assertTrue(p.register().contains("named with its address"), p.register());
                assertTrue(p.criticRules().contains("each with its address"), p.criticRules());
            } finally { Video.installedProbe = was; }
        }
    }

    /** A library without the helper still gets the field by its words; its rules then name the tools the run has, and say how to add the rest. */
    @Test
    void withoutTheHelperTheRulesNameTheWebTools() {
        BooleanSupplier was = Video.installedProbe;
        Video.installedProbe = () -> false;
        try {
            YoutubeProfile p = new YoutubeProfile();
            assertTrue(p.applies("Which YouTube channels teach iaido well?"), "the field still joins by its words");
            String r = p.register();
            assertTrue(r.contains("web_search") && r.contains("web_fetch"), r);
            assertTrue(r.contains("researchzosho video install"), "it says how the video tools are added: " + r);
            assertFalse(r.contains("(channel_uploads)") || r.contains("(video_details)") || r.contains("kind=channels"), "it does not tell the worker to call tools it has not got: " + r);
            assertTrue(r.contains("never ranked on") && r.contains("never from their face"), "the judgment is the same: " + r);
            assertFalse(p.planRules().contains("watches it"), "no sub-question is told to watch moments it cannot read: " + p.planRules());
            assertTrue(p.criticRules().contains("when its page gives one"), p.criticRules());
            assertTrue(Video.NOT_INSTALLED_NOTE.contains("researchzosho video install") && Video.NOT_INSTALLED_NOTE.endsWith("."), Video.NOT_INSTALLED_NOTE);
        } finally { Video.installedProbe = was; }
    }

    @Test
    void theLibraryKnowsTheField() {
        Profile known = Profiles.named("youtube");
        assertNotNull(known, "registered as a profile");
        assertEquals("youtube", known.name());
    }
}
