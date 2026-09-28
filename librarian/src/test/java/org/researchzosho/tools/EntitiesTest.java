package org.researchzosho.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Character references in a page's text become the characters they stand for, once. */
class EntitiesTest {
    @Test
    void numericHexAndNamedReferencesAreDecodedOnce() {
        assertEquals("a project's popularity", Entities.decode("a project&#x27;s popularity"));
        assertEquals("it’s — “quoted” …", Entities.decode("it&#8217;s &mdash; &ldquo;quoted&rdquo; &hellip;"));
        assertEquals("Tom & Ann <b>", Entities.decode("Tom &amp; Ann &lt;b&gt;"));
        assertEquals("&lt;", Entities.decode("&amp;lt;"), "one pass: an escaped reference stays a reference");
        assertEquals("森田 家", Entities.decode("&#26862;&#30000;&nbsp;&#x5BB6;"));
        assertEquals("&unknown; &#xFFFFFFF; R&D", Entities.decode("&unknown; &#xFFFFFFF; R&D"), "what names no character is left as it is");
        assertNull(Entities.decode(null));
    }

    @Test
    void aSavedPageReadsWithItsApostrophes() {
        String html = "<html><head><title>Ask HN: Is your project&#x27;s traction real?</title></head><body><p>a project&#x27;s popularity has almost zero correlation</p></body></html>";
        assertEquals("Ask HN: Is your project's traction real?", WebFetchTool.pageTitle(html));
        assertTrue(WebFetchTool.readable(html).contains("a project's popularity has almost zero correlation"), WebFetchTool.readable(html));
    }
}
