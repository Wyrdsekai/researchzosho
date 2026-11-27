package org.researchzosho.librarian;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * A value a command takes on its command line, typed or read from a file the person names (0.8.4). {@code --file <path>} in the value's
 * place reads it from that file, so that a key never stands on a command line, in the shell's history or in the list of processes. The
 * file is read once, by the command the person typed, and its text goes into the value and nowhere else; the command says which file it
 * read, never what was in it. Only the command line reads a file: the chat, the web app, the phone, MCP and the API take a value as it
 * is given, and refuse one written as {@code --file …} ({@link #fileForm}).
 * <p>
 * The value is the file's one line with text on it, without the line end and the spaces around it. A file that is missing, cannot be
 * read, holds no text, or holds more than one line of text is refused in a sentence that names the file and shows nothing of what is in
 * it: a file of two keys is someone's notes, and the library does not guess which line is meant. A word that begins with two dashes is
 * not taken as a value (before 0.8.4 {@code settings set exa_key --file key.txt} kept the words "--file key.txt" as the key and said
 * it was set); a value that really begins so comes after {@code --}.
 */
final class CommandValue {
    private CommandValue() { }

    /** The most a value's file is read for: a key is a few hundred bytes, and a larger file is not one value. */
    static final int MOST_BYTES = 64 * 1024;

    /**
     * What the command line gave: the value, the file it was read from ("" when it was typed) and how many words it took; or why not, with
     * {@code usage} true when the words were not a form the command takes (the command then prints its usage).
     */
    record Given(String value, String file, int words, String refused, boolean usage) {
        boolean ok() { return refused.isEmpty(); }
        static Given typed(String value, int words) { return new Given(value, "", words, "", false); }
        static Given wrong(String why) { return new Given("", "", 0, why, true); }
        static Given unread(String why) { return new Given("", "", 0, why, false); }
    }

    /**
     * The value of {@code settings set <name> …}: the words from {@code from} on, joined with spaces, as they always were; {@code --file
     * <path>} and nothing else, read from the file; or every word after {@code --}, as it is. Any other word that begins with two dashes
     * is refused, and so is anything after the path ({@code command}: the command's words, for the sentence that says what it does not take).
     */
    static Given rest(String[] args, int from, String command) {
        List<String> words = from < args.length ? Arrays.asList(args).subList(from, args.length) : List.of();
        if (words.isEmpty()) return Given.wrong(command + " needs a value.");
        String first = words.get(0);
        if (first.equals("--file")) {
            if (words.size() == 1) return Given.wrong("--file needs the path of a file.");
            if (words.size() > 2) return Given.wrong("--file takes one path and nothing after it; a path with spaces goes in quotes.");
            return read(words.get(1));
        }
        if (first.equals("--")) {
            if (words.size() == 1) return Given.wrong("-- needs the value after it.");
            return Given.typed(String.join(" ", words.subList(1, words.size())), words.size());
        }
        for (String w : words) if (w.startsWith("--")) return Given.wrong(notTaken(w, command));
        return Given.typed(String.join(" ", words), words.size());
    }

    /**
     * One value at {@code at}, for a command that takes more after it ({@code models key}, {@code records key}, {@code connect},
     * {@code peer add}): the word there; {@code --file <path>}, read from the file (two words); or {@code -- <word>}, as it is (two words).
     * A word there that begins with two dashes is refused.
     */
    static Given one(String[] args, int at, String command) {
        if (at >= args.length) return Given.wrong(command + " needs a value.");
        String w = args[at];
        if (w.equals("--file")) return at + 1 < args.length ? read(args[at + 1]) : Given.wrong("--file needs the path of a file.");
        if (w.equals("--")) return at + 1 < args.length ? Given.typed(args[at + 1], 2) : Given.wrong("-- needs the value after it.");
        if (w.startsWith("--")) return Given.wrong(notTaken(w, command));
        return Given.typed(w, 1);
    }

    private static String notTaken(String word, String command) {
        return word.equals("--file") ? "--file goes in the value's place, with the path after it." : command + " does not take " + word + ".";
    }

    /** The value read from the file at {@code given} (the path as the person typed it), or the refusal that names the file. */
    static Given read(String given) {
        String nothing = " Nothing was changed.";
        Path p;
        try { p = Path.of(given); } catch (InvalidPathException e) { return Given.unread("There is no file at " + given + "." + nothing); }
        if (Files.isDirectory(p)) return Given.unread(given + " is a folder, not a file." + nothing);
        if (!Files.exists(p)) return Given.unread("There is no file at " + given + "." + nothing);
        byte[] bytes;
        try (InputStream in = Files.newInputStream(p)) { bytes = in.readNBytes(MOST_BYTES + 1); }   // a pipe or /dev/stdin is read too, never more than this
        catch (AccessDeniedException e) { return Given.unread("The file " + given + " cannot be read: you are not allowed to read it." + nothing); }
        catch (IOException e) { return Given.unread("The file " + given + " cannot be read." + nothing); }
        if (bytes.length > MOST_BYTES) return Given.unread("The file " + given + " is larger than one value can be (" + MOST_BYTES / 1024 + " KB)." + nothing);
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { return Given.unread("The file " + given + " is not text." + nothing); }
        if (text.startsWith("﻿")) text = text.substring(1);   // the mark some editors put at a text file's start
        List<String> lines = text.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        if (lines.isEmpty()) return Given.unread("The file " + given + " is empty." + nothing);
        if (lines.size() > 1) return Given.unread("The file " + given + " has " + lines.size() + " lines with text on them. The value is read from a file that holds it alone, on one line, so that no line is guessed at." + nothing);
        return new Given(lines.get(0), given, 2, "", false);
    }

    /** Whether a value given to a door is the command line's file form ({@code --file <path>}), which no door reads. */
    static boolean fileForm(String value) {
        return value != null && value.strip().matches("(?s)--file(\\s.*)?");
    }

    /** What a door says to a value in the file form: the command that reads one ({@code command}, with its {@code --file <path>}), and that nothing was changed. */
    static String onlyOnTheCommandLine(String command) {
        return Pages.t("A value is read from a file only on the command line of the library's own computer: {0}. Nothing was changed.", command);
    }
}
