package org.researchzosho.librarian;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The setup conversation, driven end to end with a scripted person, a fake model server and fake acts. */
class SetupTest {

    static final class FakeProbe implements Setup.Probe {
        final String answering; final List<String> ids; boolean embeds;
        FakeProbe(String answering, List<String> ids, boolean embeds) { this.answering = answering; this.ids = ids; this.embeds = embeds; }
        @Override public List<String> models(String base, String key) { return base.equals(answering) ? ids : null; }
        @Override public String chat(String base, String model, String key) { return base.equals(answering) ? "ready" : "!nothing is answering at that address"; }
        @Override public boolean embeds(String base, String key) { return embeds && base.equals(answering); }
        String searx = null; String braveKey = null;                 // what answers a search probe, if anything
        @Override public boolean searxng(String base) { return searx != null && base.equals(searx); }
        @Override public boolean brave(String key) { return braveKey != null && key.equals(braveKey); }
    }

    static final class FakeActs implements Setup.Acts {
        boolean claude = true; int installed = 0; List<String> mcpArgs;
        java.util.Set<String> hosts = null;                       // null: only claude, as before
        java.util.Map<String, List<String>> registered = new java.util.LinkedHashMap<>();
        boolean docker = false; String searxStarted = "!no docker"; String embedStarted = "!no docker"; int embedStarts = 0; boolean gpu = true;
        @Override public boolean haveDocker() { return docker; }
        @Override public boolean embedGpu() { return gpu; }
        @Override public String startSearxng(int port) { return searxStarted; }
        @Override public String startEmbed(int port) { embedStarts++; return embedStarted; }
        @Override public int installService(int port, PrintStream out) { installed++; out.println("installed (fake) on " + port); return 0; }
        @Override public boolean haveClaude() { return claude; }
        @Override public String claudeMcpAdd(List<String> args) { mcpArgs = new ArrayList<>(args); return "connected"; }
        @Override public boolean have(String command) { return hosts != null ? hosts.contains(command) : "claude".equals(command) && claude; }
        @Override public String register(String command, List<String> args) { registered.put(command, new ArrayList<>(args)); if ("claude".equals(command)) mcpArgs = new ArrayList<>(args); return "connected"; }
        @Override public String launcher() { return "/opt/rz/bin/researchzosho"; }
    }

    private static String run(Path home, String script, Setup.Probe probe, FakeActs acts, boolean yes, boolean service, boolean claude) throws Exception {
        String real = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        org.researchzosho.Config.invalidate();
        try {
            var out = new ByteArrayOutputStream();
            int rc = new Setup(new BufferedReader(new StringReader(script)), new PrintStream(out, true), probe, acts, yes).run(4649, service, claude);
            assertEquals(0, rc);
            return out.toString();
        } finally {
            System.setProperty("user.home", real);
            org.researchzosho.Config.invalidate();
        }
    }

    @Test
    void theDefaultsAloneMakeAWorkingLibraryWithAFoundModel(@TempDir Path home) throws Exception {
        var acts = new FakeActs();
        String out = run(home, "", new FakeProbe("http://localhost:11434", List.of("gemma3:27b", "qwen3"), true), acts, true, true, true);
        assertTrue(out.contains("Made a new library at " + home.resolve("researchzosho-library")), out);
        assertTrue(Files.isDirectory(home.resolve("researchzosho-library").resolve("catalog")));
        assertTrue(out.contains("Found a model server at http://localhost:11434 offering gemma3:27b, qwen3."), out);
        assertTrue(out.contains("it answered: \"ready\""), out);
        assertTrue(out.contains("Search by meaning is on"), out);
        assertEquals(1, acts.installed, "the service was installed by default");
        assertNotNull(acts.mcpArgs, "Claude Code was connected");
        assertTrue(acts.mcpArgs.contains("--transport") && acts.mcpArgs.contains("http") && acts.mcpArgs.stream().anyMatch(a -> a.startsWith("Authorization: Bearer ")), acts.mcpArgs.toString());
        String cfg = Files.readString(home.resolve(".researchzosho").resolve("config"));
        assertTrue(cfg.contains("drive = http://localhost:11434") && cfg.contains("model = gemma3:27b") && cfg.contains("embed = http://localhost:11434"), cfg);
        assertTrue(cfg.contains("library = " + home.resolve("researchzosho-library")), cfg);
        // the reader Claude Code got is on the list with write access
        var store = new LibraryStore(home.resolve("researchzosho-library"));
        assertTrue(Patrons.load(store).listed().stream().anyMatch(e -> e.level() == Patrons.Level.write), "a writer was listed for Claude Code");
        assertTrue(out.contains("Done. Your settings are in"), out);
    }

