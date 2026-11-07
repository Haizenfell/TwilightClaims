package com.twilight.claims.api.managed;

import java.util.Objects;
import java.util.UUID;

public record ManagedOperationContext(ManagedClaimIdentity identity, UUID operationId,
                                      long revision, UUID initiator) {
    public ManagedOperationContext {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(operationId, "operationId");
        if (revision < 1) throw new IllegalArgumentException("Revision must be positive");
    }
}
