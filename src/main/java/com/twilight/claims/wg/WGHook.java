package com.twilight.claims.wg;

import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.twilight.claims.TwilightClaims;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.*;

public class WGHook {

    private final TwilightClaims plugin;
    private final RegionContainer container;

    public WGHook(TwilightClaims plugin) {
        this.plugin = plugin;
        this.container = WorldGuard.getInstance().getPlatform().getRegionContainer();
    }

    public RegionManager getManager(World world) {
        return container.get(BukkitAdapter.adapt(world));
    }

    public ProtectedRegion getRegionAt(Location loc) {
        RegionManager rm = getManager(loc.getWorld());
        if (rm == null) return null;

        ApplicableRegionSet set = rm.getApplicableRegions(BukkitAdapter.asBlockVector(loc));
        for (ProtectedRegion region : set) return region;
        return null;
    }
    public Location getNearestPointInRegion(ProtectedRegion region, Location from) {

        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();

        double x = from.getX();
        double z = from.getZ();

        double clampedX = Math.max(min.x(), Math.min(x, max.x()));
        double clampedZ = Math.max(min.z(), Math.min(z, max.z()));

        int y = from.getWorld().getHighestBlockYAt((int) clampedX, (int) clampedZ);

        return new Location(from.getWorld(), clampedX + 0.5, y + 1, clampedZ + 0.5);
    }

    public ProtectedRegion getRegionAt(Player p, Location loc) {
        RegionManager rm = getManager(loc.getWorld());
        if (rm == null) return null;

        ApplicableRegionSet set = rm.getApplicableRegions(BukkitAdapter.asBlockVector(loc));
        if (set == null || set.size() == 0) return null;

        UUID uid = p.getUniqueId();
        int cx = loc.getBlockX();
        int cz = loc.getBlockZ();

        List<ProtectedRegion> owned = new ArrayList<>();
        List<ProtectedRegion> all = new ArrayList<>();

        for (ProtectedRegion r : set) {
            all.add(r);
            if (r.getOwners().contains(uid)) owned.add(r);
        }

        if (!owned.isEmpty()) {
            for (ProtectedRegion r : owned) {
                BlockVector3 min = r.getMinimumPoint();
                BlockVector3 max = r.getMaximumPoint();
                if ((cx == min.x() && cz == min.z()) ||
                        (cx == min.x() && cz == max.z()) ||
                        (cx == max.x() && cz == min.z()) ||
                        (cx == max.x() && cz == max.z()))
                    return r;
            }

            for (ProtectedRegion r : owned) {
                BlockVector3 min = r.getMinimumPoint();
                BlockVector3 max = r.getMaximumPoint();
                if ((cz == min.z() && cx > min.x() && cx < max.x()) ||
                        (cz == max.z() && cx > min.x() && cx < max.x()) ||
                        (cx == min.x() && cz > min.z() && cz < max.z()) ||
                        (cx == max.x() && cz > min.z() && cz < max.z()))
                    return r;
            }

            ProtectedRegion best = owned.get(0);
            for (ProtectedRegion r : owned)
                if (getArea(r) < getArea(best)) best = r;
            return best;
        }

        ProtectedRegion best = all.get(0);
        for (ProtectedRegion r : all)
            if (getArea(r) < getArea(best)) best = r;

        return best;
    }

    public List<ProtectedRegion> getRegionsOwnedBy(Player p) {
        RegionManager rm = getManager(p.getWorld());
        if (rm == null) return List.of();

        List<ProtectedRegion> out = new ArrayList<>();
        UUID uid = p.getUniqueId();

        for (ProtectedRegion r : rm.getRegions().values())
            if (r.getOwners().contains(uid)) out.add(r);

        return out;
    }

    public Collection<ProtectedRegion> getAllRegions(World world) {
        RegionManager manager = getManager(world);
        if (manager == null) return List.of();
        return manager.getRegions().values();
    }

    public Location getRegionCenter(ProtectedRegion region, World world) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();

        double x = (min.x() + max.x()) / 2.0;
        double z = (min.z() + max.z()) / 2.0;

