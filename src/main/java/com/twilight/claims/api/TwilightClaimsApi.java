package com.twilight.claims.api;

import com.sk89q.worldguard.protection.flags.Flag;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface TwilightClaimsApi {
    int API_VERSION = 1;

    String getPluginVersion();
    List<Claim> getClaims();
    List<Claim> getClaims(World world);
    List<Claim> getClaims(UUID owner);
    Optional<Claim> getClaim(ClaimKey key);
    Optional<Claim> getClaimAt(Location location);
    Optional<Claim> getClaimAt(Player player, Location location);
    Optional<Claim> findNearestClaim(Location location, double radius);
    Optional<Claim> findClaim(UUID owner, long number);
    boolean isOwner(ClaimKey key, UUID player);
    boolean isTrusted(ClaimKey key, UUID player);
    boolean hasConflict(World world, ClaimBounds bounds, ClaimKey ignoredClaim);

    Claim createClaim(UUID owner, World world, ClaimBounds bounds, boolean enforcePointLimit);
    Claim createClaim(UUID owner, World world, ClaimBounds bounds, BoundaryStyle style, boolean enforcePointLimit);
    Claim createClaim(Player owner, int radius);
    Claim createChildClaim(UUID owner, ClaimKey parent, ClaimBounds bounds, BoundaryStyle style, boolean enforcePointLimit);
    Claim resizeClaim(ClaimKey key, ClaimBounds bounds, boolean enforcePointLimit);
    void deleteClaim(ClaimKey key);
    Claim transferOwner(ClaimKey key, UUID owner, boolean enforcePointLimit);
    Claim addMember(ClaimKey key, UUID player);
    Claim removeMember(ClaimKey key, UUID player);
    Claim clearMembers(ClaimKey key);
    <T> Optional<T> getFlag(ClaimKey key, Flag<T> flag);
    Map<Flag<?>, Object> getFlags(ClaimKey key);
    <T> Claim setFlag(ClaimKey key, Flag<T> flag, T value);
    Claim clearFlags(ClaimKey key);
    Claim setPriority(ClaimKey key, int priority);
    Claim setParent(ClaimKey key, ClaimKey parent);

    BoundaryStyle getDefaultBoundaryStyle();
    BoundaryStyle getBoundaryStyle(ClaimKey key);
    Optional<BoundaryStyle> getBoundaryStyleOverride(ClaimKey key);
    Claim setBoundaryStyle(ClaimKey key, BoundaryStyle style);
    Claim resetBoundaryStyle(ClaimKey key);
    void showBoundary(Player viewer, ClaimKey key);
    void showBoundary(Player viewer, ClaimKey key, long durationTicks);
    void showErrorBoundary(Player viewer, ClaimKey key, long durationSeconds);
    void showOwnedBoundaries(Player viewer);
    void clearBoundaries(Player viewer);
    boolean isVisualizing(Player viewer);

    ClaimPoints getPoints(Player player);
    Optional<ClaimPoints> getCachedPoints(UUID player);
    boolean canAfford(Player player, long additionalArea);
    ItemStack getShovel();
    ItemStack getInspector();
    boolean isShovel(ItemStack item);
    boolean isInspector(ItemStack item);
    void showClaimList(Player player, int page);
    void requestDeletion(Player player, ClaimKey key);
}
