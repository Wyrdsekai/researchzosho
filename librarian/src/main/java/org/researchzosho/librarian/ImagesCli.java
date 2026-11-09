package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code researchzosho images find <words…> [--count N] [--keep] [--judge "<criteria>"] [--collection <name>]}, {@code images describe
 * <picture> [<question…>]} and {@code images judge "<criteria>" <picture>…}: pictures found for words, each said with its page; kept on the
 * shelves; described or judged by the seeing model. A picture is a web address, a path on this machine, a kept picture's file name or
 * its entry on the shelves.
 */
final class ImagesCli {
    private ImagesCli() { }

    private static final ObjectMapper M = new ObjectMapper();

    static final String USAGE = "usage: researchzosho images find <words…> [--count N] [--keep] [--judge \"<criteria>\"] [--collection <name>]\n"
            + "       researchzosho images describe <url | path | kept file | entry> [<question…>]\n"
            + "       researchzosho images judge \"<criteria>\" [--like <reference>] <url | path | kept file | entry>…\n"
            + "       researchzosho images like <url | path | kept file | entry> [<words…>] [--what person|place|object|…] [--concept \"<aspect>\"] [--count N] [--keep] [--collection <name>]\n"
            + "       researchzosho images likeness install | status      (the likeness helper: CLIP for pictures, SFace for faces, on the CPU)\n"
            + "  like: a picture as the ask. The seeing model describes the reference, the candidates are found for its words and yours, and each is judged beside it: the same person, place or thing (--what says which; person unless said), or, with --concept, like it in the aspect you name. The reference given as a path is kept on the shelves as yours.\n"
            + "  find: pictures for the words, from Brave when its key is kept and from the library's SearXNG, each said with the page it stands on; --keep fetches them onto the shelves (catalog/images/, shown at /images/<file>), --judge asks the seeing model whether each shows the criteria.\n"
            + "  describe: the seeing model's words about one picture, and its answer to the question when one is given. judge: yes, no or unsure for each picture, with one sentence why.\n"
            + "  The seeing model is the library's default when it reads pictures, else a local entry that does, else a hosted one with its key (researchzosho models test <name> finds out).";

