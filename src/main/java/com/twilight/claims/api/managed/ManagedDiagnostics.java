package com.twilight.claims.api.managed;

import java.util.Map;

public record ManagedDiagnostics(boolean ready, int queuedOperations, int pendingRecovery,
                                  int activeClaims, int tombstones, int receipts,
                                  int controllers, int trustedClaims, String lastError,
                                  Map<String, Boolean> worlds) {
    public ManagedDiagnostics { worlds = Map.copyOf(worlds); }
}
