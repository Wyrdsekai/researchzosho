package org.researchzosho.librarian;

/**
 * A library-protocol error: a stable string {@code code} a patron's runtime can branch on, and a
 * message written as a sentence, because the patron will say it to a person. The JSON-RPC number
 * is what MCP needs; the string is what the contract (docs/LIBRARY_PROTOCOL.md §5) promises.
 */
public final class ProtocolError extends RuntimeException {
    public final String code;
    public final int rpc;

    private ProtocolError(String code, int rpc, String message) {
        super(message);
        this.code = code;
        this.rpc = rpc;
    }

    public static ProtocolError notFound(String what) {
        return new ProtocolError("not_found", -32004, notFoundMessage(what));
    }
    /**
     * The sentence a person reads. A caller that wrote a whole sentence (it ends in a period) is quoted as it
     * is; "entry X", "id X", "job X", "claim X" and "route X" become one; anything else reads
     * "Nothing in the library matches X."
     */
    static String notFoundMessage(String what) {
        String w = what == null ? "" : what.strip();
        if (w.endsWith(".")) return w;
        if (w.startsWith("entry ") || w.startsWith("id ")) return "No entry has the id " + w.substring(w.indexOf(' ') + 1) + ".";
        if (w.startsWith("job ")) return "No research run has the id " + w.substring(4) + ".";
        if (w.startsWith("claim ")) return "No claim has the id " + w.substring(6) + ".";
        if (w.startsWith("route ")) return "Nothing is served at " + w.substring(6) + ".";
        return "Nothing in the library matches " + w + ".";
    }
    public static ProtocolError forbidden(String message) {
        return new ProtocolError("forbidden", -32003, message);
    }
    public static ProtocolError noSources() {
        return new ProtocolError("no_sources", -32001,
                "A claim needs at least one source. Add a source and submit it again.");
    }
    public static ProtocolError invalidArgs(String message) {
        return new ProtocolError("invalid_args", -32602, message);
    }
    /** Reserved: there is no daily budget; a host must still accept the code. */
    public static ProtocolError budgetExceeded(String message) {
        return new ProtocolError("budget_exceeded", -32005, message);
    }
    public static ProtocolError unavailable(String message) {
        return new ProtocolError("unavailable", -32002, message);
    }
}