    static int run(LibraryStore store, String[] args) throws IOException {
        if (args.length < 3) { System.err.println(USAGE); return 2; }
        // a quiet screen, as the chat's: the drive's lines belong in a log, not between the person and the pictures (a command printed the client's context-window line nine times, 2026-11-08)
        try { ((Logger) LoggerFactory.getLogger("org.researchzosho")).setLevel(Level.WARN); } catch (Throwable ignored) { }
        LibraryProtocol p = new LibraryProtocol(store);
        ObjectNode a = M.createObjectNode();
        a.putObject("patron").put("did", "person").put("name", "").put("runtime", "cli");
        try {
            switch (args[2]) {
                case "likeness" -> {
                    String what = args.length > 3 ? args[3] : "status";
                    String refreshed = LikenessHelper.refresh(); if (!refreshed.isEmpty()) System.out.println(refreshed);
                    if (what.equals("status")) { System.out.println(LikenessHelper.status()); return 0; }
                    if (!what.equals("install")) { System.err.println(USAGE); return 2; }
                    if (!LikenessHelper.on()) { System.out.println("The likeness helper is off (" + LikenessHelper.SETTING + "=off); unset that to install it."); return 1; }
                    if (LikenessHelper.installed()) { System.out.println(LikenessHelper.status()); return 0; }
                    System.out.println("Setting the likeness helper up: " + LikenessHelper.PICTURE_MODEL + " for pictures and " + LikenessHelper.FACE_MODEL + " for faces, on the CPU; " + LikenessHelper.SIZES + "; " + LikenessHelper.LICENCES + ". This takes minutes.");
                    String r = LikenessHelper.INSTALL.get();
                    System.out.println(r.equals("ready") ? "The likeness helper is ready. " + LikenessHelper.status() : "The setup did not finish: " + r.replaceFirst("^!", "") + ". researchzosho images likeness install tries again.");
                    return r.equals("ready") ? 0 : 1;
                }
                case "find", "search" -> {
                    List<String> words = new ArrayList<>(); int count = Images.DEFAULT_COUNT; boolean keep = false; String criteria = "", collection = "";
                    for (int i = 3; i < args.length; i++) {
                        switch (args[i]) {
                            case "--count", "-n" -> { try { count = Integer.parseInt(LibrarianCli.flagValue(args, i++)); } catch (NumberFormatException e) { System.err.println("--count takes a number."); return 2; } }
                            case "--keep" -> keep = true;
                            case "--judge", "--criteria" -> criteria = LibrarianCli.flagValue(args, i++);
                            case "--collection" -> collection = LibrarianCli.flagValue(args, i++);
                            default -> { if (args[i].startsWith("--")) { System.err.println(USAGE); return 2; } words.add(args[i]); }
                        }
                    }
                    if (words.isEmpty()) { System.err.println("Say what pictures to find: researchzosho images find red fox in snow"); return 2; }
                    a.put("op", "find").put("words", String.join(" ", words)).put("count", count);
                    if (keep) a.put("keep", true);
                    if (!criteria.isEmpty()) a.put("criteria", criteria);
                    if (!collection.isEmpty()) a.put("collection", collection);
                    JsonNode r = p.images(a);
                    System.out.println(r.path("summary").asText());
                    int i = 0;
                    for (JsonNode f : r.path("found")) {
                        i++;
                        System.out.println(i + ". " + f.path("said").asText());
                        System.out.println("   page: " + (f.path("page").asText("").isEmpty() ? "(none given)" : f.path("page").asText()));
                        System.out.println("   image: " + f.path("image").asText());
                        for (JsonNode v : r.path("verdicts")) if (v.path("image").asText().equals(f.path("image").asText())) System.out.println("   judged: " + v.path("verdict").asText() + (v.path("why").asText("").isEmpty() ? "" : " — " + v.path("why").asText()));
                        for (JsonNode k : r.path("kept")) if (k.path("image").asText().equals(f.path("image").asText())) System.out.println("   kept as " + k.path("raw").asText() + ", shown at " + k.path("url").asText());
                        for (JsonNode k : r.path("not_kept")) if (k.path("image").asText().equals(f.path("image").asText())) System.out.println("   not kept: " + k.path("why").asText());
                    }
                    return r.path("found").size() == 0 ? 1 : 0;
                }
                case "describe" -> {
                    if (args.length < 4) { System.err.println(USAGE); return 2; }
                    a.put("op", "describe"); a.putArray("images").add(args[3]);
                    if (args.length > 4) a.put("question", String.join(" ", List.of(args).subList(4, args.length)));
                    JsonNode r = p.images(a);
                    for (JsonNode d : r.path("descriptions")) System.out.println(d.path("description").asText("").isEmpty() ? "The seeing model gave no description of " + d.path("image").asText() + "." : d.path("description").asText());
                    System.out.println("(" + r.path("model").asText() + ")");
                    return 0;
                }
                case "judge" -> {
                    if (args.length < 5) { System.err.println(USAGE); return 2; }
                    a.put("op", "judge").put("criteria", args[3]);
                    ArrayNode imgs = a.putArray("images");
                    for (int i = 4; i < args.length; i++) {
                        if (args[i].equals("--like")) { a.put("reference", LibrarianCli.flagValue(args, i++)); continue; }
                        if (args[i].equals("--concept")) { a.put("mode", Images.CONCEPT); continue; }
                        imgs.add(args[i]);
                    }
                    JsonNode r = p.images(a);
                    if (r.has("reference")) System.out.println("Judged beside the reference" + (r.path("reference").path("id").asText("").isEmpty() ? "" : " kept as " + r.path("reference").path("id").asText()) + (r.path("two_pictures").asBoolean(false) ? ", both pictures in one look." : ", by the reference's description, since the model takes one picture at a time."));
                    System.out.println(r.path("summary").asText());
                    for (JsonNode v : r.path("verdicts")) System.out.println("  " + v.path("said").asText());
                    return 0;
                }
                case "like" -> {
                    if (args.length < 4) { System.err.println(USAGE); return 2; }
                    List<String> words = new ArrayList<>(); int count = Images.DEFAULT_COUNT; boolean keep = false; String what = "", concept = "", collection = "";
                    for (int i = 4; i < args.length; i++) {
                        switch (args[i]) {
                            case "--count", "-n" -> { try { count = Integer.parseInt(LibrarianCli.flagValue(args, i++)); } catch (NumberFormatException e) { System.err.println("--count takes a number."); return 2; } }
                            case "--keep" -> keep = true;
                            case "--what" -> what = LibrarianCli.flagValue(args, i++);
                            case "--concept" -> concept = LibrarianCli.flagValue(args, i++);
                            case "--collection" -> collection = LibrarianCli.flagValue(args, i++);
                            default -> { if (args[i].startsWith("--")) { System.err.println(USAGE); return 2; } words.add(args[i]); }
                        }
                    }
                    a.put("op", "like").put("reference", args[3]).put("count", count).put("name", args[3].replaceAll("^.*[\\\\/]", ""));
                    if (!words.isEmpty()) a.put("words", String.join(" ", words));
                    if (!concept.isEmpty()) a.put("mode", Images.CONCEPT).put("what", concept); else a.put("mode", Images.SAME).put("what", what.isEmpty() ? "person" : what);
                    if (keep) a.put("keep", true);
                    if (!collection.isEmpty()) a.put("collection", collection);
                    JsonNode r = p.images(a);
                    JsonNode ref = r.path("reference");
                    System.out.println((ref.path("id").asText("").isEmpty() ? "The reference was read." : "The reference is kept as " + ref.path("id").asText() + ", shown at " + ref.path("url").asText() + ".") + " Described as: " + ref.path("description").asText());
                    System.out.println("Searched for \"" + r.path("words").asText() + "\" (" + r.path("words_why").asText() + "). " + (Images.CONCEPT.equals(r.path("mode").asText()) ? "Looking for pictures like it in " + r.path("what").asText() : "Looking for the same " + r.path("what").asText()) + ", because " + r.path("mode_why").asText() + ". " + r.path("how").asText()
                            + (r.path("skipped").asInt(0) == 0 ? "" : " " + r.path("skipped").asInt() + " skipped as not pictures the model can look at."));
                    int i = 0;
                    for (JsonNode v : r.path("verdicts")) {
                        i++;
                        System.out.println(i + ". " + v.path("said").asText());
                        System.out.println("   page: " + (v.path("page").asText("").isEmpty() ? "(none given)" : v.path("page").asText()));
                        System.out.println("   image: " + v.path("image").asText());
                        for (JsonNode k : r.path("kept")) if (k.path("image").asText().equals(v.path("image").asText())) System.out.println("   kept as " + k.path("raw").asText() + ", shown at " + k.path("url").asText());
                    }
                    for (JsonNode f : r.path("same_as_reference")) System.out.println("Your picture appears also on " + (f.path("page").asText("").isEmpty() ? f.path("image").asText() : f.path("page").asText()) + ".");
                    System.out.println(r.path("summary").asText());
                    return r.path("verdicts").size() == 0 && r.path("same_as_reference").size() == 0 ? 1 : 0;
                }
                default -> { System.err.println(USAGE); return 2; }
            }
        } catch (ProtocolError e) { System.err.println(e.getMessage()); return 1; }
    }
}
