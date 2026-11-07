package com.twilight.claims.api.managed;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

public interface ManagedControllerRegistration extends AutoCloseable {
    CompletionStage<Boolean> renewAcl(ManagedClaimIdentity identity, long revision, long leaseTicks);
    CompletionStage<Boolean> invalidateAcl(ManagedClaimIdentity identity);
    default CompletionStage<ManagedResult> retireOperations(ManagedClaimIdentity identity, long throughRevision) {
        return CompletableFuture.completedFuture(new ManagedResult(
                ManagedStatus.PROVIDER_UNAVAILABLE, null, null, "Operation retirement is unsupported"));
    }
    @Override void close();
}
