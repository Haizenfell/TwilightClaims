package com.twilight.claims.api;

import java.util.Objects;

public final class ClaimOperationException extends RuntimeException {
    public enum Reason {
        NOT_FOUND, WORLD_UNAVAILABLE, INVALID_BOUNDS, CONFLICT, NOT_ENOUGH_POINTS,
        HAS_CHILDREN, SAVE_FAILED, INVALID_OWNER, INVALID_STATE, CANCELLED
    }

    private final Reason reason;

    public ClaimOperationException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public ClaimOperationException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason getReason() { return reason; }
}
