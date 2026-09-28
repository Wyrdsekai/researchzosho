package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Every researchzosho command the Bedrock guide gives, and every one `bedrock use` prints, is a command the program has. */
class BedrockGuideTest {

    static String guide() throws Exception {
        for (String c : new String[]{"../docs/public/BEDROCK.md", "docs/public/BEDROCK.md", "../docs/BEDROCK.md", "docs/BEDROCK.md"}) if (Files.exists(Path.of(c))) return Files.readString(Path.of(c));
        fail("BEDROCK.md not found from " + Path.of("").toAbsolutePath());
        return "";
    }

    /** The verbs the usage lists, one at the start of each of its lines. */
    static Set<String> verbs() {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?m)^ {2}([a-z][a-z-]*)").matcher(LibrarianCli.USAGE);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** The researchzosho commands in a text that the program does not have: an unknown verb, or a service operation it does not do. */
    static List<String> missing(String text) {
        Set<String> verbs = verbs();
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("researchzosho ([a-z][a-z-]*)(?: ([a-z][a-z-]*))?").matcher(text);
        while (m.find()) {
            if (!verbs.contains(m.group(1))) out.add(m.group());
            else if (m.group(1).equals("service") && m.group(2) != null && !List.of("install", "uninstall", "status").contains(m.group(2))) out.add(m.group());
        }
        return out;
    }

    @Test
    void whatBedrockUseSaysGivesCommandsThatExist() {
        for (Service.Os os : Service.Os.values()) {
            String said = BedrockCli.used("us.anthropic.example-v1:0", "us-east-1", "", "amazon.titan-embed-text-v2:0", os);
            assertEquals(List.of(), missing(said), os + ": " + said);
            assertTrue(said.contains("researchzosho rebuild"), said);
        }
        assertTrue(BedrockCli.used("m", "us-east-1", "", null, Service.Os.linux).contains("systemctl --user restart " + Service.NAME));
        assertTrue(BedrockCli.used("m", "us-east-1", "", null, Service.Os.macos).contains("launchctl kickstart -k gui/"));
    }

    @Test
    void everyCommandTheGuideGivesExists() throws Exception {
        assertTrue(verbs().containsAll(List.of("rebuild", "service", "bedrock")), verbs().toString());
        assertEquals(List.of(), missing(guide()), "commands in docs/public/BEDROCK.md that the program does not have");
    }
}
