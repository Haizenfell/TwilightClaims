package com.twilight.claims.api.managed;

import java.util.concurrent.CompletionStage;

public interface ManagedControllerRegistration extends AutoCloseable {
    CompletionStage<Boolean> renewAcl(ManagedClaimIdentity identity, long revision, long leaseTicks);
    CompletionStage<Boolean> invalidateAcl(ManagedClaimIdentity identity);
    @Override void close();
}
