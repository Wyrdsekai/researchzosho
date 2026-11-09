package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

/**
 * The look at a video at any address (the brain's design, 2026-11-04, §4 {@code look}): the video field reads its frames and transcript
 * into the three tracks, said, shown and seen, keeps them, and answers what was asked of it with found, maybe or no and the second. The
 * protocol's op, the command, the page's form and the MCP tool all come through here; a test puts a stand-in in {@link #LOOKER}.
 */
public final class VideoLook {
    private VideoLook() { }
    private static final ObjectMapper M = new ObjectMapper();

    /** The reading of a video at any address: the video field's, unless a test stands one in. */
    public interface Looker { ObjectNode look(LibraryStore store, String video, String what, boolean sparse, String did) throws IOException; }
    public static final Looker REAL = VideoLook::real;
    public static volatile Looker LOOKER = REAL;

    /** The video field's look, as the op answers it: the video, its title, an answer per thing remembered, what the reading cost, and the words. */
    static ObjectNode real(LibraryStore store, String video, String what, boolean sparse, String did) throws IOException {
        VideoRecall.Look look = VideoRecall.lookAt(store, video, what, sparse, line -> { }, () -> false);
        ObjectNode r = M.createObjectNode();
        r.put("video", look.videoId()).put("title", look.title() == null ? "" : look.title()).put("link", look.link());
        ArrayNode a = r.putArray("answers");
        for (VideoRecall.Answer x : look.answers()) a.addObject().put("feature", x.feature()).put("verdict", x.verdict()).put("seconds", x.seconds()).put("at", x.seconds() > 0 ? x.clock() : "").put("why", x.why() == null ? "" : x.why());
        r.put("read", look.read() == null ? "" : look.read());
        r.put("summary", look.text().stripTrailing());
        return r;
    }

    /** What the look answers, {@code available} true; a bad address or an empty ask is an IllegalArgumentException for the caller to word. */
    public static ObjectNode look(LibraryStore store, String video, String what, boolean sparse, String did) throws IOException {
        ObjectNode o = LOOKER.look(store, video, what, sparse, did);
        o.put("available", true);
        if (!o.has("video")) o.put("video", video);
        return o;
    }
}
