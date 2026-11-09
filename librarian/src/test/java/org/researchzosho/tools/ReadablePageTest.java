package org.researchzosho.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A page's text is the page's own content: not the site's menus, and not what a tag keeps in its attributes. */
class ReadablePageTest {

    @Test void aTagThatCarriesKilobytesInAnAttributeIsStrippedWithoutOverflowingTheStack() {
        StringBuilder big = new StringBuilder("<p>before</p><div data-map=");
        for (int i = 0; i < 60_000; i++) big.append("0.0123,45.678;");   // a county's map page keeps its geometry in an attribute, unquoted: one character per turn of the old loop
        big.append(" class=x>inside</div><!-- ").append("a comment of many words ".repeat(20_000)).append("--><p>after</p>");
        String text = WebFetchTool.readable(big.toString());
        assertTrue(text.contains("before") && text.contains("inside") && text.contains("after"), text.length() > 200 ? text.substring(0, 200) : text);
        assertFalse(text.contains("data-map") || text.contains("0.0123,45.678"), "the attribute's data is not the page's text");
    }


    @Test
    void theSitesMenusAndATagsAttributesAreNotThePagesText() {
        String body = "x".repeat(300);
        String html = "<html><head><title>T</title></head><body><header><a href=\"#c\">Skip to content</a><nav><ul><li>Main menu</li></ul></nav></header>"
                + "<main id=\"content\"><h1>Morita Isamu</h1><span typeof=\"mw:Transclusion\" data-mw='{\"parts\":[{\"wt\":\"born > 1850 {{JPN}}\"}]}'>1852</span>"
                + "<table><tr><th>Children</th><td>Morita Kenzo</td></tr></table><p>" + body + " and 3 < 4.</p><nav>In other languages</nav></main>"
                + "<footer>Privacy policy</footer></body></html>";
        String text = WebFetchTool.readable(html);
        assertTrue(text.startsWith("Morita Isamu"), text);
        assertTrue(text.contains("Children | Morita Kenzo |") && text.contains("1852") && text.contains("3 < 4"), text);
        for (String noise : new String[]{"Skip to content", "Main menu", "Privacy policy", "In other languages", "JPN", "wt"}) assertFalse(text.contains(noise), noise + " in: " + text);
        assertTrue(WebFetchTool.readable("<main>short</main><p>the page is out here</p>").contains("the page is out here"), "an empty <main> is not taken for the page");
    }
}
