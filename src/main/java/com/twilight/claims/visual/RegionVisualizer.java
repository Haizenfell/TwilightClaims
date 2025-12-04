package com.twilight.claims.visual;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.TwilightClaims;
import com.twilight.claims.tool.ShovelToolListener.CornerType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class RegionVisualizer {

    private final TwilightClaims plugin;

    private final Material cornerMaterial;
    private final Material extendMaterial;
    private final Material cornerMaterialError;
    private final Material extendMaterialError;

    // настройки видимости
    private final double maxDistanceSq;
    private final boolean onlyLoadedChunks;

    private final Map<UUID, List<Location>> activeNormal = new HashMap<>();
    private final Map<UUID, List<Location>> activeError = new HashMap<>();
    private final Map<UUID, BukkitTask> clearTasks = new HashMap<>();
    private final Map<UUID, Long> errorUntil = new HashMap<>();

    public RegionVisualizer(TwilightClaims plugin) {
        this.plugin = plugin;

        cornerMaterial = Optional.ofNullable(Material.matchMaterial(
                plugin.getConfig().getString("visual.corner-block", "GLOWSTONE")
        )).orElse(Material.GLOWSTONE);

        extendMaterial = Optional.ofNullable(Material.matchMaterial(
                plugin.getConfig().getString("visual.extend-block", "GOLD_BLOCK")
        )).orElse(Material.GOLD_BLOCK);

        cornerMaterialError = Optional.ofNullable(Material.matchMaterial(
                plugin.getConfig().getString("visual.corner-block_error", "SHROOMLIGHT")
        )).orElse(Material.SHROOMLIGHT);

        extendMaterialError = Optional.ofNullable(Material.matchMaterial(
                plugin.getConfig().getString("visual.extend-block_error", "CRIMSON_NYLIUM")
        )).orElse(Material.CRIMSON_NYLIUM);

        double maxDist = plugin.getConfig().getDouble("visual.max-distance", 64.0);
        this.maxDistanceSq = maxDist > 0 ? maxDist * maxDist : -1.0;
        this.onlyLoadedChunks = plugin.getConfig().getBoolean("visual.only-loaded-chunks", true);
    }

    public void show(Player player, ProtectedRegion region, CornerType selectedCorner) {
        UUID id = player.getUniqueId();

        BukkitTask prev = clearTasks.remove(id);
        if (prev != null) prev.cancel();

        World world = player.getWorld();
        List<Location> locations = new ArrayList<>();
        List<Material> materials = new ArrayList<>();
        Location origin = player.getLocation();

        addRegionVisualization(world, region, selectedCorner, locations, materials, origin);

        List<Location> current;
        if (isErrorActive(player)) {
            clearNormal(player);
            current = new ArrayList<>();
        } else {
            current = activeNormal.getOrDefault(id, new ArrayList<>());
        }

        current.addAll(locations);
        activeNormal.put(id, current);

        sendBlockChanges(player, locations, materials);
    }

    public void showErrorTemporary(Player player, ProtectedRegion region, int seconds) {
        World world = player.getWorld();
        List<Location> locations = new ArrayList<>();
        List<Material> materials = new ArrayList<>();
        Location origin = player.getLocation();

        addRegionVisualizationError(world, region, locations, materials, origin);

        sendBlockChanges(player, locations, materials);

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Location loc : locations) {
                player.sendBlockChange(loc, loc.getBlock().getBlockData());
            }
        }, seconds * 20L);
    }

    public void showErrorNearby(Player player, ProtectedRegion region) {
        int sec = plugin.getConfig().getInt("visual.stick-error-highlight-seconds", 3);
        showErrorTemporary(player, region, sec);
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
        Location origin = player.getLocation();

        addRegionVisualizationError(world, region, locations, materials, origin);

        clearError(player);
        activeError.put(id, new ArrayList<>(locations));

        sendBlockChanges(player, locations, materials);
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
        Location origin = player.getLocation();

        for (ProtectedRegion region : regions) {
            addRegionVisualization(world, region, selectedCorner, locations, materials, origin);
        }

        sendBlockChanges(player, locations, materials);

        activeNormal.put(id, locations);
    }

    // ====== ВИЗУАЛИЗАЦИЯ РЕГИОНА C ФИЛЬТРОМ ДИСТАНЦИИ / ЧАНКОВ ======

    private void addRegionVisualization(
            World world,
            ProtectedRegion region,
            CornerType selectedCorner,
            List<Location> locations,
            List<Material> materials,
            Location origin
    ) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();

        int sizeX = Math.abs(max.x() - min.x());
        int sizeZ = Math.abs(max.z() - min.z());
        boolean big = sizeX > 6 || sizeZ > 6;

        addCorner(locations, materials, world, min.x(), min.z(), CornerType.MIN_MIN, selectedCorner, origin);
        addCorner(locations, materials, world, min.x(), max.z(), CornerType.MIN_MAX, selectedCorner, origin);
        addCorner(locations, materials, world, max.x(), min.z(), CornerType.MAX_MIN, selectedCorner, origin);
        addCorner(locations, materials, world, max.x(), max.z(), CornerType.MAX_MAX, selectedCorner, origin);

        if (big) {
            addEdge(locations, materials, world, min.x(), min.z(), max.x(), min.z(), origin);
            addEdge(locations, materials, world, min.x(), max.z(), max.x(), max.z(), origin);
            addEdge(locations, materials, world, min.x(), min.z(), min.x(), max.z(), origin);
            addEdge(locations, materials, world, max.x(), min.z(), max.x(), max.z(), origin);
        }
    }

    private void addRegionVisualizationError(
            World world,
            ProtectedRegion region,
            List<Location> locations,
            List<Material> materials,
            Location origin
    ) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();

        int sizeX = Math.abs(max.x() - min.x());
        int sizeZ = Math.abs(max.z() - min.z());
        boolean big = sizeX > 6 || sizeZ > 6;

        addCornerError(locations, materials, world, min.x(), min.z(), CornerType.MIN_MIN, origin);
        addCornerError(locations, materials, world, min.x(), max.z(), CornerType.MIN_MAX, origin);
        addCornerError(locations, materials, world, max.x(), min.z(), CornerType.MAX_MIN, origin);
        addCornerError(locations, materials, world, max.x(), max.z(), CornerType.MAX_MAX, origin);

        if (big) {
            addEdgeError(locations, materials, world, min.x(), min.z(), max.x(), min.z(), origin);
            addEdgeError(locations, materials, world, min.x(), max.z(), max.x(), max.z(), origin);
            addEdgeError(locations, materials, world, min.x(), min.z(), min.x(), max.z(), origin);
            addEdgeError(locations, materials, world, max.x(), min.z(), max.x(), max.z(), origin);
        }
    }

    // ====== УГЛЫ / РЁБРА С ФИЛЬТРОМ ======

    private void addCorner(
            List<Location> locations,
            List<Material> materials,
            World world,
            int x,
            int z,
            CornerType type,
            CornerType selectedCorner,
            Location origin
    ) {
        if (selectedCorner != type) {
            addLocationIfVisible(locations, materials, world, x, z, cornerMaterial, origin);
        }

        int dx = (type == CornerType.MIN_MIN || type == CornerType.MIN_MAX) ? 1 : -1;
        int dz = (type == CornerType.MIN_MIN || type == CornerType.MAX_MIN) ? 1 : -1;

        addLocationIfVisible(locations, materials, world, x + dx, z, extendMaterial, origin);
        addLocationIfVisible(locations, materials, world, x, z + dz, extendMaterial, origin);
    }

    private void addCornerError(
            List<Location> locations,
            List<Material> materials,
            World world,
            int x,
            int z,
            CornerType type,
            Location origin
    ) {
        addLocationIfVisible(locations, materials, world, x, z, cornerMaterialError, origin);

        int dx = (type == CornerType.MIN_MIN || type == CornerType.MIN_MAX) ? 1 : -1;
        int dz = (type == CornerType.MIN_MIN || type == CornerType.MAX_MIN) ? 1 : -1;

        addLocationIfVisible(locations, materials, world, x + dx, z, extendMaterialError, origin);
        addLocationIfVisible(locations, materials, world, x, z + dz, extendMaterialError, origin);
    }

    private void addEdge(
            List<Location> locations,
            List<Material> materials,
            World world,
            int x1,
            int z1,
            int x2,
            int z2,
            Location origin
    ) {
        int dx = x2 - x1;
        int dz = z2 - z1;
        int length = Math.abs(dx) + Math.abs(dz);

        int threshold = plugin.getConfig().getInt("visual.edge-big-threshold", 10);
        int stepSmall = plugin.getConfig().getInt("visual.edge-step-small", 3);
        int stepBig = plugin.getConfig().getInt("visual.edge-step-big", 5);

        int step = length > threshold ? stepBig : stepSmall;

        int sx = Integer.signum(dx);
        int sz = Integer.signum(dz);

        for (int i = step; i < length; i += step) {
            int x = x1 + sx * i;
            int z = z1 + sz * i;

            addLocationIfVisible(locations, materials, world, x, z, extendMaterial, origin);
        }
    }

    private void addEdgeError(
            List<Location> locations,
            List<Material> materials,
            World world,
            int x1,
            int z1,
            int x2,
            int z2,
            Location origin
    ) {
        int dx = x2 - x1;
        int dz = z2 - z1;
        int length = Math.abs(dx) + Math.abs(dz);

        int threshold = plugin.getConfig().getInt("visual.edge-big-threshold", 10);
        int stepSmall = plugin.getConfig().getInt("visual.edge-step-small", 3);
        int stepBig = plugin.getConfig().getInt("visual.edge-step-big", 5);

        int step = length > threshold ? stepBig : stepSmall;

        int sx = Integer.signum(dx);
        int sz = Integer.signum(dz);

        for (int i = step; i < length; i += step) {
            int x = x1 + sx * i;
            int z = z1 + sz * i;

            addLocationIfVisible(locations, materials, world, x, z, extendMaterialError, origin);
        }
    }

    // ====== ХЕЛПЕРЫ ВИДИМОСТИ / ОТПРАВКИ ======

    private boolean isVisible(World world, int x, int z, Location origin) {
        if (origin != null && maxDistanceSq > 0 && origin.getWorld() == world) {
            double dx = origin.getX() - x;
            double dz = origin.getZ() - z;
            if ((dx * dx + dz * dz) > maxDistanceSq) {
                return false;
            }
        }

        if (onlyLoadedChunks) {
            int cx = x >> 4;
            int cz = z >> 4;
            if (!world.isChunkLoaded(cx, cz)) {
                return false;
            }
        }

        return true;
    }

    private void addLocationIfVisible(
            List<Location> locations,
            List<Material> materials,
            World world,
            int x,
            int z,
            Material material,
            Location origin
    ) {
        if (!isVisible(world, x, z, origin)) return;

        int y = world.getHighestBlockYAt(x, z);
        locations.add(new Location(world, x, y, z));
        materials.add(material);
    }

    private void sendBlockChanges(Player player, List<Location> locations, List<Material> materials) {
        for (int i = 0; i < locations.size(); i++) {
            player.sendBlockChange(locations.get(i), materials.get(i).createBlockData());
        }
    }

    // ====== ОЧИСТКА ======

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
