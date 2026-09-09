package org.researchzosho.client;

/** A protocol error: {@link #code} is stable (not_found, forbidden, no_sources, invalid_args, unavailable). */
public final class LibraryException extends RuntimeException {
    public final String code;
    public final int status;

    public LibraryException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }
}
