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
        return new ProtocolError("not_found", -32004, "The library holds nothing under " + what + ".");
    }
    public static ProtocolError forbidden(String message) {
        return new ProtocolError("forbidden", -32003, message);
    }
    public static ProtocolError noSources() {
        return new ProtocolError("no_sources", -32001,
                "A claim needs at least one source; the library does not accept unsourced claims.");
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
