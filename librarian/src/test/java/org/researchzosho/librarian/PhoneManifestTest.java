package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The phone page's web-app manifest (2026-11-25: the owner's Android offered only a shortcut; a manifest is what makes Chrome offer
 * Install whatever its rules for a page without one): served by the door to anyone, allowed by the door's policy, its colours the
 * night of the palette, its name the program's, and the icons it names are the sizes it says, the maskable one inside the circle a
 * launcher may cut to. Nothing starts: the manifest and the icons are the program's own text and files.
 */
class PhoneManifestTest {

    @Test
    void theManifestIsTheProgramsInstalledAtTheDoorInTheNightsColoursAndThePageAndThePolicyNameIt() throws Exception {
        JsonNode m = new ObjectMapper().readTree(PhonePage.manifest());
        assertEquals("/m", m.path("id").asText());
        assertEquals("/m", m.path("start_url").asText());
        assertEquals("/m", m.path("scope").asText());
        assertEquals("standalone", m.path("display").asText());
        assertEquals("ResearchZosho", m.path("name").asText(), "the program's name: an unpaired sender may read the manifest");
        assertEquals("ResearchZosho", m.path("short_name").asText());
        String night = Pages.colour(Pages.NIGHT, "--paper");
        assertEquals(night, m.path("background_color").asText(), "the installed app's first screen is the page's own night");
        assertEquals(night, m.path("theme_color").asText());
        assertEquals("#16121c", night, "the palette's night, as the owner saw it");

        String page = PhonePage.html();
        assertTrue(page.contains("<link rel=\"manifest\" href=\"/m/app.webmanifest\">") && page.indexOf("rel=\"manifest\"") < page.indexOf("</head>"), page);
        assertEquals("manifest", PhoneDoor.routeOf("GET", "/m/app.webmanifest"));
        assertEquals("anyone", PhoneDoor.ROUTES.get("manifest")[0]);
        assertTrue(PhoneDoor.POLICY.contains("manifest-src 'self'"), "the web app's default-src 'none' would refuse the manifest: " + PhoneDoor.POLICY);
        assertFalse(Pages.POLICY.contains("manifest-src"), "the web app's own pages name no manifest and keep their policy");
    }

    @Test
    void theIconsAreTheSizesTheManifestSaysOpaqueAndTheMaskableMarkIsInsideTheCircle() throws Exception {
        JsonNode m = new ObjectMapper().readTree(PhonePage.manifest());
        Map<String, String> purposes = new TreeMap<>();
        List<String> faults = new ArrayList<>();
        for (JsonNode i : m.path("icons")) {
            String src = i.path("src").asText(), sizes = i.path("sizes").asText();
            purposes.merge(i.path("purpose").asText() + " " + sizes, src, (a, b) -> a + " " + b);
            assertTrue(PhonePage.ICONS.containsKey(src), "an icon the door serves: " + src);
            BufferedImage im = ImageIO.read(new ByteArrayInputStream(PhonePage.icon(src)));
            int n = Integer.parseInt(sizes.substring(0, sizes.indexOf('x')));
            if (im.getWidth() != n || im.getHeight() != n) faults.add(src + " is " + im.getWidth() + "x" + im.getHeight() + ", the manifest says " + sizes);
            if (im.getColorModel().hasAlpha()) faults.add(src + " has transparency: a launcher would show its corners black");
            if ("maskable".equals(i.path("purpose").asText())) {
                // outside the circle of 40 % of the size around the centre there is only the ground: plum at the top to night at the bottom
                int[] plum = {0x2a, 0x17, 0x45}, nightRgb = {0x16, 0x12, 0x1c};
                int outside = 0;
                for (int y = 0; y < n; y++)
                    for (int x = 0; x < n; x++) {
                        if (Math.hypot(x + 0.5 - n / 2.0, y + 0.5 - n / 2.0) <= 0.40 * n) continue;
                        int p = im.getRGB(x, y);
                        double t = y / (double) (n - 1);
                        int[] c = {(p >> 16) & 255, (p >> 8) & 255, p & 255};
                        for (int k = 0; k < 3; k++) if (Math.abs(c[k] - (plum[k] + (nightRgb[k] - plum[k]) * t)) > 3) { outside++; break; }
                    }
                if (outside > 0) faults.add(src + ": " + outside + " pixels of the mark lie outside the maskable safe zone");
            }
        }
        assertEquals(Map.of("any 192x192", "/m/icon-192.png", "any 512x512", "/m/icon-512.png", "maskable 512x512", "/m/icon-512-maskable.png"), purposes);
        BufferedImage touch = ImageIO.read(new ByteArrayInputStream(PhonePage.icon("/m/icon.png")));
        assertEquals(180, touch.getWidth(), "the home-screen icon the owner chose stays 180 px");
        assertTrue(faults.isEmpty(), String.join("\n", faults));
    }
}
