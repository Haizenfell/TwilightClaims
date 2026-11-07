package com.twilight.claims.api.managed;

import com.twilight.claims.api.BoundaryStyle;
import com.twilight.claims.api.ClaimBounds;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record ManagedClaimState(ClaimBounds bounds, long areaBudget, Set<UUID> members,
                                Map<String, String> flags, BoundaryStyle boundaryStyle, int priority) {
    public ManagedClaimState {
        Objects.requireNonNull(bounds, "bounds");
        members = Set.copyOf(members);
        flags = Map.copyOf(flags);
        if (areaBudget < 0) throw new IllegalArgumentException("Area budget cannot be negative");
        if (members.size() > 50) throw new IllegalArgumentException("At most 50 members are supported");
        if (flags.size() > 128) throw new IllegalArgumentException("At most 128 flags are supported");
        flags.forEach((key, value) -> {
            if (key.length() > 128 || value.length() > 8192) throw new IllegalArgumentException("Flag is too large");
        });
    }
}
