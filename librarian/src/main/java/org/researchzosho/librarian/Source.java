package org.researchzosho.librarian;

import org.researchzosho.tools.ContentPolicy;
import org.researchzosho.tools.Tool;

import java.util.List;

/**
 * A source is a place to look, with the tools that look there: the web, the scholarly indexes, the public records, the archives, the old
 * newspapers, Japanese full text, the forums, the videos and the video sites, the podcasts, the shops, the book catalogues, the software
 * archives, the shelves. A source says what it is for, which tools it offers a run, the keys a person may set for it, and whether it is
 * available on this box.
 */
public interface Source {

    /** "web", "scholar", "records", "archives", "old-newspapers", "japanese-fulltext", "forums", "videos", "video-sites", "podcasts", "shops", "books", "software", "shelves". */
    String name();

    /** The words the brain reads: what is found there and when it pays over the web. */
    String words();

    /** The tools this source offers a run with this focus and policy; none when the source is not available here. */
    List<Tool> tools(String focus, ContentPolicy policy);

    /** The settings' names of the keys this source can use; none for a source that needs no key. */
    List<String> keys();

    /** Whether the source can be looked at on this box: the video helper installed, a record collection known. */
    default boolean available() { return true; }

    /** Why it is not available, in a sentence for the person; "" when it is. */
    default String unavailableBecause() { return ""; }

    /** The fields that back this source during the transition. */
    default List<Profile> profiles() { return List.of(); }
}
