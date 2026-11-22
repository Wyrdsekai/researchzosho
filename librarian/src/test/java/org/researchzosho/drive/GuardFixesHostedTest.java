package org.researchzosho.drive;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hosted model after the regression checks of 0.8.1 ({@link GuardChecksHostedTest}): the same mechanisms with other inputs. Which
 * host a model server is, is decided by its name exactly; every place in the sources that sends a completion adds the router's
 * list of providers to leave out in the one place that adds it; and a tool call the server gave no id gets one of its own.
 */
class GuardFixesHostedTest {

    /** A host is the one named: the name or a name under it, capitals and the dot that ends a full name aside; for every hosted service the client knows by name, not the router alone. */
    @Test
    void aHostIsTheOneNamedByItsNameAndNotByItsLastLetters() {
        assertTrue(DriveClient.hostIs("api.groq.com", "api.groq.com") && DriveClient.hostIs("EU.API.Groq.com.", "api.groq.com") && DriveClient.hostIs("generativelanguage.googleapis.com", "googleapis.com"));
        for (String other : List.of("evilapi.groq.com", "notgoogleapis.com", "googleapis.com.example.org", "openrouter.ai.example.org", "xopenrouter.ai", "", " "))
            for (String name : List.of("api.groq.com", "googleapis.com", "openrouter.ai")) assertFalse(DriveClient.hostIs(other, name), other + " is not " + name);
        assertFalse(DriveClient.hostIs(null, "openrouter.ai"));
        // the client's own questions about a host, through the same test
        assertTrue(DriveClient.hostedApi("https://generativelanguage.googleapis.com/v1beta/openai") && DriveClient.hostedApi("https://api.deepseek.com./v1") && DriveClient.hostedApi("https://router.huggingface.co/v1"));
        for (String lookAlike : List.of("https://notgoogleapis.com/v1", "https://my-huggingface.co/v1", "https://fakeapi.deepseek.com/v1", "https://xapi.x.ai/v1", "https://example.org/v1?host=openrouter.ai"))
            assertFalse(DriveClient.hostedApi(lookAlike), lookAlike + " is taken for a hosted service the client knows by name");
        assertTrue(DriveClient.openRouter("https://openrouter.ai./api/v1") && !DriveClient.openRouter("https://openrouter.ai.evil.example/api/v1") && !DriveClient.openRouter("http://127.0.0.1:1" + Gated.PATH + "https/xopenrouter.ai/api/v1") && DriveClient.openRouter("http://127.0.0.1:1" + Gated.PATH + "https/openrouter.ai/api/v1"));
    }

    static Path src() { return Files.exists(Path.of("src")) ? Path.of("src") : Path.of("librarian/src"); }

    /**
     * Read from the sources, as the request guard's own test reads them for clients: every place that builds a request for a
     * completion (an address ending in /chat/completions) passes its body through the one place that adds the list of providers a
     * router is to leave out ({@code routed}, or {@code hostedShape}, which calls it). A sender written later that builds its body by
     * hand fails this until it does; the hello of setup was one, and went without the list until 0.8.1.
     */
    @Test
    void everyPlaceThatSendsACompletionAddsTheListOfProvidersToLeaveOut() throws IOException {
        List<String> senders = new ArrayList<>(), bare = new ArrayList<>();
        Set<String> files = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(src().resolve("main").resolve("java"))) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String[] lines = Files.readString(p).split("\n", -1);
                String name = src().resolve("main").resolve("java").relativize(p).toString().replace('\\', '/').replaceFirst("^org/researchzosho/", "");
                Matcher m;
                for (int i = 0; i < lines.length; i++) {
                    m = Pattern.compile("\\+\\s*\"/chat/completions\"").matcher(lines[i]);
                    if (!m.find()) continue;
                    senders.add(name + ":" + (i + 1)); files.add(name);
                    boolean shaped = false;
                    // the body is shaped a few lines before the request is built, or as it is handed to it in the same statement
                    for (int k = Math.max(0, i - 40); k <= Math.min(lines.length - 1, i + 6) && !shaped; k++) shaped = lines[k].contains("routed(") || lines[k].contains("hostedShape(");
                    if (!shaped) bare.add(name + ":" + (i + 1) + " " + lines[i].strip());
                }
            }
        }
        System.out.println("[fix] 8 places in the sources that send a completion: " + senders.size() + " in " + files + "; without the place that adds the list: " + bare.size());
        assertEquals(Set.of("drive/DriveClient.java", "librarian/Crews.java", "librarian/Setup.java"), files, "a new sender of completions: see that its body passes DriveClient.routed, then name it here");
        assertTrue(senders.size() >= 7, "the scan found the senders (the control): " + senders);
        assertEquals(List.of(), bare, "a completion is built without the list of providers a router is to leave out");
    }

    /** A tool call the server gave no id (one recovered from a reply's text, a stream without ids) gets an id no other call has. */
    @Test
    void aCallTheServerGaveNoIdGetsOneOfItsOwn() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 2000; i++) { String id = DriveClient.ownId(i % 2 == 0 ? "recovered" : "call"); assertTrue(id.matches("(recovered|call)_[0-9a-z]+") && id.length() <= 40, id); ids.add(id); }
        assertEquals(2000, ids.size(), "an id was given twice");
    }
}
