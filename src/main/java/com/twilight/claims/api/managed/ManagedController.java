package com.twilight.claims.api.managed;

@FunctionalInterface
public interface ManagedController {
    boolean authorize(ManagedOperationContext context, ManagedAction action, ManagedClaimState desiredState);
}
