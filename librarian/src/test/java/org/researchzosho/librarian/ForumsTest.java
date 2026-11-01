package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.researchzosho.Config;
import org.researchzosho.tools.ForumSearchTool;
import org.researchzosho.tools.Forums;
import org.researchzosho.tools.ProductSearchTool;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The forums reader against a stand-in archive and a stand-in Stack Exchange API: nothing here touches the web or the box's settings. */
class ForumsTest {
    static final ObjectMapper M = new ObjectMapper();
    HttpServer server; Map<String, String> savedBase = new HashMap<>(); String realHome; Function<String, String> savedSearch;

    static String post(String id, String title, String text, int score, int comments, long created) {
        return "{\"id\":\"" + id + "\",\"subreddit\":\"selfhosted\",\"title\":\"" + title + "\",\"selftext\":\"" + text + "\",\"author\":\"someone\",\"score\":" + score + ",\"num_comments\":" + comments + ",\"created_utc\":" + created + ".0,\"permalink\":\"/r/selfhosted/comments/" + id + "/x/\"}";
    }

    @BeforeEach void stub(@TempDir Path tmp) throws Exception {
        realHome = System.getProperty("user.home"); System.setProperty("user.home", tmp.resolve("home").toString()); Config.invalidate();
        savedBase.putAll(Forums.BASE); savedSearch = ProductSearchTool.REDDIT_SEARCH;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        // the archive: a title search and a text search share one thread, so the reader must count it once; the scores decide the order
        server.createContext("/archive/api/posts/search", x -> {
            String q = x.getRequestURI().getQuery();
            String body = q.contains("title=") ? "{\"data\":[" + post("aaa111", "Is Seafile abandoned?", "Last release was long ago.", 5, 15, 1774704029L) + "," + post("bbb222", "Switched from Seafile to the other one", "", 47, 73, 1724000000L) + "]}"
                    : q.contains("selftext=") ? "{\"data\":[" + post("bbb222", "Switched from Seafile to the other one", "", 47, 73, 1724000000L) + "," + post("ccc333", "Weekly thread", "I run seafile at home", 12, 2, 1760000000L) + "]}"
                    : "{\"data\":[" + post("ddd444", "Newest", "", 1, 0, 1790000000L) + "]}";
            send(x, 200, body, false);
        });
        server.createContext("/archive/api/posts/ids", x -> send(x, 200, "{\"data\":[" + post("bbb222", "Switched from Seafile to the other one", "After a year I moved.", 47, 73, 1724000000L) + "]}", false));
        server.createContext("/archive/api/comments/tree", x -> send(x, 200, "{\"data\":[{\"kind\":\"t1\",\"data\":{\"id\":\"c1\",\"author\":\"ann\",\"body\":\"Sync is fast.\",\"score\":3,\"created_utc\":1724001000,\"replies\":{\"kind\":\"Listing\",\"data\":{\"children\":[{\"kind\":\"t1\",\"data\":{\"id\":\"c2\",\"author\":\"bo\",\"body\":\"Agreed, and the clients are separate.\",\"score\":1,\"created_utc\":1724002000,\"replies\":\"\"}}]}}}},"
                + "{\"kind\":\"t1\",\"data\":{\"id\":\"c3\",\"author\":\"cy\",\"body\":\"The company stopped its German business.\",\"score\":9,\"created_utc\":1724003000,\"replies\":\"\"}},{\"kind\":\"t1\",\"data\":{\"id\":\"c4\",\"author\":\"[deleted]\",\"body\":\"[removed]\",\"score\":0,\"created_utc\":1724004000,\"replies\":\"\"}}]}", false));
        // the Stack Exchange API: gzip on the search, as the real one sends it; a title with an entity; an accepted answer
        server.createContext("/se/search/advanced", x -> send(x, 200, "{\"items\":[{\"question_id\":192705,\"title\":\"Why won&#39;t prospective employers negotiate salary?\",\"body\":\"<p>I asked for <b>more</b>.</p><ul><li>once</li></ul>\",\"owner\":{\"display_name\":\"Pat\"},\"score\":35,\"answer_count\":8,\"creation_date\":1663000000,\"link\":\"https://workplace.stackexchange.com/questions/192705/why-wont\"},"
                + "{\"question_id\":67615,\"title\":\"How to counter-offer\",\"body\":\"<p>Short.</p>\",\"owner\":{\"display_name\":\"Lee\"},\"score\":-4,\"answer_count\":4,\"creation_date\":1463000000,\"link\":\"https://workplace.stackexchange.com/questions/67615/how\"}],\"has_more\":true,\"quota_max\":300,\"quota_remaining\":42}", true));
        server.createContext("/se/questions/192705/answers", x -> send(x, 200, "{\"items\":[{\"answer_id\":1,\"score\":30,\"is_accepted\":false,\"owner\":{\"display_name\":\"Dan\"},\"creation_date\":1663100000,\"body\":\"<p>The hiring manager had to jump through hoops.</p>\"},{\"answer_id\":2,\"score\":21,\"is_accepted\":true,\"owner\":{\"display_name\":\"Vee\"},\"creation_date\":1663200000,\"body\":\"<p>The main thing is leverage.</p>\"}],\"quota_remaining\":41}", false));
        server.createContext("/se/questions/192705/comments", x -> send(x, 200, "{\"items\":[{\"comment_id\":9,\"score\":0,\"owner\":{\"display_name\":\"Mod\"},\"creation_date\":1663300000,\"body\":\"Comments moved to chat.\"}],\"quota_remaining\":40}", false));
        server.createContext("/se/questions/192705", x -> send(x, 200, "{\"items\":[{\"question_id\":192705,\"title\":\"Why won&#39;t prospective employers negotiate salary?\",\"body\":\"<p>I asked for more.</p>\",\"owner\":{\"display_name\":\"Pat\"},\"score\":35,\"answer_count\":8,\"creation_date\":1663000000,\"link\":\"https://workplace.stackexchange.com/questions/192705/why-wont\"}],\"quota_remaining\":41}", false));
        server.createContext("/se/questions/1/answers", x -> send(x, 400, "{\"error_id\":400,\"error_message\":\"no site found for name xx\",\"error_name\":\"bad_parameter\"}", false));
        server.createContext("/se/questions/1", x -> send(x, 400, "{\"error_id\":400,\"error_message\":\"no site found for name xx\",\"error_name\":\"bad_parameter\"}", false));
        server.start();
        Forums.BASE.put("reddit_archive", base + "/archive"); Forums.BASE.put("stackexchange", base + "/se");
    }

