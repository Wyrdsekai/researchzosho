package org.researchzosho.tools;

import org.researchzosho.librarian.Researcher;
import org.researchzosho.librarian.Video;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Channels like one: the phrasings the model writes from the channel's own words, every phrasing searched two ways, the tally, the seed left out. */
class ChannelsLikeToolTest {

    private static final ObjectMapper J = new ObjectMapper();
    private static final String SEED = "UCseedseedseedseedseed00";
    @TempDir Path home;
    private String realHome;
    private Video.Runner realRunner;
    private BooleanSupplier realProbe;
    private Supplier<Researcher.Drive> realDrives;
    final List<String> ran = new ArrayList<>();
    final List<String> asked = new ArrayList<>();

    /** A drive that writes three phrasings, and records what it was asked. */
    final Researcher.Drive drive = new Researcher.Drive() {
        @Override public ObjectNode chat(ArrayNode messages, ArrayNode tools, int maxTokens, String toolChoice) { throw new UnsupportedOperationException(); }
        @Override public String classify(ArrayNode messages, int maxTokens) {
            asked.add(messages.get(0).path("content").asText());
            return "1. iaido kata demonstration\n- koryu iaido school\n\"Iaido Archives okuiai\"\nmuso shinden ryu\n";
        }
        @Override public int contextWindow() { return 32_000; }
    };

    @BeforeEach
    void anInstalledHelperOfItsOwn() throws Exception {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Path dir = home.resolve(".researchzosho").resolve("video");
        Files.createDirectories(dir.resolve("venv").resolve("bin"));
        Files.writeString(dir.resolve("wgcf-profile.conf"), "[Interface]\n");
        Files.writeString(dir.resolve("warp.env"), "VPN_TYPE=wireguard\n");
        Files.writeString(dir.resolve("venv").resolve("bin").resolve("yt-dlp"), "#!/bin/sh\n");
        realRunner = Video.runner; realProbe = Video.providerProbe; realDrives = ChannelsLikeTool.drives;
        Video.providerProbe = () -> true;
        Video.paceMs = 0; // a stand-in for yt-dlp needs no pacing
        ChannelsLikeTool.drives = () -> drive;
        Video.runner = (cmd, t) -> {
            String line = String.join(" ", cmd);
            ran.add(line);
            if (line.endsWith("/channel/" + SEED + "/videos"))
                return new Video.Result(0, "{\"channel_id\":\"" + SEED + "\",\"channel\":\"Iaido Archives\",\"channel_follower_count\":2380,\"description\":\"Collection of iaido videos: koryu kata, seitei and okuiai.\","
                        + "\"entries\":[{\"title\":\"Okuiai 11 — iaido kata\"},{\"title\":\"Seitei iaido kata 1 to 12\"},{\"title\":\"Koryu iaido: Omori ryu\"},{\"title\":\"Okuiai kata, standing\"}]}");
            if (line.contains("sp=EgIQAg")) {
                // the channel search: the dojo for every phrasing, the seed itself, the kitchen for the kata one
                String kitchen = line.contains("kata") ? ",{\"id\":\"UCcook\",\"title\":\"Kata's Kitchen\",\"channel_follower_count\":90000,\"description\":\"weeknight dinners and baking\",\"url\":\"https://www.youtube.com/channel/UCcook\"}" : "";
                return new Video.Result(0, "{\"entries\":["
                        + "{\"id\":\"" + SEED + "\",\"title\":\"Iaido Archives\",\"channel_follower_count\":2380,\"description\":\"Collection of iaido videos\"},"
                        + "{\"id\":\"UCdojo\",\"title\":\"Iaido in Amersfoort\",\"channel_follower_count\":1570,\"description\":\"a small iaido dojo: seitei kata and koryu\",\"url\":\"https://www.youtube.com/channel/UCdojo\"}" + kitchen + "]}");
            }
            if (line.contains("ytsearch20:"))
                // the video search: a club nobody's channel search names, found through its videos by two phrasings
                return new Video.Result(0, "{\"entries\":[{\"id\":\"v1\",\"title\":\"Okuiai kata study night\",\"channel\":\"Kendo and Iaido Club\",\"channel_id\":\"UCkendo\",\"channel_url\":\"https://www.youtube.com/channel/UCkendo\"}"
                        + (line.contains("koryu") || line.contains("muso") ? "" : ",{\"id\":\"v2\",\"title\":\"Baking day\",\"channel\":\"Kata's Kitchen\",\"channel_id\":\"UCcook\"}") + "]}");
            return new Video.Result(1, "ERROR: unexpected: " + line);
        };
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", realHome);
        Video.runner = realRunner; Video.providerProbe = realProbe; Video.paceMs = Video.PACE_MS; ChannelsLikeTool.drives = realDrives;
    }

