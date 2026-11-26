package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The phone page's look (2026-11-25, the owner: "make default dark mode … use the purple theme we did for the webapp"): the web app's
 * palette from the one place both stylesheets read, dark unless the person chose light whatever the phone's own setting, the choice
 * set before the page is drawn by the page's one script in the head, and the bars' colour said. Nothing starts: the page is text.
 */
class PhoneLookTest {

    @Test
    void thePhonePageWearsTheWebAppsPaletteDarkUnlessLightIsChosenAndItIsSetBeforeThePageIsDrawn() {
        String css = PhonePage.CSS, page = PhonePage.html(), js = PhonePage.JS;
        assertTrue(css.startsWith(":root{color-scheme:dark;" + Pages.NIGHT + ";"), "dark is the page's own look: " + css);
        assertTrue(css.contains(":root[data-theme=light]{color-scheme:light;" + Pages.DAY + "}"), "light only when chosen");
        assertFalse(css.contains("prefers-color-scheme"), "the phone's own setting does not choose the look");
        Matcher hex = Pattern.compile("#[0-9a-fA-F]{3,8}\\b").matcher(css.replace(Pages.NIGHT, "").replace(Pages.DAY, ""));
        boolean byHand = hex.find();
        assertFalse(byHand, "every colour is the web app's token, none written by hand: " + (byHand ? hex.group() : ""));
        // the web app's stylesheet is made of the same two, so the two cannot drift
        assertTrue(Pages.CSS.startsWith(":root{color-scheme:light;" + Pages.DAY + ";") && Pages.CSS.contains("{:root:not([data-theme=light]){color-scheme:dark;" + Pages.NIGHT + "}}")
                && Pages.CSS.contains(":root[data-theme=dark]{color-scheme:dark;" + Pages.NIGHT + "}"));

        // the one script, in the head before the stylesheet, and no script written into the page (the policy allows none)
        int script = page.indexOf("<script src=\"/m/phone.js\"></script>"), style = page.indexOf("<style>"), head = page.indexOf("</head>");
        assertTrue(script > 0 && script < style && style < head, page);
        assertEquals(1, page.split("<script", -1).length - 1, "one script tag, by address");
        assertTrue(page.contains("<meta name=\"color-scheme\" content=\"dark\">") && page.contains("<meta name=\"theme-color\" content=\"" + Pages.colour(Pages.NIGHT, "--paper") + "\">"), page);
        assertTrue(page.contains("data-look=\"dark\">dark</button>") && page.contains("data-look=\"light\">light</button>"), "the switch in the web app's words");
        // the remembered look is read and set at once; the rest of the script waits for the page
        int read = js.indexOf("localStorage.getItem('rz.theme')"), set = js.indexOf("look(v,false);"), waits = js.indexOf("DOMContentLoaded");
        assertTrue(read >= 0 && read < set && set < waits, js.substring(0, Math.min(900, js.length())));
        assertTrue(js.contains("'" + Pages.colour(Pages.DAY, "--paper") + "'") && js.contains("localStorage.setItem('rz.theme',v)"), "the bars follow a switch, and the choice is kept");
    }

    @Test
    void theTextAndTheControlsStandOutFromTheirGroundInBothLooks() {
        List<String> faults = new ArrayList<>();
        for (String[] look : new String[][]{{"dark", Pages.NIGHT}, {"light", Pages.DAY}}) {
            String p = look[1];
            // body text and links (4.5:1): the ink, the second ink (the quiet words), the accent (links, the tab that is on), the error
            for (String fg : new String[]{"--ink", "--ink-2", "--accent", "--dispute"})
                for (String bg : new String[]{"--paper", "--paper-2"}) at(faults, look[0], p, fg, bg, 4.5);
            at(faults, look[0], p, "--on-accent", "--accent", 4.5);     // the Send button's word, the badge's number
            at(faults, look[0], p, "--ink-3", "--paper-2", 4.5);        // the box's placeholder, on the box
            // controls (3:1): a button's and the box's edge on the page and on a turn; a run's bar on its track
            for (String bg : new String[]{"--paper", "--paper-2"}) at(faults, look[0], p, "--ink-3", bg, 3);
            at(faults, look[0], p, "--accent", "--rule", 3);
        }
        assertTrue(faults.isEmpty(), String.join("\n", faults));
    }

    private static void at(List<String> faults, String look, String palette, String fg, String bg, double least) {
        double r = contrast(Pages.colour(palette, fg), Pages.colour(palette, bg));
        if (r < least) faults.add(look + ": " + fg + " on " + bg + " is " + String.format("%.2f", r) + ":1, under " + least + ":1");
    }

    /** WCAG 2's contrast ratio of two colours written #rrggbb. */
    static double contrast(String a, String b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    private static double luminance(String hex) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
            c[i] = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }
}
