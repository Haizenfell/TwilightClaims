package com.twilight.claims.managed;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import java.util.UUID;

public final class ManagedRegions {
    private static final String PREFIX = "tcmanaged_";

    private ManagedRegions() {}

    public static boolean isManaged(ProtectedRegion region) {
        return region != null && isManagedId(region.getId());
    }

    public static boolean isManagedId(String id) {
        return id != null && id.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }

    public static String regionId(UUID instanceId) {
        return PREFIX + instanceId.toString().replace("-", "");
    }
}