    @Test
    void aPersonCanNameARentedModelAndSkipTheService(@TempDir Path home) throws Exception {
        var acts = new FakeActs(); acts.claude = false;
        // nothing local answers; the person names a hosted server, gives a key, declines the service
        String script = String.join("\n", List.of(
                home.resolve("mylib").toString(),        // where the library lives
                "https://api.example.com",                // the server
                "sk-test-123",                            // the key
                "big-model",                              // which model
                "",                                       // no web search backend
                "",                                       // no embeddings server
                "n"                                       // no service
        )) + "\n";
        String out = run(home, script, new FakeProbe("https://api.example.com", List.of(), false), acts, false, true, true);
        assertTrue(out.contains("No model server was found on the usual local ports."), out);
        assertTrue(out.contains("it answered: \"ready\""), out);
        assertTrue(out.contains("Search is by words."), out);
        assertEquals(0, acts.installed);
        assertNull(acts.mcpArgs, "no claude on this machine: never offered");
        String cfg = Files.readString(home.resolve(".researchzosho").resolve("config"));
        assertTrue(cfg.contains("drive = https://api.example.com") && cfg.contains("api.key = sk-test-123") && cfg.contains("model = big-model") && cfg.contains("embed = off"), cfg);
        assertTrue(Files.isDirectory(home.resolve("mylib").resolve("catalog")));
    }

    @Test
    void theFirstDocumentIsShelvedAndTheFirstQuestionAnswered(@TempDir Path home) throws Exception {
        Path doc = home.resolve("lead.txt");
        Files.writeString(doc, "Lead paint was banned for residential use in the United States in 1978.");
        var acts = new FakeActs(); acts.claude = false;
        String script = String.join("\n", List.of("", "", "", "", "n", doc.toString(), "when was lead paint banned")) + "\n";
        String out = run(home, script, new FakeProbe("http://localhost:8080", List.of("m"), false), acts, false, true, true);
        assertTrue(out.contains("Shelved lead.txt."), out.substring(Math.max(0, out.length() - 900)));
        assertTrue(out.contains("1978"), "the first answer shows the document: " + out.substring(Math.max(0, out.length() - 900)));
    }

    @Test
    void withNoModelAnywhereSetupStillFinishesAndSaysWhatWaits(@TempDir Path home) throws Exception {
        var acts = new FakeActs(); acts.claude = true;
        String out = run(home, "", new FakeProbe("http://nowhere", List.of(), false), acts, true, false, true);
        assertTrue(out.contains("research runs wait for one"), out);
        assertNotNull(acts.mcpArgs);
        assertTrue(acts.mcpArgs.contains("--") && acts.mcpArgs.contains("/opt/rz/bin/researchzosho"), "no service: Claude Code gets the library over stdio: " + acts.mcpArgs);
        String cfg = Files.readString(home.resolve(".researchzosho").resolve("config"));
        assertFalse(cfg.contains("drive ="), "nothing was written for a drive that was never named: " + cfg);
    }

    @Test
    void codexAndGeminiAreConnectedTooWhenTheyAreInstalled(@TempDir Path home) throws Exception {
        var acts = new FakeActs(); acts.hosts = java.util.Set.of("claude", "codex", "gemini");
        String out = run(home, "", new FakeProbe("http://localhost:11434", java.util.List.of("m"), true), acts, true, true, true);
        assertTrue(out.contains("Claude Code is connected") && out.contains("Codex is connected") && out.contains("Gemini CLI is connected"), out);
        assertTrue(acts.registered.get("claude").contains("--transport") && acts.registered.get("claude").contains("http"), "the service is on: Claude Code goes over HTTP with a token: " + acts.registered.get("claude"));
        assertEquals(java.util.List.of("mcp", "add", "librarian", "--", "/opt/rz/bin/researchzosho", "mcp"), acts.registered.get("codex"), "codex over stdio");
        assertTrue(acts.registered.get("gemini").contains("-t") && acts.registered.get("gemini").contains("http") && acts.registered.get("gemini").stream().anyMatch(a -> a.startsWith("Authorization: Bearer ")), acts.registered.get("gemini").toString());
        assertTrue(out.contains("Other programs."), "and the lines any other MCP program needs are printed");
    }

