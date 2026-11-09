package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A diagram in a report (0.7.0): a ```mermaid block is drawn on the page in the browser, with the Mermaid the library serves itself from
 * /static/, strict, in the page's look, its text one fold away; a page that draws one carries the script and a policy that allows it, and
 * no other page changes by a byte; a PDF or a Word file keeps the block as its text. Nobody here has a browser: the drawing itself is
 * Mermaid's, and what is pinned is the markup, the script, the policy, the file and the export.
 */
class DiagramTest {

    /** The flowchart a 27B wrote on its own into a report, which the page showed as a block of raw text (the keeper's release test, 2026-11-08). */
    static final String FLOW = "flowchart LR\n    A[Requirement / Ticket] --> B[Plan / Spec]\n    B --> C[Code]\n    C --> D[Review]\n    D --> E[Merge / Release]";
    static final String FLOW_ESCAPED = FLOW.replace(">", "&gt;");
    static final String FLOW_HTML = "<figure class=\"diagram\"><pre class=\"mermaid\">" + FLOW_ESCAPED + "</pre><details class=\"diagram-text\"><summary>Show as text</summary><pre>" + FLOW_ESCAPED + "</pre></details></figure>";

    static final String PLAIN_CSP = "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'";
    static final String DIAGRAM_CSP = "default-src 'none'; img-src 'self'; style-src 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; form-action 'self'";
    static final String SCRIPT_TAG = "<script src=\"/static/mermaid-11.17.2.min.js\"></script>";

    static HttpResponse<String> get(HttpClient c, String url) throws Exception { return c.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()); }

    @Test
    void aMermaidFenceBecomesAPreTheBrowserDrawsWithItsTextBehindAFold() {
        assertEquals("<h3>Answer</h3><p>The flow of a change:</p>" + FLOW_HTML + "<p>Each step hands to the next.</p>",
                Pages.md("## Answer\n\nThe flow of a change:\n\n```mermaid\n" + FLOW + "\n```\n\nEach step hands to the next.\n"));
        assertEquals(FLOW_HTML, Pages.md("```Mermaid\n" + FLOW + "\n```"), "the fence's word in any case");
        assertEquals(FLOW_HTML, Pages.md("```mermaid\n" + FLOW + "\n"), "a fence the report never closed is still drawn");
        assertEquals("<pre>flowchart LR\n    A --&gt; B\n</pre>", Pages.md("```python\nflowchart LR\n    A --> B\n```"), "any other fence is code, as before");
        assertEquals("<pre>a\n</pre>", Pages.md("```\na\n```"));
        String hostile = Pages.md("```mermaid\nflowchart LR\n    A[<script>alert(1)</script>] --> B[\"x\"]\n```");
        assertTrue(hostile.contains("A[&lt;script&gt;alert(1)&lt;/script&gt;] --&gt; B[&quot;x&quot;]"), hostile);
        assertFalse(hostile.contains("<script>"), "a tag in a model's diagram is text, never live: " + hostile);
        assertEquals(Pages.md("```mermaid\n" + FLOW + "\n```"), Pages.diagram(FLOW), "md and the chat make the diagram by the one helper");
    }

    @Test
    void theChatDrawsAReplysDiagramByTheSamePathAndItsPlainWordsAreAsTheyWere() {
        assertEquals("<p>Here is the flow:</p>" + FLOW_HTML + "<p>Each step hands to the next [<a href=\"/entry/I-0277-flow\">I-0277-flow</a>].</p>",
                Pages.prose("Here is the flow:\n\n```mermaid\n" + FLOW + "\n```\n\nEach step hands to the next [I-0277-flow]."));
        assertEquals("<p>Hello<br>there</p><p>Bye [<a href=\"/entry/F-0001-x\">F-0001-x</a>]</p>", Pages.prose("Hello\nthere\n\nBye [F-0001-x]"), "words without a fence render as before");
        assertEquals("<p></p>", Pages.prose(""), "as before");
        assertEquals("<pre>ls -la</pre><p>Then look.</p>", Pages.prose("```sh\nls -la\n```\n\nThen look."), "another fence is code; nothing stands between two blocks");
    }

    @Test
    void aPageWithADiagramCarriesTheScriptAndAWiderPolicyAndEveryOtherPageIsAsItWas(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        store.write(new Investigation("I-0277-flow", "How does a change travel from a ticket to a release?", Finding.State.accepted, "model:test", "2026-11-08T00:00:00Z", List.of(), List.of(),
                "## Answer\n\nThe flow of a change:\n\n```mermaid\n" + FLOW + "\n```\n\nEach step hands to the next.\n"));
        store.write(new Investigation("I-0278-plain", "What is a ticket?", Finding.State.accepted, "model:test", "2026-11-08T00:00:00Z", List.of(), List.of(), "## Answer\n\nA request for a change.\n"));
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + d.port();
            HttpResponse<String> drawn = get(c, base + "/entry/I-0277-flow");
            assertEquals(200, drawn.statusCode(), drawn.body());
            String h = drawn.body();
            assertTrue(h.contains(FLOW_HTML), h);
            assertTrue(h.contains(SCRIPT_TAG + "<script>(function(){if(!window.mermaid)return;"), "Mermaid from this library, then its drawing: " + h);
            assertTrue(h.contains("mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:look()==='dark'?'dark':'neutral',suppressErrorRendering:true})"), "strict, no HTML labels or clicks; the theme after the page's look; a parse error leaves the text: " + h);
            assertTrue(h.contains("mermaid.render(id,src).then(function(r){p.innerHTML=r.svg;p.classList.add('drawn')") && h.contains(".catch(function(err){p.textContent=src;") && h.contains("e.className='diagram-error'"), "drawn in place; on an error the text stays and the error goes under it: " + h);
            assertTrue(h.contains("'This diagram could not be drawn; its text is shown instead.'"), h);
            assertTrue(h.contains("attributeName==='data-theme'") && h.contains("prefers-color-scheme: dark"), "drawn again when the look switch or the computer's setting changes: " + h);
            assertTrue(h.indexOf(SCRIPT_TAG) > h.indexOf("</footer>"), "the script comes after the page, so the text is on the page before the drawing: " + h);
            assertEquals(DIAGRAM_CSP, drawn.headers().firstValue("Content-Security-Policy").orElse(""));
            // every other page: no script of Mermaid's, and the policy byte for byte as before
            for (String path : new String[]{"/entry/I-0278-plain", "/", "/past", "/search", "/chat"}) {
                HttpResponse<String> plain = get(c, base + path);
                assertEquals(200, plain.statusCode(), path);
                assertFalse(plain.body().contains("<script src=\"/static/") || plain.body().contains("mermaid.initialize") || plain.body().contains(Pages.DIAGRAM_MARK), path + " carries Mermaid: " + plain.body());
                assertEquals(PLAIN_CSP, plain.headers().firstValue("Content-Security-Policy").orElse(""), path);
            }
        } finally { d.stop(); }
    }

    @Test
    void theChatPageDrawsADiagramAReplyCarries(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        Patrons.set(store, "did:key:zWriter", "a writer", Patrons.Level.write);
        Patrons.Patron writer = new Patrons.Patron("did:key:zWriter", "a writer", "test");
        Librarian.Session s = Librarian.Session.open(store, writer);
        ObjectMapper m = new ObjectMapper();
        ObjectNode you = m.createObjectNode(); you.put("role", "user"); you.put("content", "draw me the flow"); you.put("at", System.currentTimeMillis());
        ObjectNode reply = m.createObjectNode(); reply.put("role", "assistant"); reply.put("content", "Here it is:\n\n```mermaid\n" + FLOW + "\n```"); reply.put("at", System.currentTimeMillis());
        s.append(you); s.append(reply);
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + d.port();
            HttpResponse<String> chat = c.send(HttpRequest.newBuilder(URI.create(base + "/chat?session=" + s.id)).header("Cookie", Pages.COOKIE + "=" + Patrons.issueToken(store, "did:key:zWriter")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, chat.statusCode(), chat.body());
            assertTrue(chat.body().contains("<div class=\"say\"><p>Here it is:</p>" + FLOW_HTML + "</div>"), chat.body());
            assertTrue(chat.body().contains(SCRIPT_TAG), chat.body());
            assertEquals(DIAGRAM_CSP, chat.headers().firstValue("Content-Security-Policy").orElse(""));
            assertTrue(chat.body().contains("if(view.querySelector('pre.mermaid')){if(window.rzDraw)window.rzDraw();else{location.reload();return}}"), "a reply that arrives with a diagram is drawn, or the page loads again with Mermaid: " + chat.body());
        } finally { d.stop(); }
    }

    @Test
    void theLibraryServesMermaidItselfWithALongCacheLifeAndNothingElseUnderStatic(@TempDir Path tmp) throws Exception {
        LibraryStore store = new LibraryStore(tmp); store.init();
        LibrarianDaemon d = LibrarianDaemon.start(store, "127.0.0.1", 0, "http://127.0.0.1:1", "none", -1);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + d.port();
            HttpResponse<byte[]> js = c.send(HttpRequest.newBuilder(URI.create(base + Pages.MERMAID_PATH)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, js.statusCode());
            assertEquals("text/javascript; charset=utf-8", js.headers().firstValue("Content-Type").orElse(""));
            assertEquals("public, max-age=31536000, immutable", js.headers().firstValue("Cache-Control").orElse(""), "the name carries the version, so the browser keeps the file");
            assertEquals("nosniff", js.headers().firstValue("X-Content-Type-Options").orElse(""));
            String head = new String(js.body(), 0, 60, StandardCharsets.UTF_8);
            assertTrue(head.startsWith("\"use strict\";var __esbuild_esm_mermaid_nm;"), head);
            assertTrue(new String(js.body(), js.body().length - 120, 120, StandardCharsets.UTF_8).contains("globalThis[\"mermaid\"] = "), "the build sets the mermaid global the drawing script calls");
            // the file is the one the README beside it records, by its checksum and its size
            String readme;
            try (InputStream in = Pages.class.getResourceAsStream("static/README.md")) { assertNotNull(in, "static/README.md"); readme = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            Matcher sha = Pattern.compile("sha256\\s+([0-9a-f]{64})").matcher(readme); assertTrue(sha.find(), readme);
            Matcher size = Pattern.compile("size\\s+(\\d+) bytes").matcher(readme); assertTrue(size.find(), readme);
            assertEquals(sha.group(1), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(js.body())));
            assertEquals(Integer.parseInt(size.group(1)), js.body().length);
            assertTrue(readme.contains("mermaid-" + Pages.MERMAID_VERSION + ".min.js") && readme.contains("MIT"), readme);
            HttpResponse<String> licence = get(c, base + "/static/mermaid-LICENSE.txt");
            assertEquals(200, licence.statusCode());
            assertTrue(licence.body().startsWith("The MIT License (MIT)"), licence.body());
            assertEquals("text/plain; charset=utf-8", licence.headers().firstValue("Content-Type").orElse(""));
            for (String gone : new String[]{"/static/mermaid-0.0.0.min.js", "/static/", "/static/a/b.js", "/static/%2e%2e/favicon.png", "/static/.hidden"}) {
                HttpResponse<String> r = get(c, base + gone);
                assertEquals(404, r.statusCode(), gone + ": " + r.body());
            }
            HttpResponse<String> posted = c.send(HttpRequest.newBuilder(URI.create(base + Pages.MERMAID_PATH)).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertNotEquals(200, posted.statusCode(), "a file is read, never written to");
        } finally { d.stop(); }
    }

    @Test
    void anExportKeepsTheDiagramAsItsText() {
        List<ExportDoc.Block> blocks = ExportDoc.parse("Before.\n\n```mermaid\n" + FLOW + "\n```\n\nAfter.", Map.of());
        assertEquals(3, blocks.size(), blocks.toString());
        assertInstanceOf(ExportDoc.Para.class, blocks.get(0));
        ExportDoc.Code code = assertInstanceOf(ExportDoc.Code.class, blocks.get(1), "the PDF and the Word file set the block as code, in the text's own words");
        assertEquals(List.of(FLOW.split("\n")), code.lines());
        assertInstanceOf(ExportDoc.Para.class, blocks.get(2));
        assertFalse(code.lines().stream().anyMatch(l -> l.contains("```") || l.contains("mermaid")), "the fence and its word are not in the text");
    }
}
