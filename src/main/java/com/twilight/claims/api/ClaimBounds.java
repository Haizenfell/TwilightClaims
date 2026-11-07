package com.twilight.claims.api;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.Objects;

public record ClaimBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public ClaimBounds {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Minimum coordinates must not exceed maximum coordinates");
        }
    }

    public static ClaimBounds fullHeight(World world, int minX, int minZ, int maxX, int maxZ) {
        Objects.requireNonNull(world, "world");
        return new ClaimBounds(minX, world.getMinHeight(), minZ, maxX, world.getMaxHeight() - 1, maxZ);
    }

    public long area() {
        long width = (long) maxX - minX + 1;
        long depth = (long) maxZ - minZ + 1;
        return width > Long.MAX_VALUE / depth ? Long.MAX_VALUE : width * depth;
    }

    public double centerX() { return ((long) minX + maxX) / 2.0; }
    public double centerZ() { return ((long) minZ + maxZ) / 2.0; }
    public Location center(World world) {
        return new Location(world, centerX(), ((long) minY + maxY) / 2.0, centerZ());
    }
}
