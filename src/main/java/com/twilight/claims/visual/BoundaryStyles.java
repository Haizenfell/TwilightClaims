package com.twilight.claims.visual;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StringFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.api.BoundaryStyle;
import org.bukkit.Material;

import java.util.Objects;
import java.util.Optional;

public final class BoundaryStyles {
    public static final String FLAG_NAME = "twilightclaims-boundary-style";
    private static StringFlag styleFlag;

    private BoundaryStyles() {}

    public static void registerFlag() {
        var registry = WorldGuard.getInstance().getFlagRegistry();
        Flag<?> existing = registry.get(FLAG_NAME);
        if (existing != null) {
            styleFlag = requireStringFlag(existing);
            return;
        }
        StringFlag candidate = new StringFlag(FLAG_NAME);
        try {
            registry.register(candidate);
            styleFlag = candidate;
        } catch (FlagConflictException ex) {
            styleFlag = requireStringFlag(registry.get(FLAG_NAME));
        }
    }

    private static StringFlag requireStringFlag(Flag<?> existing) {
        if (existing instanceof StringFlag stringFlag) return stringFlag;
        throw new IllegalStateException("WorldGuard flag " + FLAG_NAME + " is registered with an incompatible type");
    }

    public static StringFlag flag() {
        if (styleFlag == null) throw new IllegalStateException("Boundary style flag has not been registered");
        return styleFlag;
    }

    public static String raw(ProtectedRegion region) {
        return Objects.requireNonNull(region, "region").getFlag(flag());
    }

    public static Optional<BoundaryStyle> get(ProtectedRegion region) {
        String value = raw(region);
        return value == null ? Optional.empty() : Optional.of(decode(value));
    }

    public static void set(ProtectedRegion region, BoundaryStyle style) {
        Objects.requireNonNull(region, "region").setFlag(flag(), encode(Objects.requireNonNull(style, "style")));
    }

    public static void reset(ProtectedRegion region) {
        Objects.requireNonNull(region, "region").setFlag(flag(), null);
    }

    public static String encode(BoundaryStyle style) {
        Objects.requireNonNull(style, "style");
        return String.join(",", style.corner().name(), style.edge().name(), style.selectedCorner().name(),
                style.errorCorner().name(), style.errorEdge().name());
    }

    public static BoundaryStyle decode(String value) {
        String[] materials = Objects.requireNonNull(value, "value").split(",", -1);
        if (materials.length != 5) throw new IllegalArgumentException("Boundary style must contain exactly five materials");
        return new BoundaryStyle(Material.valueOf(materials[0]), Material.valueOf(materials[1]),
                Material.valueOf(materials[2]), Material.valueOf(materials[3]), Material.valueOf(materials[4]));
    }
}