    @Test
    void theModelWritesThePhrasingsFromTheChannelsOwnTextAndItsNameIsNotSearchedFor() {
        ChannelsLikeTool.Seed seed = ChannelsLikeTool.seed("https://www.youtube.com/channel/" + SEED);
        assertNotNull(seed);
        assertEquals("Iaido Archives", seed.name()); assertEquals(2380, seed.subscribers()); assertEquals(4, seed.titles().size());
        List<String> q = ChannelsLikeTool.phrasings(seed, drive);
        assertEquals(List.of("iaido kata demonstration", "koryu iaido school", "muso shinden ryu"), q, "numbering, bullets and quotes stripped; the line that is the seed's own name dropped");
        assertTrue(asked.get(0).contains("DESCRIPTION:\nCollection of iaido videos") && asked.get(0).contains("- Okuiai 11 — iaido kata") && asked.get(0).contains("OTHER channels"), asked.get(0));
        assertTrue(ran.get(0).contains("--playlist-end 30 --dump-single-json https://www.youtube.com/channel/" + SEED + "/videos"), ran.get(0));
    }

    @Test
    void withoutAModelThePhrasingsAreTheTitlesWordPairs() {
        ChannelsLikeTool.Seed seed = new ChannelsLikeTool.Seed(SEED, "Iaido Archives", "", 1, "", List.of("Okuiai 11 — iaido kata", "Seitei iaido kata 1 to 12", "Koryu iaido: Omori ryu", "Okuiai kata, standing", "Seitei iaido kata 12"));
        List<String> q = ChannelsLikeTool.phrasings(seed, null);
        assertEquals("kata okuiai seitei", q.get(q.size() - 1), "the three most used single words last: " + q);
        assertTrue(q.contains("okuiai kata") && q.contains("seitei kata"), "pairs used twice or more, the seed's own name ('iaido') out: " + q);
        assertTrue(q.stream().noneMatch(s -> s.contains("iaido") || s.contains("archives")), q.toString());
    }

    @Test
    void everyPhrasingIsSearchedTwoWaysAndTheTallyRanksTheChannelsSmallOrNot() {
        String out = new ChannelsLikeTool().execute(J.createObjectNode().put("channel", SEED).put("limit", 10));
        assertTrue(out.startsWith("channels like Iaido Archives (https://www.youtube.com/channel/" + SEED + "), by how many of 3 searches found each; searched: iaido kata demonstration / koryu iaido school / muso shinden ryu"), out);
        int dojo = out.indexOf("Iaido in Amersfoort"), kendo = out.indexOf("Kendo and Iaido Club"), cook = out.indexOf("Kata's Kitchen");
        assertTrue(dojo > 0 && kendo > 0 && cook > 0, out);
        assertTrue(dojo < cook && kendo < cook, "the two found by every phrasing come before the kitchen found by one, whatever its 90,000 subscribers: " + out);
        assertTrue(out.contains("Iaido in Amersfoort — 1,570 subscribers · found by 3 of 3 (channel search)"), out);
        assertTrue(out.contains("Kendo and Iaido Club · found by 3 of 3 (video search)"), "found only through its videos, no subscriber count known: " + out);
        assertTrue(out.contains("Kata's Kitchen — 90,000 subscribers · found by 1 of 3 (channel and video search)"), out);
        assertFalse(out.substring(out.indexOf("<<<")).contains(". Iaido Archives"), "the seed itself is not among its own likes: " + out);
        assertEquals(1 + 3 * 2, ran.size(), "one listing of the seed, then a channel search and a video search per phrasing: " + ran);
        assertTrue(ran.get(1).contains("--playlist-end 40 https://www.youtube.com/results?search_query=iaido+kata+demonstration&sp=EgIQAg%253D%253D"), "two pages of channels: " + ran.get(1));
        assertTrue(ran.get(2).endsWith("ytsearch20:iaido kata demonstration"), ran.get(2));
    }

    @Test
    void wordOverlapAndWhatIsNotAChannel() {
        assertEquals(1.0, ChannelsLikeTool.wordOverlap("iaido kata okuiai seitei", "okuiai kata"), 1e-9);
        assertEquals(0.0, ChannelsLikeTool.wordOverlap("iaido kata", "weeknight dinners"), 1e-9);
        assertEquals("kata, okuiai", ChannelsLikeTool.shared("iaido kata okuiai", "the kata and the okuiai, and dinners"));
        assertTrue(new ChannelsLikeTool().execute(J.createObjectNode().put("channel", "https://www.youtube.com/watch?v=Q_ZtgMnPsvw")).startsWith("ERROR: not a channel"));
    }
}