        int y = world.getHighestBlockYAt((int) x, (int) z);
        return new Location(world, x + 0.5, y + 1, z + 0.5);
    }

    public boolean isWithinRadius(ProtectedRegion region, Location origin, double radius) {
        Location center = getRegionCenter(region, origin.getWorld());
        return center.distance(origin) <= radius;
    }

    public double distanceToRegion(ProtectedRegion region, Location origin) {
        Location center = getRegionCenter(region, origin.getWorld());
        return center.distance(origin);
    }

    public ProtectedRegion getClosestRegion(Location origin, double radius) {
        World world = origin.getWorld();
        if (world == null) return null;

        return getAllRegions(world).stream()
                .filter(r -> isWithinRadius(r, origin, radius))
                .min(Comparator.comparingDouble(r -> distanceToRegion(r, origin)))
                .orElse(null);
    }

    public static class ProximityResult {
        private final ProtectedRegion other;

        public ProximityResult(ProtectedRegion other) {
            this.other = other;
        }

        public ProtectedRegion getOther() { return other; }
        public int getGap() { return 0; }
        public int getRequired() { return 0; }
    }

    public ProximityResult checkProximity(
            World world,
            BlockVector3 newMin,
            BlockVector3 newMax,
            ProtectedRegion ignore
    ) {
        RegionManager rm = getManager(world);
        if (rm == null) return null;

        int minX = Math.min(newMin.x(), newMax.x());
        int maxX = Math.max(newMin.x(), newMax.x());
        int minZ = Math.min(newMin.z(), newMax.z());
        int maxZ = Math.max(newMin.z(), newMax.z());

        for (ProtectedRegion other : rm.getRegions().values()) {
            if (other.getId().equalsIgnoreCase("__global__")) continue;
            if (ignore != null && other.getId().equals(ignore.getId())) continue;

            BlockVector3 oMin = other.getMinimumPoint();
            BlockVector3 oMax = other.getMaximumPoint();

            int oMinX = Math.min(oMin.x(), oMax.x());
            int oMaxX = Math.max(oMin.x(), oMax.x());
            int oMinZ = Math.min(oMin.z(), oMax.z());
            int oMaxZ = Math.max(oMin.z(), oMax.z());

            boolean overlapX = minX <= oMaxX && maxX >= oMinX;
            boolean overlapZ = minZ <= oMaxZ && maxZ >= oMinZ;

            if (overlapX && overlapZ)
                return new ProximityResult(other);
        }
        return null;
    }

    public ProtectedRegion createRegion(Player p, int radius) {
        Location loc = p.getLocation();
        World w = p.getWorld();

        int x = loc.getBlockX();
        int z = loc.getBlockZ();

        int minX = x - radius;
        int maxX = x + radius;
        int minZ = z - radius;
        int maxZ = z + radius;

        int minY = w.getMinHeight();
        int maxY = w.getMaxHeight() - 1;

        RegionManager rm = getManager(w);
        if (rm == null) {
            p.sendMessage(plugin.getMessages().msg("region_manager_null"));
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
                    .map(id -> Bukkit.getOfflinePlayer(id).getName())
                    .orElse("Unknown");

            p.sendMessage(
                    plugin.getMessages().msg("region_create_conflict")
                            .replace("%region%", pr.getOther().getId())
                            .replace("%owner%", owner)
            );
            return null;
        }

        String base = "claim_" + p.getUniqueId().toString().substring(0, 8);
        String id = base;
        int counter = 1;

        while (rm.hasRegion(id))
            id = base + "_" + counter++;

        ProtectedRegion region = new ProtectedCuboidRegion(id, newMin, newMax);
        region.getOwners().addPlayer(p.getUniqueId());

        rm.addRegion(region);
        save(w);

        return region;
    }

    public int getArea(ProtectedRegion r) {
        BlockVector3 min = r.getMinimumPoint();
        BlockVector3 max = r.getMaximumPoint();
        return (max.x() - min.x() + 1) * (max.z() - min.z() + 1);
    }

    public void save(World w) {
        try {
            RegionManager rm = getManager(w);
            if (rm != null) rm.save();
        } catch (Exception ignored) {}
    }
}