    @Test
    void webSearchWalksTheLadderBraveThenSearxngThenTheFallback(@TempDir Path home) throws Exception {
        var probe = new FakeProbe("http://localhost:11434", java.util.List.of("m"), false);
        var acts = new FakeActs();
        String out = run(home, "", probe, acts, true, true, true);                 // no key, no SearXNG, no docker
        assertTrue(out.contains("Paste a Brave API key"), "Brave is asked for first: " + out);
        assertTrue(out.contains("Web search will use the built-in fallback: Wikipedia plus the papers"), out);
        probe.searx = "http://localhost:8888";
        out = run(home, "", probe, acts, true, true, true);
        assertTrue(out.contains("SearXNG answers at http://localhost:8888: web search goes through it."), out);
        assertFalse(out.contains("built-in fallback"), "with SearXNG the fallback is not mentioned");
        probe.searx = null; acts.docker = true; acts.searxStarted = "http://127.0.0.1:8888";
        out = run(home, "", probe, acts, true, true, true);
        assertTrue(out.contains("Starting SearXNG… it answers at http://127.0.0.1:8888."), "docker is offered and taken by default: " + out);
        java.nio.file.Files.createDirectories(home.resolve(".researchzosho"));
        java.nio.file.Files.writeString(home.resolve(".researchzosho").resolve("config"), "RESEARCHZOSHO_BRAVE_KEY = BSA-test\n");
        probe.braveKey = "BSA-test"; probe.searx = "http://localhost:8888";
        out = run(home, "", probe, acts, true, true, true);
        assertTrue(out.contains("the Brave Search API key you have works") && out.contains("the fallback behind Brave"), out);
    }

    @Test
    void withDockerAndNoEmbedderTheWizardOffersToStartOne(@TempDir Path home) throws Exception {
        // the model server does not embed; Docker is here: the offer, taken by the default answer
        var acts = new FakeActs(); acts.docker = true; acts.embedStarted = "http://127.0.0.1:" + Embed.DEFAULT_PORT;
        String out = run(home, "", new FakeProbe("http://localhost:11434", List.of("qwen3"), false), acts, true, false, false);
        assertTrue(out.contains("Start an embeddings server with Docker"), out);
        assertEquals(1, acts.embedStarts);
        assertTrue(out.contains("Search by meaning is on"), out);
        String cfg = Files.readString(home.resolve(".researchzosho").resolve("config"));
        assertTrue(cfg.contains("embed = http://127.0.0.1:" + Embed.DEFAULT_PORT), cfg);
        // Docker there but the start fails: the address question, and words only when it is left blank
        acts = new FakeActs(); acts.docker = true; acts.embedStarted = "!docker: no space left";
        out = run(home.resolve("b"), "", new FakeProbe("http://localhost:11434", List.of("qwen3"), false), acts, true, false, false);
        assertTrue(out.contains("no: docker: no space left") && out.contains("Search is by words"), out);
        // Docker but no GPU: no offer either — the CPU image is too slow to offer unasked
        acts = new FakeActs(); acts.docker = true; acts.gpu = false;
        out = run(home.resolve("d"), "", new FakeProbe("http://localhost:11434", List.of("qwen3"), false), acts, true, false, false);
        assertFalse(out.contains("Start an embeddings server with Docker"), out);
        assertEquals(0, acts.embedStarts);
        // no Docker: no offer, the address question as before
        acts = new FakeActs();
        out = run(home.resolve("c"), "", new FakeProbe("http://localhost:11434", List.of("qwen3"), false), acts, true, false, false);
        assertFalse(out.contains("Start an embeddings server with Docker"), out);
        assertEquals(0, acts.embedStarts);
        assertTrue(out.contains("Search is by words"), out);
    }
}
