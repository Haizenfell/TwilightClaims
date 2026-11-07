package com.twilight.claims.api;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record Claim(ClaimKey key, String name, ClaimBounds bounds, Set<UUID> owners,
                    Set<UUID> members, int priority, ClaimKey parent, BoundaryStyle boundaryStyle) {
    public Claim {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(bounds, "bounds");
        owners = Set.copyOf(owners);
        members = Set.copyOf(members);
        Objects.requireNonNull(boundaryStyle, "boundaryStyle");
    }

    public long area() { return bounds.area(); }
    public boolean isOwner(UUID playerId) { return owners.contains(playerId); }
    public boolean isMember(UUID playerId) { return members.contains(playerId); }
    public Optional<UUID> owner() { return owners.stream().sorted().findFirst(); }
    public Optional<ClaimKey> parentKey() { return Optional.ofNullable(parent); }
}
