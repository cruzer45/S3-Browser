package com.s3explorer.service;

/**
 * Thrown from inside a running transfer when the user pauses or cancels it. Unchecked so it
 * can propagate out of SDK callbacks such as request body streams.
 */
public class TransferSignal extends RuntimeException {

    public enum Kind { PAUSED, CANCELLED }

    private final Kind kind;

    public TransferSignal(Kind kind) {
        super(kind == Kind.PAUSED ? "Transfer paused" : "Transfer cancelled", null, false, false);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
