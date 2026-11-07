package com.twilight.claims.api.managed;

import com.twilight.claims.api.ClaimKey;

public record ManagedClaimSnapshot(ManagedClaimIdentity identity, ClaimKey key, long revision,
                                   ManagedLifecycle lifecycle, ManagedClaimState state) {}
