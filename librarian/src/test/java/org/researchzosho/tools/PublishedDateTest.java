package org.researchzosho.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PublishedDateTest {
    @Test
    void thePageOwnDateIsReadFromItsHead() {
        assertEquals("2024-03-12", WebFetchTool.publishedDate("<html><head><meta property=\"article:published_time\" content=\"2024-03-12T09:00:00+09:00\"></head>"));
        assertEquals("2021-11-05", WebFetchTool.publishedDate("<script type=\"application/ld+json\">{\"@type\":\"NewsArticle\",\"datePublished\":\"2021-11-05\"}</script>"));
        assertEquals("2019-07-01", WebFetchTool.publishedDate("<meta name=\"citation_publication_date\" content=\"2019/07/01\">"));
        assertEquals("2023-01-30", WebFetchTool.publishedDate("<p>Posted <time datetime=\"2023-01-30T10:00\">yesterday</time></p>"));
        assertEquals("", WebFetchTool.publishedDate("<html><body>no date here</body></html>"));
        assertEquals("", WebFetchTool.publishedDate("<meta name=\"date\" content=\"2099-01-01\">"), "a date in the future is not a date");
    }
}
