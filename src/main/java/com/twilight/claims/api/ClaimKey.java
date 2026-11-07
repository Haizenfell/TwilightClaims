package com.twilight.claims.api;

import java.util.Objects;
import java.util.UUID;

public record ClaimKey(UUID worldId, String regionId) {
    public ClaimKey {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(regionId, "regionId");
        if (regionId.isBlank()) throw new IllegalArgumentException("regionId must not be blank");
    }
}
