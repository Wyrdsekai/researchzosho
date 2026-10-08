package org.researchzosho.librarian.profiles;

import org.researchzosho.librarian.Profile;
import org.researchzosho.librarian.Vocabulary;

import java.util.List;

/**
 * The podcasts field (0.5.5): a question about what someone says on a show, about a show, or about a host or a guest. Its rules send the
 * workers to the person's own words — the episodes' transcripts, through {@code podcast_search} — before articles that quote them, and have
 * every such claim cited by the episode and the time. It joins a run by the question's words, as the YouTube field does; the tool itself is
 * in every run. Measured before it existed (2026-10-08): two runs on a podcast host's views made ninety web calls and none to the tool.
 */
public final class PodcastsProfile implements Profile {

    @Override public String name() { return "podcasts"; }

    @Override public String description() { return "what people say on podcasts: the episodes' own words, with the time, before articles about them; shows found by fit, not by charts"; }

    @Override public List<Vocabulary.Term> predicates() {
        return List.of(
                new Vocabulary.Term("hosts", "hosts", List.of("presents", "is the host of", "co-hosts"), ""),
                new Vocabulary.Term("guest-on", "was a guest on", List.of("appeared on", "was interviewed on", "spoke on"), ""),
                new Vocabulary.Term("said-on", "said on", List.of("argued on", "claimed on", "stated on the show"), ""));
    }

    /** Words that mean a question is about a podcast, an episode, or a host as such. */
    static final List<String> WORDS = List.of(
            "podcast", "podcasts", "episode", "episodes", "cohost", "co-host", "cohosts", "co-hosts", "podcaster",
            "ポッドキャスト", "팟캐스트", "播客", "podcast épisode", "episodio del podcast");

    @Override public boolean applies(String question) {
        String q = Vocabulary.norm(question);
        for (String w : WORDS) if (q.contains(w)) return true;
        return false;
    }

    @Override public String register() {
        return "A question about what someone says on a podcast, or about a show, its host or a guest, is answered from the episodes themselves: "
                + "podcast_search is the tool. kind=shows with the show's or the person's name gives the feed address; kind=episodes with that feed lists "
                + "the episodes, newest first, with whether a transcript is published, and query narrows them by words; kind=transcript with the feed and "
                + "the episode reads what was said, with the time, from the show's own transcript or the library's transcription of the audio (two "
                + "episodes or two hours per run, so choose the episodes by their titles and dates first); kind=people lists the episodes a person "
                + "appeared on, across shows. Read the person's own words there before articles that quote them: an article is a secondary source and "
                + "is said to be one. Cite a spoken claim by the episode's page and the time as the tool shows them, so that it can be checked at that "
                + "moment. A transcript the library made is machine-read: quote it with that said. Where the person has several shows, read each.";
    }

    @Override public String planRules() {
        return "This is a question about what is said on a podcast, or about a show, a host or a guest. Split it by the shows the person hosts or "
                + "visits and by the topics asked about, one sub-question per show and topic, each answered from the episodes' transcripts (podcast_search: "
                + "shows, then episodes, then transcript), with the dates narrowed to the period the question names. One sub-question may read what "
                + "others wrote about the person, as the secondary sources.";
    }

    @Override public String criticRules() {
        return "For a question about what someone says on a podcast the evidence is enough when at least one episode transcript was read for each "
                + "show named, within the period asked about, and every claim about what was said cites the episode and the time; a claim that rests only "
                + "on an article quoting the person is marked as second-hand. A gap worth another search names a show, an episode or a period not yet read.";
    }

    @Override public String writerRules() {
        return "Give what the person said in their own words where it matters, each with the episode (its title as a link to its page) and the time, "
                + "and the date. Separate what they said themselves from what others report of them. Where the shows are the subject, give them in a "
                + "table: show, host, what it covers, episodes and the last one's date, the feed address, why it fits.";
    }
}
