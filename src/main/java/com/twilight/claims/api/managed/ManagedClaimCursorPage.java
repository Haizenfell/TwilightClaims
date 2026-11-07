package com.twilight.claims.api.managed;

import java.util.List;
import java.util.UUID;

public record ManagedClaimCursorPage(ManagedStatus status, List<ManagedClaimSnapshot> claims,
                                      UUID nextCursor, long generation) {
    public ManagedClaimCursorPage { claims = List.copyOf(claims); }
}
