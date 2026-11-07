package com.twilight.claims.api.managed;

import java.util.UUID;

public record ManagedResult(ManagedStatus status, ManagedClaimSnapshot snapshot,
                            UUID operationId, String message) {
    public boolean isSuccess() {
        return status == ManagedStatus.APPLIED || status == ManagedStatus.DELETED || status == ManagedStatus.REPLAYED;
    }
}
