package org.researchzosho.client;

/** A protocol error: {@link #code} is stable (not_found, forbidden, no_sources, invalid_args, unavailable, declined, confirm). */
import com.fasterxml.jackson.databind.JsonNode;

public final class LibraryException extends RuntimeException {
    public final String code;
    public final int status;

    /**
     * The error as the library sent it, when it sent one: beside code and message, what a refusal carries as data (a {@code confirm} for a
     * comic lists under {@code comic_helper} what setting the comic helper up fetches). Null for a failure the library did not answer.
     */
    public final JsonNode error;

    public LibraryException(String code, int status, String message) { this(code, status, message, null); }

    public LibraryException(String code, int status, String message, JsonNode error) {
        super(message);
        this.code = code;
        this.status = status;
        this.error = error;
    }
}
