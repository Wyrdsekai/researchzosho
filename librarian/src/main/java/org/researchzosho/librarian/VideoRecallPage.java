package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.researchzosho.librarian.Pages.t;
import static org.researchzosho.librarian.Pages.n;

/** The Video page, {@code /video}: a search over what was said and seen in the videos the library has read, the picture in your head, the answers so far, and the videos held. */
public final class VideoRecallPage {
    private VideoRecallPage() { }

    /** Text made safe for a page, for the field that lives in another package. */
    public static String esc(String s) { return Pages.esc(s); }

    public static String body(LibraryStore store, boolean writer, String query) throws IOException { return body(store, writer, query, Patrons.Patron.PERSON); }

    /** One line for the videos the library has read: how many, with which of the three tracks, and when the last was read. On Past's row and at the head of the table. */
    static String heldLine(List<VideoRecall.Held> held) {
        long said = held.stream().filter(VideoRecall.Held::said).count(), shown = held.stream().filter(VideoRecall.Held::shown).count(), seen = held.stream().filter(VideoRecall.Held::seen).count();
        String last = ""; for (VideoRecall.Held h : held) if (h.readOn().compareTo(last) > 0) last = h.readOn();
        return t("{0}, {1} with what was said, {2} with the words on screen, {3} with what is seen", n(held.size(), "{0} video read", "{0} videos read"), said, shown, seen) + (last.isEmpty() ? "." : "; " + t("last read {0}", Chrome.now().day(last)) + ".");
    }

