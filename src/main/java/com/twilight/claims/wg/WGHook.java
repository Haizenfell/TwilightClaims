package com.twilight.claims.wg;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.twilight.claims.TwilightClaims;
import com.twilight.claims.api.ClaimBounds;
import com.twilight.claims.api.ClaimKey;
import com.twilight.claims.api.ClaimOperationException;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class WGHook {

    private static final int WORLD_COORDINATE_LIMIT = 29_999_984;

    private final TwilightClaims plugin;
    private final RegionContainer container;

    public WGHook(TwilightClaims plugin) {
        this(plugin, WorldGuard.getInstance().getPlatform().getRegionContainer());
    }

    WGHook(TwilightClaims plugin, RegionContainer container) {
        this.plugin = plugin;
        this.container = container;
    }

    public RegionManager getManager(World world) {
        return world == null ? null : container.get(BukkitAdapter.adapt(world));
    }

    public boolean isClaim(ProtectedRegion region) {
        return region instanceof ProtectedCuboidRegion && ClaimIdentity.isClaimId(region.getId());
    }

    public String getDisplayName(ProtectedRegion region) {
        return ClaimIdentity.displayName(region);
    }

    public record ClaimRef(World world, ProtectedRegion region) {}

    public boolean isOwner(Player player, ProtectedRegion region) {
        return isOwner(player.getUniqueId(), region);
    }

    public boolean isOwner(UUID owner, ProtectedRegion region) {
        return isClaim(region) && region.getOwners().contains(owner);
    }

    public ProtectedRegion getRegionAt(Location location) {
        return selectRegion(null, location, false);
    }

    public ProtectedRegion getRegionAt(Player player, Location location) {
        return selectRegion(player, location, false);
    }

    public ProtectedRegion getClaimAt(Player player, Location location) {
        return selectRegion(player, location, true);
    }

    private ProtectedRegion selectRegion(Player player, Location location, boolean claimsOnly) {
        RegionManager manager = getManager(location.getWorld());
        if (manager == null) return null;
        BlockVector3 position = BukkitAdapter.asBlockVector(location);
        Comparator<ProtectedRegion> ordering = Comparator
                .comparingInt((ProtectedRegion region) -> player != null
                        && region.getOwners().contains(player.getUniqueId()) ? 0 : 1)
                .thenComparingInt(region -> edgeRank(region, position))
                .thenComparing(Comparator.comparingInt(ProtectedRegion::getPriority).reversed())
                .thenComparingLong(this::getArea)
                .thenComparing(ProtectedRegion::getId);
        return manager.getApplicableRegions(position).getRegions().stream()
                .filter(ProtectedRegion::isPhysicalArea)
                .filter(region -> region.contains(position))
                .filter(region -> !claimsOnly || isClaim(region))
                .min(ordering).orElse(null);
    }

    private static int edgeRank(ProtectedRegion region, BlockVector3 position) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        boolean edgeX = position.x() == min.x() || position.x() == max.x();
        boolean edgeZ = position.z() == min.z() || position.z() == max.z();
        return edgeX && edgeZ ? 0 : edgeX || edgeZ ? 1 : 2;
    }

    public List<ProtectedRegion> getRegionsOwnedBy(Player player) {
        return getOwnedClaims(player).stream().map(ClaimRef::region).toList();
    }

    public List<ClaimRef> getOwnedClaims(Player player) {
        return getOwnedClaims(player.getUniqueId());
    }

    public List<ClaimRef> getOwnedClaims(UUID owner) {
        List<ClaimRef> regions = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (ProtectedRegion region : getAllRegions(world)) {
                if (isOwner(owner, region)) regions.add(new ClaimRef(world, region));
            }
        }
        regions.sort(Comparator
                .comparingLong((ClaimRef ref) -> ClaimIdentity.numberOf(ref.region().getId()).orElse(Long.MAX_VALUE))
                .thenComparing(ref -> ref.region().getId())
                .thenComparing(ref -> ref.world().getName(), Comparator.nullsLast(Comparator.naturalOrder())));
        return regions;
    }

    public ProtectedRegion getClaim(UUID worldId, String regionId) {
        World world = Bukkit.getWorld(worldId);
        RegionManager manager = getManager(world);
        if (manager == null) return null;
        ProtectedRegion region = manager.getRegion(regionId);
        return isClaim(region) ? region : null;
    }

    public Collection<ProtectedRegion> getAllRegions(World world) {
        RegionManager manager = getManager(world);
        if (manager == null) return List.of();
        return manager.getRegions().values().stream().filter(ProtectedRegion::isPhysicalArea).toList();
    }

    public Location getNearestPointInRegion(ProtectedRegion region, Location from) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        double x = Math.clamp(from.getX(), (double) min.x(), (double) max.x() + 1);
        double z = Math.clamp(from.getZ(), (double) min.z(), (double) max.z() + 1);
        return new Location(from.getWorld(), x, from.getY(), z);
    }

    public boolean isWithinRadius(ProtectedRegion region, Location origin, double radius) {
        return radius >= 0 && distanceToRegion(region, origin) <= radius;
    }

    public double distanceToRegion(ProtectedRegion region, Location origin) {
        if (origin.getWorld() == null || !region.isPhysicalArea()) return Double.MAX_VALUE;
        return getNearestPointInRegion(region, origin).distance(origin);
    }

    public ProtectedRegion getClosestRegion(Location origin, double radius) {
        return getAllRegions(origin.getWorld()).stream()
                .filter(this::isClaim)
                .filter(r -> isWithinRadius(r, origin, radius))
                .min(Comparator.comparingDouble((ProtectedRegion r) -> distanceToRegion(r, origin))
                        .thenComparing(ProtectedRegion::getId))
                .orElse(null);
    }

    public static class ProximityResult {
        private final ProtectedRegion other;

        public ProximityResult(ProtectedRegion other) {
            this.other = other;
        }

        public ProtectedRegion getOther() { return other; }
    }

    public ProximityResult checkProximity(
            World world,
            BlockVector3 newMin,
            BlockVector3 newMax,
            ProtectedRegion ignore
    ) {
        RegionManager rm = getManager(world);
        if (rm == null) return null;

        ProtectedRegion candidate = new ProtectedCuboidRegion("__claim_check__", newMin, newMax);
        List<ProtectedRegion> candidates = new ArrayList<>();
        for (ProtectedRegion other : getAllRegions(world).stream()
                .filter(other -> ignore == null || !other.getId().equalsIgnoreCase(ignore.getId()))
                .sorted(Comparator.comparing(ProtectedRegion::getId)).toList()) {
            if (ignore != null && isDescendant(other, ignore)) {
                if (!candidate.contains(other.getMinimumPoint()) || !candidate.contains(other.getMaximumPoint())) {
                    return new ProximityResult(other);
                }
                continue;
            }
            if (ignore != null && isDescendant(ignore, other)) {
                if (!(other instanceof ProtectedCuboidRegion)
                        || !other.contains(candidate.getMinimumPoint())
                        || !other.contains(candidate.getMaximumPoint())) {
                    return new ProximityResult(other);
                }
                continue;
            }
            candidates.add(other);
        }
        return candidate.getIntersectingRegions(candidates).stream()
                .findFirst().map(ProximityResult::new).orElse(null);
    }

    private static boolean isDescendant(ProtectedRegion region, ProtectedRegion ancestor) {
        Set<ProtectedRegion> visited = new HashSet<>();
        for (ProtectedRegion parent = region.getParent(); parent != null && visited.add(parent); parent = parent.getParent()) {
            if (parent.equals(ancestor)) return true;
        }
        return false;
    }

    public ProtectedRegion createRegion(Player p, int radius) {
        Location loc = p.getLocation();
        World w = p.getWorld();

        int x = loc.getBlockX();
        int z = loc.getBlockZ();

        long minX = (long) x - radius;
        long maxX = (long) x + radius;
        long minZ = (long) z - radius;
        long maxZ = (long) z + radius;
        if (radius < 0 || minX < -WORLD_COORDINATE_LIMIT || maxX > WORLD_COORDINATE_LIMIT
                || minZ < -WORLD_COORDINATE_LIMIT || maxZ > WORLD_COORDINATE_LIMIT
                || !w.getWorldBorder().isInside(new Location(w, minX + 0.5, loc.getY(), minZ + 0.5))
                || !w.getWorldBorder().isInside(new Location(w, maxX + 0.5, loc.getY(), maxZ + 0.5))) {
            plugin.getMessages().send(p, "region_invalid_size", text -> text);
            return null;
        }

        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 1;

        RegionManager rm = getManager(w);
        if (rm == null) {
            p.sendMessage(plugin.getMessages().msg("region_manager_null")
                    .replace("%world%", w.getName()));
            return null;
        }

        BlockVector3 newMin = BlockVector3.at(minX, minY, minZ);
        BlockVector3 newMax = BlockVector3.at(maxX, maxY, maxZ);

        ProximityResult pr = checkProximity(w, newMin, newMax, null);
        if (pr != null) {
            plugin.getVisualizer().showError(p, pr.getOther());

            String owner = pr.getOther().getOwners().getUniqueIds()
                    .stream()
                    .findFirst()
                    .map(id -> {
                        String name = Bukkit.getOfflinePlayer(id).getName();
                        return name == null ? id.toString() : name;
                    })
                    .orElse("Unknown");

            p.sendMessage(
                    plugin.getMessages().msg("region_create_conflict")
                            .replace("%region%", getDisplayName(pr.getOther()))
                            .replace("%owner%", owner)
            );
            return null;
        }

        try {
            var created = plugin.getApi().createClaim(p.getUniqueId(), w,
                    new ClaimBounds((int) minX, minY, (int) minZ, (int) maxX, maxY, (int) maxZ), true);
            return getClaim(created.key().worldId(), created.key().regionId());
        } catch (ClaimOperationException exception) {
            sendOperationFailure(p, exception, (maxX - minX + 1) * (maxZ - minZ + 1));
            return null;
        }
    }

    public String newRegionId(UUID owner, World world) {
        RegionManager rm = getManager(world);
        if (rm == null) throw new IllegalStateException("WorldGuard is unavailable for this world");
        Set<Long> occupied = new HashSet<>();
        Set<String> existingIds = new HashSet<>(rm.getRegions().keySet());
        for (World loaded : Bukkit.getWorlds()) {
            RegionManager loadedManager = getManager(loaded);
            if (loadedManager == null) continue;
            existingIds.addAll(loadedManager.getRegions().keySet());
            for (ProtectedRegion existing : loadedManager.getRegions().values()) {
                if (isOwner(owner, existing)) {
                    ClaimIdentity.numberOf(existing.getId()).ifPresent(occupied::add);
                }
            }
        }
        for (ProtectedRegion existing : rm.getRegions().values()) {
            if (isOwner(owner, existing)) ClaimIdentity.numberOf(existing.getId()).ifPresent(occupied::add);
        }
        long number = ClaimIdentity.nextNumber(occupied);
        return ClaimIdentity.newId(number, candidate -> {
            String prefix = candidate.substring(0, 16);
            return existingIds.stream().anyMatch(existing -> existing.equals(candidate)
                    || existing.equals(prefix) || existing.startsWith(prefix + "_"));
        });

    }

    public boolean removeClaim(Player player, Location location) {
        ProtectedRegion region = getClaimAt(player, location);
        if (region == null || !isOwner(player, region)) return false;
        return removeClaim(player, location.getWorld(), region.getId());
    }

    public boolean removeClaim(Player player, UUID worldId, String regionId) {
        return removeClaim(player, Bukkit.getWorld(worldId), regionId);
    }

    private boolean removeClaim(Player player, World world, String regionId) {
        RegionManager manager = getManager(world);
        if (manager == null) return false;
        ProtectedRegion region = manager.getRegion(regionId);
        if (!isOwner(player, region)) return false;
        if (manager.getRegions().values().stream().anyMatch(other -> region.equals(other.getParent()))) {
            plugin.getMessages().send(player, "region_has_children", text -> text);
            return false;
        }
        try {
            plugin.getApi().deleteClaim(new ClaimKey(world.getUID(), regionId));
            return true;
        } catch (ClaimOperationException exception) {
            sendOperationFailure(player, exception, getArea(region));
            return false;
        }
    }

    private void sendOperationFailure(Player player, ClaimOperationException exception, long area) {
        String message = switch (exception.getReason()) {
            case SAVE_FAILED -> "region_save_failed";
            case CANCELLED -> "claim_action_cancelled";
            case NOT_ENOUGH_POINTS -> "not_enough_points";
            case HAS_CHILDREN -> "region_has_children";
            case NOT_FOUND -> "no_region";
            default -> "region_invalid_size";
        };
        plugin.getMessages().send(player, message, text -> text.replace("%need%", String.valueOf(area))
                .replace("%free%", String.valueOf(exception.getReason() == ClaimOperationException.Reason.NOT_ENOUGH_POINTS
                        ? plugin.getApi().getPoints(player).free() : 0)));
    }

    public long getArea(ProtectedRegion region) {
        if (!region.isPhysicalArea()) return 0;
        return areaOf(region.getMinimumPoint(), region.getMaximumPoint());
    }

    public static long areaOf(BlockVector3 min, BlockVector3 max) {
        long width = Math.abs((long) max.x() - min.x()) + 1;
        long depth = Math.abs((long) max.z() - min.z()) + 1;
        return width > Long.MAX_VALUE / depth ? Long.MAX_VALUE : width * depth;
    }

    public boolean save(World w) {
        try {
            RegionManager rm = getManager(w);
            if (rm == null) return false;
            rm.saveChanges();
            return true;
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to save WorldGuard regions for world " + w.getName() + ": " + ex.getMessage());
            return false;
        }
    }
}
