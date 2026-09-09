package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tiers are mechanical (host patterns), so they are pinnable — including the order of checks. */
class SourceTierTest {

    @Test
    void hostsClassify() {
        assertEquals(SourceTier.scholarly, SourceTier.of("https://koara.lib.keio.ac.jp/x.pdf"));
        assertEquals(SourceTier.scholarly, SourceTier.of("https://www.jstage.jst.go.jp/article/its/14/0/14_1406/_article/-char/en"));
        assertEquals(SourceTier.scholarly, SourceTier.of("https://arxiv.org/abs/2511.02824"));
        assertEquals(SourceTier.scholarly, SourceTier.of("https://www.scielo.br/j/ct/a/x"));
        assertEquals(SourceTier.reference, SourceTier.of("https://en.wikipedia.org/wiki/Keigo"));
        assertEquals(SourceTier.reference, SourceTier.of("https://docs.pytorch.org/audio/stable/tutorials/x.html"));
        assertEquals(SourceTier.reference, SourceTier.of("https://www.mext.go.jp/x"));
        assertEquals(SourceTier.primary, SourceTier.of("https://huggingface.co/NTQAI/wav2vec2-large-japanese"));
        assertEquals(SourceTier.primary, SourceTier.of("https://github.com/m-bain/whisperX/blob/main/x.py"));
        assertEquals(SourceTier.blog, SourceTier.of("https://note.com/maika_takai/n/n40a512aad9da"));
        assertEquals(SourceTier.blog, SourceTier.of("https://tokyoexplained.wordpress.com/2015/08/x"));
        assertEquals(SourceTier.blog, SourceTier.of("https://blog.example.org/post"));
        assertEquals(SourceTier.forum, SourceTier.of("https://www.reddit.com/r/x"));
        assertEquals(SourceTier.forum, SourceTier.of("https://japanese.stackexchange.com/q/1"),
                "a stackexchange subdomain is a forum, not reference");
        assertEquals(SourceTier.personal, SourceTier.of("file:///home/me/paper.pdf"));
        assertEquals(SourceTier.web, SourceTier.of("https://veqta.com/honorific-overload"));
        assertEquals(SourceTier.web, SourceTier.of(""));
        assertEquals(SourceTier.web, SourceTier.of("not a url"));
    }

    @Test
    void strongestWins() {
        var sources = List.of(
                new Finding.Source("https://note.com/x", "n/a", "s"),
                new Finding.Source("https://www.jstage.jst.go.jp/x", "n/a", "s"));
        assertEquals(SourceTier.scholarly, SourceTier.strongest(sources));
        assertEquals(SourceTier.web, SourceTier.strongest(List.of()));
    }

    @Test
    void autoPromotionByTier() {
        assertTrue(SourceTier.scholarly.autoPromotes());
        assertTrue(SourceTier.reference.autoPromotes());
        assertTrue(SourceTier.primary.autoPromotes());
        assertTrue(SourceTier.personal.autoPromotes());
        assertFalse(SourceTier.blog.autoPromotes());
        assertFalse(SourceTier.forum.autoPromotes());
        assertFalse(SourceTier.web.autoPromotes());
    }
}
