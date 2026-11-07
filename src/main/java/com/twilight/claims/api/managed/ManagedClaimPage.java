package com.twilight.claims.api.managed;

import java.util.List;

public record ManagedClaimPage(ManagedStatus status, List<ManagedClaimSnapshot> claims, int nextOffset) {
    public ManagedClaimPage { claims = List.copyOf(claims); }
}
