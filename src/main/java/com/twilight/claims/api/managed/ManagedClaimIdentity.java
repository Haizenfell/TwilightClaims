package com.twilight.claims.api.managed;

import java.util.Objects;
import java.util.UUID;

public record ManagedClaimIdentity(String controllerId, UUID externalId, UUID instanceId,
                                   String serverId, UUID worldId) {
    public ManagedClaimIdentity {
        Objects.requireNonNull(controllerId, "controllerId");
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(worldId, "worldId");
        if (!controllerId.matches("[a-z0-9][a-z0-9_.-]{0,63}") || serverId.isBlank() || serverId.length() > 128)
            throw new IllegalArgumentException("Invalid controller or server identifier");
    }

    public String regionId() { return "tcmanaged_" + instanceId.toString().replace("-", ""); }
}