    @AfterEach void restore() { server.stop(0); Forums.BASE.putAll(savedBase); ProductSearchTool.REDDIT_SEARCH = savedSearch; System.setProperty("user.home", realHome); Config.invalidate(); }

    static void send(com.sun.net.httpserver.HttpExchange x, int status, String body, boolean gzip) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        if (gzip) { ByteArrayOutputStream o = new ByteArrayOutputStream(); try (GZIPOutputStream g = new GZIPOutputStream(o)) { g.write(b); } b = o.toByteArray(); x.getResponseHeaders().add("Content-Encoding", "gzip"); }
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(status, b.length);
        try (OutputStream os = x.getResponseBody()) { os.write(b); }
    }

    @Test void aSubredditsThreadsComeFromTheTitleAndTheTextSearchesOnceEachBestFirst() throws Exception {
        List<Forums.Topic> ts = Forums.redditThreads("r/selfhosted", "seafile", 730, 10);
        assertEquals(List.of("bbb222", "ccc333", "aaa111"), ts.stream().map(Forums.Topic::id).toList(), "the shared thread once, by score");
        assertEquals("https://www.reddit.com/r/selfhosted/comments/bbb222/x/", ts.get(0).url());
        assertEquals(2, Forums.redditThreads("selfhosted", "seafile", 730, 2).size(), "the limit cuts after the sort");
        assertEquals("ddd444", Forums.redditThreads("https://www.reddit.com/r/selfhosted/", "", 30, 10).get(0).id(), "no words: the newest, as the archive gives them");
        String text = ForumSearchTool.threadsText(ts, "r/selfhosted", "seafile", true, 730);
        assertTrue(text.startsWith("3 thread(s) on r/selfhosted for \"seafile\" from the last 730 days, best first, read from the Arctic Shift archive"), text);
        assertTrue(text.contains("- [bbb222] 2024-08-18, score 47, 73 replies, by someone: Switched from Seafile to the other one — https://www.reddit.com/r/selfhosted/comments/bbb222/x/"), text);
        assertTrue(text.contains("- [aaa111] 2026-03-28, score 5, 15 replies, by someone: Is Seafile abandoned? — Last release was long ago. — https://"), text);
    }

    @Test void aThreadIsReadWithItsCommentTreeBestFirstRepliesSetIn() throws Exception {
        Forums.Read r = Forums.redditThread("https://www.reddit.com/r/selfhosted/comments/bbb222/switched/");
        assertEquals("Switched from Seafile to the other one", r.topic().title());
        assertEquals(List.of("cy", "ann", "bo"), r.replies().stream().map(Forums.Reply::author).toList(), "the top comments by score, each followed by the replies to it; the removed one left out");
        assertEquals(List.of(0, 0, 1), r.replies().stream().map(Forums.Reply::depth).toList());
        String text = ForumSearchTool.readText(r, true);
        assertTrue(text.startsWith("Thread [bbb222] on r/selfhosted, by someone, 2024-08-18, score 47, 73 replies, read from the Arctic Shift archive"), text);
        assertTrue(text.contains("\nAfter a year I moved.\nReplies, best first (3 shown of 73):\n- cy, score 9, 2024-08-18: The company stopped its German business.\n- ann, score 3, 2024-08-18: Sync is fast.\n  - bo, score 1, 2024-08-18: Agreed, and the clients are separate."), text);
    }

    @Test void aStackExchangeSiteIsSearchedByVotesAndAQuestionReadWithItsAnswersTheAcceptedOneMarked() throws Exception {
        List<Forums.Topic> ts = Forums.stackThreads("The Workplace", "negotiate salary", 10);
        assertEquals(2, ts.size());
        assertEquals("Why won't prospective employers negotiate salary?", ts.get(0).title(), "the entity decoded");
        assertEquals("I asked for more.\n- once", ts.get(0).text(), "the HTML as text");
        assertEquals("workplace.stackexchange.com", ts.get(0).site());
        assertEquals(42, Forums.stackQuotaLeft(), "read from the gzip answer");
        Forums.Read r = Forums.stackThread("workplace", "https://workplace.stackexchange.com/questions/192705/why-wont");
        assertEquals(List.of("Dan", "Vee", "Mod"), r.replies().stream().map(Forums.Reply::author).toList());
        assertTrue(r.replies().get(1).accepted()); assertEquals("comment", r.replies().get(2).kind());
        String text = ForumSearchTool.readText(r, false);
        assertTrue(text.contains("- Vee, score 21, 2022-09-15, the accepted answer: The main thing is leverage.\n- Mod, score 0, 2022-09-16, a comment on the question: Comments moved to chat."), text);
        assertTrue(text.contains("CC BY-SA"), text);
    }

    @Test void theSitesNameIsReadFromWhatAPersonWrites() {
        assertEquals("workplace", Forums.stackSite("workplace.stackexchange.com")); assertEquals("workplace", Forums.stackSite("The Workplace")); assertEquals("softwareengineering", Forums.stackSite("https://softwareengineering.stackexchange.com/questions/1"));
        assertEquals("stackoverflow", Forums.stackSite("stackoverflow.com")); assertEquals("softwareengineering", Forums.stackSite("Software Engineering")); assertEquals("dba", Forums.stackSite("dba"));
        assertEquals("selfhosted", Forums.subredditName("https://www.reddit.com/r/selfhosted/")); assertEquals("selfhosted", Forums.subredditName("r/selfhosted")); assertTrue(Forums.isReddit("r/selfhosted")); assertFalse(Forums.isReddit("workplace"));
        assertEquals(List.of("eesqmd", "1f3w5vs"), Forums.redditIdsIn("1. A\n   https://www.reddit.com/r/selfhosted/comments/eesqmd/seafile_vs/\n2. B\n   https://www.reddit.com/r/selfhosted/comments/1f3w5vs/switched/\n3. A again https://www.reddit.com/r/selfhosted/comments/eesqmd/"));
    }

    @Test void theToolFencesWhatPeopleWroteAndSaysWhenTheDaysAllowanceRunsLow() throws Exception {
        ForumSearchTool tool = new ForumSearchTool();
        String out = tool.execute(M.createObjectNode().put("kind", "threads").put("site", "workplace").put("query", "negotiate salary"));
        assertTrue(out.startsWith("<<<FORUM THREADS") || out.contains("FORUM THREADS"), out);
        assertTrue(out.contains("quoted material from a source"), out);
        assertTrue(out.contains("The Stack Exchange API allows this library 42 more call(s) today; a free key"), out);
        String read = tool.execute(M.createObjectNode().put("kind", "read").put("site", "reddit").put("id", "bbb222"));
        assertTrue(read.contains("FORUM THREAD") && read.contains("- cy, score 9"), read);
        assertTrue(tool.execute(M.createObjectNode().put("kind", "read").put("site", "xx").put("id", "1")).startsWith("The Stack Exchange API did not answer (HTTP 400: no site found for name xx)"));
        assertTrue(tool.execute(M.createObjectNode().put("kind", "threads").put("site", "reddit")).startsWith("ERROR: a subreddit is needed"));
    }

    @Test void whatOwnersWroteComesFromTheThreadsTheSearchFoundReadInTheArchive() throws Exception {
        ProductSearchTool.REDDIT_SEARCH = q -> "1. Switched from Seafile\n   https://www.reddit.com/r/selfhosted/comments/bbb222/switched/\n";
        String out = ProductSearchTool.owners("Seafile", 5);
        assertTrue(out.startsWith("Reddit threads about \"Seafile\", found by the web search and read from the Arctic Shift archive"), out);
        assertTrue(out.contains("- r/selfhosted, 2024-08-18, score 47, 73 replies: Switched from Seafile to the other one — After a year I moved. — https://www.reddit.com/r/selfhosted/comments/bbb222/x/\n    cy, score 9: The company stopped its German business.\n    ann, score 3: Sync is fast."), out);
        ProductSearchTool.REDDIT_SEARCH = q -> "nothing";
        assertTrue(ProductSearchTool.owners("Seafile", 5).contains("The web search found no Reddit thread for \"Seafile\"."));
    }
}