    public static String body(LibraryStore store, boolean writer, String query, Patrons.Patron patron) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("<p class=\"k\">").append(t("Something you saw in a video a while ago, found again: the library searches what was said in the videos it has read, the words shown on screen and what is seen in them once it has looked, and answers with the moment.")).append("</p>");
        b.append("<form class=\"big\" action=\"/video\"><input name=\"q\" value=\"").append(Pages.esc(query == null ? "" : query)).append("\" placeholder=\"").append(Pages.esc(t("what you remember hearing or seeing"))).append("\" autofocus aria-label=\"").append(Pages.esc(t("what you remember"))).append("\"> <button>").append(t("Find the moment")).append("</button></form>");
        if (query != null && !query.isBlank()) {
            List<VideoRecall.Hit> hits = VideoRecall.saw(store, query, 12);
            b.append("<h2 id=\"found\">").append(t("What matches")).append("</h2>");
            if (hits.isEmpty()) b.append("<p>").append(t("Nothing in the videos the library has read matches that. It searches what was said, and the words shown and what is seen on screen once the picture has been looked for, below.")).append("</p>");
            else {
                b.append("<ul>");
                for (VideoRecall.Hit h : hits) b.append("<li><a href=\"").append(Pages.esc(h.link())).append("\" rel=\"noopener\">[").append(h.clock()).append("] ").append(Pages.esc(h.title())).append("</a> <span class=\"k\">").append(VideoRecall.label(h.kind())).append(Pages.esc(h.line())).append("</span></li>");
                b.append("</ul>");
            }
        }
        b.append("<h2 id=\"picture\">").append(t("The picture in your head")).append("</h2>");
        String running = running(store);
        if (!running.isEmpty()) b.append("<p class=\"notice\">").append(Pages.esc(running)).append(" ").append(t("{0} shows how far it has come.", "<a href=\"/jobs\">" + t("The runs page") + "</a>")).append("</p>");
        b.append("<p>").append(t("Describe what you remember seeing, and the library looks through the frames of the videos it has read: YouTube's own small frames of each video, one about every five seconds. The model reads the words on screen and says what is seen in each, the library keeps those lines beside what was said and searches them, and the model looks again at the nearest moments at full size.")).append(" ").append(VideoRecall.visionAnswers() ? t("It needs a model that reads pictures, and one answers.") : t("It needs a model that reads pictures; none answers now, so it looks through what was said instead.")).append("</p>");
        if (writer && running.isEmpty()) {
            b.append("<form method=\"post\" action=\"/video\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"picture\">")
             .append("<textarea name=\"description\" rows=\"3\" placeholder=\"").append(Pages.esc(t("a red kettle on a wooden table, a man in a blue apron pouring tea"))).append("\" aria-label=\"").append(Pages.esc(t("what you remember seeing"))).append("\"></textarea>")
             .append("<p class=\"k\">").append(t("What is in it:")).append(" ");
            for (String k : List.of("person", "thing", "place", "text")) b.append("<label><input type=\"checkbox\" name=\"kinds\" value=\"").append(k).append("\"> ").append(k.equals("text") ? t("words on screen") : t("a " + k)).append("</label> ");
            b.append("</p><button>").append(t("Look for it")).append("</button></form>");
        }
        b.append("<h2 id=\"look\">").append(t("Look at a video")).append("</h2><p>").append(t("Give a video's address and what to look for in it, and the library reads its frames and transcript and answers from them, whether or not it has read the video before. It takes about seven minutes per ten minutes of video on a local model; a quick look reads a third of the frames.")).append("</p>");
        if (writer) b.append("<form method=\"post\" action=\"/video\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"look\"><input name=\"video\" size=\"48\" placeholder=\"https://www.youtube.com/watch?v=…\" aria-label=\"").append(Pages.esc(t("the video's address"))).append("\"> <input name=\"what\" size=\"40\" placeholder=\"").append(Pages.esc(t("what to look for"))).append("\" aria-label=\"").append(Pages.esc(t("what to look for"))).append("\"> <label><input type=\"checkbox\" name=\"sparse\" value=\"1\"> ").append(t("a quick look")).append("</label> <button>").append(t("Look")).append("</button></form>");
        b.append("<h2 id=\"candidates\">").append(t("Candidates for a video you remember")).append("</h2><p>").append(t("Give the name of the thing the video makes or shows, two to four words, once in each language it may be in, and the library gathers the candidates itself: deep searches of its own plain phrasings, every upload of the channels those surface, YouTube's Data API in date order when a key is set, the other search indexes, and your own watch history first. It drops what the dates and the length rule out and lists the rest best fit first, with no popularity in it. It takes about ten minutes.")).append(" ")
                .append(t("Add what you remember of the video itself, one thing after another, and the library also looks for pages where other people describe a video in those words, when a web search index is set up.")).append("</p>");
        if (writer) b.append("<form method=\"post\" action=\"/video\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"candidates\"><input name=\"things\" size=\"48\" placeholder=\"apron, エプロン, 앞치마\" aria-label=\"").append(Pages.esc(t("the thing, once per language, comma-separated"))).append("\"> <input name=\"remembered\" size=\"48\" placeholder=\"").append(Pages.esc(t("nobody speaks; a pocket on the front"))).append("\" aria-label=\"").append(Pages.esc(t("what you remember of the video itself, one thing after another"))).append("\"> <input name=\"before\" size=\"10\" placeholder=\"").append(Pages.esc(t("before 2025-11"))).append("\" aria-label=\"").append(Pages.esc(t("watched before"))).append("\"> <input name=\"after\" size=\"10\" placeholder=\"").append(Pages.esc(t("after 2023-01"))).append("\" aria-label=\"").append(Pages.esc(t("uploaded after"))).append("\"> <input name=\"language\" size=\"4\" placeholder=\"ja\" aria-label=\"").append(Pages.esc(t("the language of the title or the on-screen text, when you know it"))).append("\"> <button>").append(t("Gather the candidates")).append("</button></form>");
        b.append("<h2 id=\"sift\">").append(t("Sift the candidates")).append("</h2><p>").append(t("Give what you remember of the video, one thing after another, and the key the candidates answer ended with, and the library judges each candidate's thumbnail, then the first storyboard sheet of those that pass, with the model that reads pictures, and lists the survivors best first. The thumbnail answers for what one picture can show, the thing, its colours, a tag; the first sheet for what you remember about the start. It takes a second or two a thumbnail and about ten a sheet; a call takes two hundred candidates at most and says how many are left, and the same call again goes on from there.")).append("</p>");
        if (writer) b.append("<form method=\"post\" action=\"/video\" class=\"stack\"><input type=\"hidden\" name=\"op\" value=\"sift\"><input name=\"what\" size=\"48\" placeholder=\"").append(Pages.esc(t("a striped apron; a red tag at the neck; the pattern pieces laid out at the start"))).append("\" aria-label=\"").append(Pages.esc(t("what you remember, one thing after another"))).append("\"> <input name=\"from\" size=\"28\" placeholder=\"").append(Pages.esc(t("the pool's key"))).append("\" aria-label=\"").append(Pages.esc(t("the key of the candidate pool"))).append("\"> <input name=\"limit\" size=\"4\" placeholder=\"200\" aria-label=\"").append(Pages.esc(t("how many to take"))).append("\"> <button>").append(t("Sift them")).append("</button></form>");
        b.append("<p class=\"k\">").append(Pages.esc(WatchHistory.summary(store))).append("</p>");
        List<ObjectNode> kept = VideoRecall.kept(store);
        if (!kept.isEmpty()) {
            b.append("<h2 id=\"answers\">").append(t("Looked for so far")).append("</h2>");
            int shown = 0;
            for (ObjectNode o : kept) {
                if (shown++ >= 10) break;
                b.append("<details><summary>").append(Pages.esc(o.path("at").asText("").replace("T", " ").replaceFirst("\\.\\d+Z$", ""))).append(" — ").append(Pages.esc(o.path("description").asText(""))).append("</summary><pre>").append(Pages.esc(o.path("answer").asText(""))).append("</pre></details>");
            }
        }
        List<VideoRecall.Held> held = VideoRecall.held(store);
        b.append("<h2 id=\"held\">").append(t("The videos the library has read")).append("</h2>");
        if (held.isEmpty()) b.append("<p>").append(t("None yet. A research run that reads videos keeps each one here with what was said in it; so does {0}.", "<code>researchzosho youtube video &lt;address&gt; --transcript</code>")).append("</p>");
        else {
            b.append("<p class=\"k\">").append(Pages.esc(heldLine(held))).append("</p>");
            b.append("<table><tr><th>").append(t("Video")).append("</th><th>").append(t("Read on")).append("</th><th>").append(t("What was said")).append("</th><th>").append(t("The words on screen")).append("</th><th>").append(t("What is seen")).append("</th></tr>");
            int rows = 0;
            for (VideoRecall.Held h : held) {
                if (rows++ >= 200) { b.append("<tr><td colspan=\"5\" class=\"k\">").append(t("and {0} more", held.size() - 200)).append("</td></tr>"); break; }
                b.append("<tr><td><a href=\"https://www.youtube.com/watch?v=").append(Pages.esc(h.videoId())).append("\" rel=\"noopener\">").append(Pages.esc(h.title())).append("</a></td><td>").append(Pages.esc(Chrome.now().day(h.readOn()))).append("</td><td>").append(h.said() ? t("kept") : "").append("</td><td>").append(Pages.esc(by(h.shownBy()))).append("</td><td>").append(Pages.esc(by(h.seenBy()))).append("</td></tr>");
            }
            b.append("</table>");
        }
        b.append(Pages.rowsSection(store, patron, "videos", t("Videos reports"), t("None yet. Ask in the chat about channels, videos or what is shown in them: the report it files is the first row here.")));
        return b.toString();
    }

    /** The models whose reading is kept, for a cell of the table: "kept" for the older capture that did not name its model. */
    static String by(Set<String> models) {
        List<String> names = new ArrayList<>();
        for (String m : models) names.add(m.isEmpty() ? t("kept") : m);
        return String.join(", ", names);
    }

    /** A search for a picture that is waiting or running, in a sentence; "" when none. */
    public static String running(LibraryStore store) throws IOException {
        Jobs jobs = new Jobs(store, j -> { throw new IllegalStateException("read only"); });
        for (ObjectNode j : jobs.active()) {
            if (!j.path("kind").asText().equals(VideoRecall.JOB_KIND)) continue;
            JsonNode p = j.path("progress");
            return (j.path("state").asText().equals("running") ? t("A search for a picture is running ({0})", j.path("job_id").asText()) : t("A search for a picture is waiting to start ({0})", j.path("job_id").asText())) + (p.path("phase").asText("").isEmpty() ? "." : ": " + p.path("phase").asText() + ".");
        }
        return "";
    }
}
