package com.twilight.claims.visual;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.TwilightWGClaims;
import com.twilight.claims.tool.ShovelToolListener.CornerType;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class RegionVisualizer {

    private final TwilightWGClaims plugin;

    private final Material cornerMaterial;
    private final Material extendMaterial;
    private final Material cornerMaterialError;
    private final Material extendMaterialError;

    private final Map<UUID, List<Location>> activeNormal = new HashMap<>();
    private final Map<UUID, List<Location>> activeError = new HashMap<>();
    private final Map<UUID, BukkitTask> clearTasks = new HashMap<>();
    private final Map<UUID, Long> errorUntil = new HashMap<>();

    public RegionVisualizer(TwilightWGClaims plugin) {
        this.plugin = plugin;

        cornerMaterial = Material.matchMaterial(
                plugin.getConfig().getString("visual.corner-block", "GLOWSTONE")
        );

        extendMaterial = Material.matchMaterial(
                plugin.getConfig().getString("visual.extend-block", "GOLD_BLOCK")
        );

        cornerMaterialError = Material.matchMaterial(
                plugin.getConfig().getString("visual.corner-block_error", "SHROOMLIGHT")
        );

        extendMaterialError = Material.matchMaterial(
                plugin.getConfig().getString("visual.extend-block_error", "CRIMSON_NYLIUM")
        );
    }

    public void show(Player player, ProtectedRegion region, CornerType selectedCorner) {
        UUID id = player.getUniqueId();

        BukkitTask prev = clearTasks.remove(id);
        if (prev != null) prev.cancel();

        World world = player.getWorld();
        List<Location> locations = new ArrayList<>();
        List<Material> materials = new ArrayList<>();

        addRegionVisualization(world, region, selectedCorner, locations, materials);

        List<Location> current;
        if (isErrorActive(player)) {
            clearNormal(player);
            current = new ArrayList<>();
        } else {
            current = activeNormal.getOrDefault(id, new ArrayList<>());
        }

        current.addAll(locations);
        activeNormal.put(id, current);

        for (int i = 0; i < locations.size(); i++) {
            player.sendBlockChange(locations.get(i), materials.get(i).createBlockData());
        }
    }

    public void showError(Player player, ProtectedRegion region) {
        UUID id = player.getUniqueId();

        long duration = plugin.getConfig().getLong("visual.error-duration", 10);
        errorUntil.put(id, System.currentTimeMillis() + duration * 1000);

        BukkitTask prev = clearTasks.remove(id);
        if (prev != null) prev.cancel();

        World world = player.getWorld();
        List<Location> locations = new ArrayList<>();
        List<Material> materials = new ArrayList<>();

        addRegionVisualizationError(world, region, locations, materials);

        clearError(player);
        activeError.put(id, new ArrayList<>(locations));

        for (int i = 0; i < locations.size(); i++) {
            player.sendBlockChange(locations.get(i), materials.get(i).createBlockData());
        }
    }

    public boolean isErrorActive(Player player) {
        Long until = errorUntil.get(player.getUniqueId());
        if (until == null) return false;

        if (System.currentTimeMillis() > until) {
            errorUntil.remove(player.getUniqueId());
            return false;
        }

        return true;
    }

    public void showList(Player player, List<ProtectedRegion> regions, CornerType selectedCorner) {
        UUID id = player.getUniqueId();

        BukkitTask prev = clearTasks.remove(id);
        if (prev != null) prev.cancel();

        clearNormal(player);

        World world = player.getWorld();
        List<Location> locations = new ArrayList<>();
        List<Material> materials = new ArrayList<>();

        for (ProtectedRegion region : regions) {
            addRegionVisualization(world, region, selectedCorner, locations, materials);
        }

        for (int i = 0; i < locations.size(); i++) {
            player.sendBlockChange(locations.get(i), materials.get(i).createBlockData());
        }

        activeNormal.put(id, locations);
    }

    private void addRegionVisualization(World world, ProtectedRegion region, CornerType selectedCorner,
                                        List<Location> locations, List<Material> materials) {

        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();

        int sizeX = Math.abs(max.x() - min.x());
        int sizeZ = Math.abs(max.z() - min.z());
        boolean big = sizeX > 6 || sizeZ > 6;

        addCorner(locations, materials, world, min.x(), min.z(), CornerType.MIN_MIN, selectedCorner);
        addCorner(locations, materials, world, min.x(), max.z(), CornerType.MIN_MAX, selectedCorner);
        addCorner(locations, materials, world, max.x(), min.z(), CornerType.MAX_MIN, selectedCorner);
        addCorner(locations, materials, world, max.x(), max.z(), CornerType.MAX_MAX, selectedCorner);

        if (big) {
            addEdge(locations, materials, world, min.x(), min.z(), max.x(), min.z());
            addEdge(locations, materials, world, min.x(), max.z(), max.x(), max.z());
            addEdge(locations, materials, world, min.x(), min.z(), min.x(), max.z());
            addEdge(locations, materials, world, max.x(), min.z(), max.x(), max.z());
        }
    }

    private void addRegionVisualizationError(World world, ProtectedRegion region,
                                             List<Location> locations, List<Material> materials) {

        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();

        int sizeX = Math.abs(max.x() - min.x());
        int sizeZ = Math.abs(max.z() - min.z());
        boolean big = sizeX > 6 || sizeZ > 6;

        addCornerError(locations, materials, world, min.x(), min.z(), CornerType.MIN_MIN);
        addCornerError(locations, materials, world, min.x(), max.z(), CornerType.MIN_MAX);
        addCornerError(locations, materials, world, max.x(), min.z(), CornerType.MAX_MIN);
        addCornerError(locations, materials, world, max.x(), max.z(), CornerType.MAX_MAX);

        if (big) {
            addEdgeError(locations, materials, world, min.x(), min.z(), max.x(), min.z());
            addEdgeError(locations, materials, world, min.x(), max.z(), max.x(), max.z());
            addEdgeError(locations, materials, world, min.x(), min.z(), min.x(), max.z());
            addEdgeError(locations, materials, world, max.x(), min.z(), max.x(), max.z());
        }
    }

    private void addCorner(List<Location> locations, List<Material> materials,
                           World world, int x, int z,
                           CornerType type, CornerType selectedCorner) {

        int y = world.getHighestBlockYAt(x, z);

        if (selectedCorner != type) {
            locations.add(new Location(world, x, y, z));
            materials.add(cornerMaterial);
        }

        int dx = (type == CornerType.MIN_MIN || type == CornerType.MIN_MAX) ? 1 : -1;
        int dz = (type == CornerType.MIN_MIN || type == CornerType.MAX_MIN) ? 1 : -1;

        locations.add(new Location(world, x + dx, y, z));
        materials.add(extendMaterial);

        locations.add(new Location(world, x, y, z + dz));
        materials.add(extendMaterial);
    }

    private void addCornerError(List<Location> locations, List<Material> materials,
                                World world, int x, int z, CornerType type) {

        int y = world.getHighestBlockYAt(x, z);

        locations.add(new Location(world, x, y, z));
        materials.add(cornerMaterialError);

        int dx = (type == CornerType.MIN_MIN || type == CornerType.MIN_MAX) ? 1 : -1;
        int dz = (type == CornerType.MIN_MIN || type == CornerType.MAX_MIN) ? 1 : -1;

        locations.add(new Location(world, x + dx, y, z));
        materials.add(extendMaterialError);

        locations.add(new Location(world, x, y, z + dz));
        materials.add(extendMaterialError);
    }

    private void addEdge(List<Location> locations, List<Material> materials,
                         World world, int x1, int z1, int x2, int z2) {

        int dx = x2 - x1;
        int dz = z2 - z1;
        int length = Math.abs(dx) + Math.abs(dz);

        int threshold = plugin.getConfig().getInt("visual.edge-big-threshold", 10);
        int stepSmall = plugin.getConfig().getInt("visual.edge-step-small", 3);
        int stepBig = plugin.getConfig().getInt("visual.edge-step-big", 5);

        int step = length > threshold ? stepBig : stepSmall;

        for (int i = step; i < length; i += step) {
            int x = x1 + Integer.signum(dx) * i;
            int z = z1 + Integer.signum(dz) * i;
            int y = world.getHighestBlockYAt(x, z);

            locations.add(new Location(world, x, y, z));
            materials.add(extendMaterial);
        }
    }

    private void addEdgeError(List<Location> locations, List<Material> materials,
                              World world, int x1, int z1, int x2, int z2) {

        int dx = x2 - x1;
        int dz = z2 - z1;
        int length = Math.abs(dx) + Math.abs(dz);

        int threshold = plugin.getConfig().getInt("visual.edge-big-threshold", 10);
        int stepSmall = plugin.getConfig().getInt("visual.edge-step-small", 3);
        int stepBig = plugin.getConfig().getInt("visual.edge-step-big", 5);

        int step = length > threshold ? stepBig : stepSmall;

        for (int i = step; i < length; i += step) {
            int x = x1 + Integer.signum(dx) * i;
            int z = z1 + Integer.signum(dz) * i;
            int y = world.getHighestBlockYAt(x, z);

            locations.add(new Location(world, x, y, z));
            materials.add(extendMaterialError);
        }
    }

    private void clearNormal(Player player) {
        UUID id = player.getUniqueId();
        List<Location> list = activeNormal.remove(id);
        if (list == null) return;

        for (Location loc : list) {
            player.sendBlockChange(loc, loc.getBlock().getBlockData());
        }
    }

    private void clearError(Player player) {
        UUID id = player.getUniqueId();
        List<Location> list = activeError.remove(id);
        if (list == null) return;

        for (Location loc : list) {
            player.sendBlockChange(loc, loc.getBlock().getBlockData());
        }
    }

    public void clear(Player player, boolean delayed) {
        UUID id = player.getUniqueId();

        BukkitTask prev = clearTasks.remove(id);
        if (prev != null) prev.cancel();

        clearNormal(player);
        clearError(player);
    }
}
