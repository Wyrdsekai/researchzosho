package org.researchzosho.tools;

import org.researchzosho.Stopping;
import org.researchzosho.librarian.Acquisitions;
import org.researchzosho.librarian.LibraryStore;
import org.researchzosho.librarian.Video;
import org.researchzosho.librarian.VideoRecall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.List;
import java.util.Comparator;
import java.util.ArrayList;

/**
 * The look at one video: a research run's tool for what one SEES in a video, which its page and transcript cannot answer. Given the
 * video's address and what the person remembers, the library reads the video's frames into the three tracks (whole or sparse) and
 * keeps them, and answers each thing remembered with found, maybe or no, the second and why. The words to the model say when it
 * pays over the page and what it costs, so a worker looks at three candidates rather than thirty.
 */
public final class LookTool implements Tool {
    private volatile ContentPolicy policy = ContentPolicy.defaults();

    /** The run's content policy, which carries the library the reading is kept in. */
    public LookTool policy(ContentPolicy p) { this.policy = p == null ? ContentPolicy.defaults() : p; return this; }

    @Override public String name() { return "look"; }

    @Override public String description() {
        return "Looks at one video for what a person remembers SEEING in it: reads its frames and transcript and answers found, maybe or no for each thing, with the second. "
                + "Use it when the question names things one sees (no talking, words on screen, a pocket, a colour, what happens at the start) and a candidate video is in hand; the page and the transcript cannot answer those. "
                + "Costs about seven minutes per ten minutes of video the first time with sparse=false, a third of that with sparse=true, and seconds when the library has read the video before. "
                + "Example: {\"video\": \"https://www.youtube.com/watch?v=abcdefghijk\", \"what\": \"no talking; the dimensions on screen at the start; an inside pocket\", \"sparse\": true}";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode s = j.createObjectNode(); s.put("type", "object");
        ObjectNode props = s.putObject("properties");
        props.putObject("video").put("type", "string").put("description", "the video's address (https://www.youtube.com/watch?v=…) or its 11-character id");
        props.putObject("what").put("type", "string").put("description", "what the person remembers seeing, one thing after another, separated by semicolons");
        props.putObject("sparse").put("type", "boolean").put("description", "true reads the first minute whole and then every fourth frame (a third of the time); false reads every frame (default true)");
        s.putArray("required").add("video").add("what");
        return s;
    }

    @Override public String execute(JsonNode args) {
        LibraryStore store = policy.store();
        if (store == null && Acquisitions.libraryExists()) store = LibraryStore.open();
        if (store == null) return "ERROR: no library is open to keep what the look reads";
        return run(store, args);
    }

    /** The most videos one look takes at once. */
    public static final int MANY = 5;

    /** One video's look, for {@link #runMany}; a test stands in for it. */
    public interface Looker { VideoRecall.Look look(LibraryStore store, String video, String what, boolean sparse, BooleanSupplier stop) throws IOException; }
    public static volatile Looker LOOKER = (store, video, what, sparse, stop) -> VideoRecall.lookAt(store, video, what, sparse, s -> { }, stop);

    /**
     * The look at several candidate videos for the same things remembered, each looked at in turn (sparse unless told), the answer a list
     * best first by how many things were found in each, then by how many maybe. The breadth comes before any depth: a run that looked at
     * one candidate three times, piece by piece, never looked at the right one (2026-11-04).
     */
    public static String runMany(LibraryStore store, List<String> videos, String what, boolean sparse) {
        if (!Video.installed()) return "ERROR: the video helper is not installed, so no video can be read: researchzosho video install";
        List<String> asked = new ArrayList<>();
        for (String v : videos) { String s = v == null ? "" : v.strip(); if (!s.isEmpty() && !asked.contains(s)) asked.add(s); }
        if (asked.isEmpty()) return "ERROR: Give the candidates' addresses in videos.";
        List<VideoRecall.Look> looks = new ArrayList<>();
        List<String> notLooked = new ArrayList<>();
        BooleanSupplier stop = Stopping.current();
        for (String v : asked.subList(0, Math.min(MANY, asked.size()))) {
            if (stop.getAsBoolean()) { notLooked.add(v + " (the run's time ran out)"); continue; }
            try { looks.add(LOOKER.look(store, v, what, sparse, stop)); }
            catch (IllegalArgumentException | IOException e) { notLooked.add(v + " (" + e.getMessage() + ")"); }
        }
        for (String v : asked.subList(Math.min(MANY, asked.size()), asked.size())) notLooked.add(v + " (one look takes " + MANY + " at most)");
        looks.sort(Comparator.comparingLong((VideoRecall.Look l) -> -l.answers().stream().filter(a -> a.verdict().equals("found")).count())
                .thenComparingLong(l -> -l.answers().stream().filter(a -> a.verdict().equals("maybe")).count()));
        StringBuilder b = new StringBuilder("Looked at " + looks.size() + (looks.size() == 1 ? " video" : " videos") + " for: " + what + ". Best first, by how many of the things were found:\n");
        int i = 0;
        for (VideoRecall.Look l : looks) {
            i++;
            long found = l.answers().stream().filter(a -> a.verdict().equals("found")).count(), maybe = l.answers().stream().filter(a -> a.verdict().equals("maybe")).count();
            b.append(i).append(". ").append(l.text().strip().replace("\n", "\n   ")).append("\n   ").append(found).append(" of ").append(l.answers().size()).append(" found")
             .append(maybe > 0 ? ", " + maybe + " maybe" : "").append(".\n");
        }
        if (!notLooked.isEmpty()) b.append("Not looked at: ").append(String.join("; ", notLooked)).append(".\n");
        return b.toString();
    }

    /** The look on a library, for the tool and for the interfaces that offer it: a plain answer, or a sentence beginning ERROR. */
    public static String run(LibraryStore store, JsonNode args) {
        if (!Video.installed()) return "ERROR: the video helper is not installed, so no video can be read: researchzosho video install";
        try {
            VideoRecall.Look look = VideoRecall.lookAt(store, args.path("video").asText(""), args.path("what").asText(""), args.path("sparse").asBoolean(true), s -> { }, Stopping.current());
            return look.text();
        } catch (IllegalArgumentException e) { return "ERROR: " + e.getMessage(); }
        catch (IOException e) { return "ERROR: " + e.getMessage(); }
    }
}
