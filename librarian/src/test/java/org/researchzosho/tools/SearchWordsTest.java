package org.researchzosho.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.researchzosho.librarian.Searx;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A search's words cannot choose where the search runs (0.8.1). SearXNG reads a part of the words that begins with {@code !},
 * {@code !!}, {@code :} or {@code <} as a command, wherever the part stands: on 2026-11-20 {@code !bd weather} made the person's own
 * SearXNG call Baidu, an engine that was switched off. The forms here are the ones {@code searx/query.py} of SearXNG 2026.7.26 parses,
 * each with what is sent in its place.
 */
class SearchWordsTest {

    /** Each form the parser accepts, as a model would write it → what is sent. */
    static final String[][] FORMS = {
            {"!bd weather", "bd weather"},                     // an engine by its shortcut (BangParser: engine_shortcuts)
            {"!baidu weather", "baidu weather"},               // an engine by its name
            {"!sogou_wechat weather", "sogou_wechat weather"}, // a name written with _ or - for its space
            {"!images red fox", "images red fox"},             // a category
            {"weather !bd", "weather bd"},                     // anywhere in the words, not only first
            {"weather !bd tomorrow !qk", "weather bd tomorrow qk"},
            {"!!g weather", "g weather"},                      // another site altogether (ExternalBangParser)
            {"weather !!", "weather"},                         // the first result opened (FeelingLuckyParser)
            {":zh weather", "zh weather"},                     // the language (LanguageParser): a code, a name, a country
            {"weather :zh-cn", "weather zh-cn"},
            {":chinese weather", "chinese weather"},
            {"<3 weather", "3 weather"},                       // the time limit in seconds (TimeoutParser)
            {"weather <850", "weather 850"},                   // and in milliseconds
            {"!:<bd weather", "bd weather"},                   // one mark behind another
            {"weather !bd", "weather bd"},           // Python splits at a no-break space
            {"天気　!bd", "天気　bd"},                   // and at an ideographic space
            {"weather\t!bd", "weather\tbd"},
            {"weather\n!bd", "weather\nbd"},
            {"weather\u001F!bd", "weather\u001Fbd"},           // and at the separators 1C to 1F
            {"weather !bd", "weather bd"},
            {"  !bd   weather  ", "bd   weather"},
    };

    /** Words that are no command stay as they are. */
    static final String[] PLAIN = {"weather in Lisbon", "wow! what a storm", "site:example.org ferry", "a:b c<d e!f", "C++ templates", "時刻表 フェリー", "50% off?", "\"exact phrase\" -minus"};

    /** The white space Python's \s matches in text, which SearXNG splits a search's words on. */
    static final String PYTHON_SPACE = "[\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\x85\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+";

    /** Whether SearXNG's parser would look at any part of these words as a command: a part beginning with one of its marks. */
    static boolean commandIn(String words) {
        for (String part : words.split(PYTHON_SPACE)) if (!part.isEmpty() && "!:<".indexOf(part.charAt(0)) >= 0) return true;
        return false;
    }

    @Test
    void everyFormSearxngReadsAsACommandIsSentAsTheWordItIs() {
        for (String[] f : FORMS) {
            assertTrue(commandIn(f[0]), "the form is one the parser reads: " + f[0]);
            assertEquals(f[1], Searx.plain(f[0]), "sent for " + f[0]);
            assertFalse(commandIn(Searx.plain(f[0])), "nothing left for the parser in: " + Searx.plain(f[0]));
        }
        for (String p : PLAIN) assertEquals(p, Searx.plain(p), "words that are no command are sent as written");
        assertEquals("", Searx.plain("!! !"));
        assertEquals("", Searx.plain(null));
    }

    @Test
    void aSearxngIsNeverSentACommandInAModelsWords() throws Exception {
        List<String> asked = new CopyOnWriteArrayList<>();
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/search", ex -> {
            asked.add(ex.getRequestURI().getRawQuery());
            byte[] b = "{\"results\":[{\"title\":\"Placeholder page\",\"url\":\"https://example.org/a\",\"content\":\"placeholder text\"}]}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        s.start();
        WebSearchTool.endpointOverride = "http://127.0.0.1:" + s.getAddress().getPort();
        try {
            List<String> sent = new ArrayList<>();
            for (String[] f : FORMS) {
                asked.clear();
                new WebSearchTool().execute(new ObjectMapper().createObjectNode().put("query", f[0]));
                assertEquals(1, asked.size(), "one request for " + f[0]);
                String q = "";
                for (String kv : asked.get(0).split("&")) if (kv.startsWith("q=")) q = URLDecoder.decode(kv.substring(2), StandardCharsets.UTF_8);
                sent.add(q);
                assertEquals(f[1], q, "the words SearXNG was sent for " + f[0]);
                assertFalse(commandIn(q), "a command reached SearXNG: " + q);
            }
            assertEquals(FORMS.length, sent.size());
        } finally {
            WebSearchTool.endpointOverride = null;
            s.stop(0);
        }
    }

    @Test
    void aSearchServiceOfThePersonsOwnIsSentTheWordsTheSameWay() {
        assertEquals("http://localhost:7700/search?q=bd%20weather", CustomSearchTool.address("http://localhost:7700/search?q={q}", "!bd weather", 5));
        assertEquals("http://localhost:7700/search?q=weather%20in%20Lisbon", CustomSearchTool.address("http://localhost:7700/search", "weather in Lisbon", 5));
    }
}
