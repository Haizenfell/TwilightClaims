package com.twilight.claims.api.managed;

import org.bukkit.plugin.Plugin;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public interface ManagedClaimsApi {
    int getApiVersion();
    Set<String> getCapabilities();
    String getServerId();
    ManagedControllerRegistration registerController(Plugin plugin, String controllerId, ManagedController controller);
    CompletionStage<ManagedResult> ensureManagedClaim(ManagedOperationContext context, ManagedClaimState state);
    CompletionStage<ManagedResult> applyManagedState(ManagedOperationContext context, ManagedClaimState state);
    CompletionStage<ManagedResult> deleteManagedClaim(ManagedOperationContext context);
    CompletionStage<ManagedResult> lookupManagedClaim(ManagedClaimIdentity identity);
    CompletionStage<ManagedResult> getOperationResult(UUID operationId);
    CompletionStage<ManagedClaimPage> listManagedClaims(String controllerId, int offset, int limit);
    CompletionStage<ManagedDiagnostics> getDiagnostics();
    CompletionStage<ManagedResult> reconcileManagedClaim(ManagedClaimIdentity identity);
}
