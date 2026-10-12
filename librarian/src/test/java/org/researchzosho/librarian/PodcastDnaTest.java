package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.librarian.dna.Dna;
import org.researchzosho.librarian.dna.PodcastDna;
import org.researchzosho.librarian.dna.Prefs;
import org.researchzosho.tools.Podcasts;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The second kind: a podcast episode's DNA from the transcript the library holds, through the same layers and the same pipeline; the show as the maker. */
class PodcastDnaTest {

    @AfterEach void reset() { Dna.useDir(null); Prefs.useDir(null); }

    /** A saved transcript of about 1,500 spoken words with a time stamp a line. */
    static void transcript(LibraryStore store, String audio, String title, String date, String word) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 150; i++) b.append('[').append(String.format("%d:%02d:%02d", i / 3600, (i * 20 / 60) % 60, (i * 20) % 60)).append("] ").append("We talked about the ").append(word).append(" and what it meant for the town, said the host, and the guest laughed and agreed it was so.\n");
        RawCapture.capture(store, audio, b.toString(), title, Podcasts.BY, "", date);
    }

    @Test void anEpisodeIsReadCountedLabelledAndComparedWithTheShowSetAside(@TempDir Path tmp) throws Exception {
        Dna.useDir(tmp.resolve("dna")); Prefs.useDir(tmp.resolve("prefs"));
        LibraryStore store = new LibraryStore(tmp.resolve("lib")); store.init();
        RawCapture.capture(store, "https://example.org/page", "a web page, not a transcript", "A page", "someone", "");
        transcript(store, "https://cdn.example.org/show/ep1.mp3", "The Harbour Hour — Episode One: Boats", "2026-09-01", "boats");
        transcript(store, "https://cdn.example.org/show/ep2.mp3", "The Harbour Hour — Episode Two: Nets", "2026-09-08", "nets");
        transcript(store, "https://cdn.example.org/other/ep9.mp3", "Ledger Talk — Episode Nine: Books", "2026-09-09", "ledgers");
        List<PodcastDna.Saved> saved = PodcastDna.saved(store);
        assertEquals(3, saved.size(), "the page is not a transcript");
        PodcastDna.Saved one = saved.stream().filter(s -> s.title().contains("Episode One")).findFirst().orElseThrow();
        assertEquals("The Harbour Hour", one.show()); assertEquals("Episode One: Boats", one.episode()); assertEquals("2026-09-01", one.published());
        assertEquals(2980, PodcastDna.seconds(one.body()));
        assertFalse(PodcastDna.spoken(one.body()).contains("[0:00:00]"));
        ObjectNode d = PodcastDna.measure(one);
        assertEquals("podcast", d.path("kind").asText()); assertEquals("The Harbour Hour", d.path("record").path("authors").asText()); assertEquals(2980, d.path("record").path("seconds").asInt());
        assertTrue(d.path("measured").path("features").path("words").asInt() > 1000);
        assertTrue(d.path("measured").path("features").has("words_per_minute"));
        AtomicInteger calls = new AtomicInteger();
        for (PodcastDna.Saved s : saved) PodcastDna.label(s, ShelfDnaTest.stub(calls), null);
        assertEquals(3, Dna.labelled(Dna.PODCAST).size());
        assertEquals(6, calls.get(), "no excerpt under 5,000 words, one window each over about 3,000 words: two calls an episode");
        assertEquals("3 of the 3 transcripts held have their DNA counted and 3 are labelled; tonight 0 labelled and 0 counted", PodcastDna.nightly(store, 60_000, ShelfDnaTest.stub(calls), () -> false));
        PodcastDna.Found f = PodcastDna.like("episode one", 5);
        assertTrue(f.ok(), f.message());
        assertEquals(List.of("Episode Nine: Books"), f.result().rows().stream().map(m -> m.title()).toList(), "the same show's other episode is set aside, the other show's compared");
        assertEquals(1, f.result().side().size());
        String text = PodcastDna.text(f.result());
        assertTrue(text.startsWith("Episodes like \"Episode One: Boats — The Harbour Hour\" among the 2 labelled on the transcripts the library holds"), text);
        assertTrue(text.contains("Set aside, from the same maker or series (the old way of finding more): Episode Two: Nets."), text);
        assertEquals("No labelled episode carries the words \"nothing here\" in its show or title.", PodcastDna.like("nothing here", 5).message());
        Dna.useDir(tmp.resolve("empty"));
        assertEquals("no podcast transcript is held yet", PodcastDna.nightly(new LibraryStore(tmp.resolve("lib2")), 60_000, null, () -> false).replace("no podcast transcript is held yet", "no podcast transcript is held yet"));
    }
}
