package com.twilight.claims.visual;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.TwilightClaims;
import com.twilight.claims.api.BoundaryStyle;
import com.twilight.claims.tool.ShovelToolListener.CornerType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Optional;

public class RegionVisualizer {

    private static final int MAX_MARKERS = 8192;
    private static final int MAX_EDGE_SAMPLES = 4096;

    private final TwilightClaims plugin;
    private final Material cornerMaterial;
    private final Material extendMaterial;
    private final Material selectedCornerMaterial;
    private final Material cornerMaterialError;
    private final Material extendMaterialError;
    private final BoundaryStyle defaultStyle;
    private final double maxDistance;
    private final double maxDistanceSq;
    private final boolean onlyLoadedChunks;
    private final int edgeThreshold;
    private final int stepSmall;
    private final int stepBig;
    private final Map<Material, BlockData> blockData = new EnumMap<>(Material.class);
    private final Map<UUID, View> views = new HashMap<>();
    private final Map<String, String> warnedStyles = new HashMap<>();

    public RegionVisualizer(TwilightClaims plugin) {
        this.plugin = plugin;
        cornerMaterial = configuredBlock("visual.corner-block", Material.GLOWSTONE);
        extendMaterial = configuredBlock("visual.extend-block", Material.GOLD_BLOCK);
        selectedCornerMaterial = configuredBlock("visual.pos1-block", Material.DIAMOND_BLOCK);
        cornerMaterialError = configuredBlock("visual.corner-block_error", Material.SHROOMLIGHT);
        extendMaterialError = configuredBlock("visual.extend-block_error", Material.CRIMSON_NYLIUM);
        defaultStyle = new BoundaryStyle(cornerMaterial, extendMaterial, selectedCornerMaterial,
                cornerMaterialError, extendMaterialError);

        double configuredDistance = plugin.getConfig().getDouble("visual.max-distance", 64.0);
        maxDistance = Double.isFinite(configuredDistance) ? configuredDistance : 64.0;
        maxDistanceSq = maxDistance > 0 ? maxDistance * maxDistance : -1.0;
        onlyLoadedChunks = plugin.getConfig().getBoolean("visual.only-loaded-chunks", true);
        edgeThreshold = Math.max(0, plugin.getConfig().getInt("visual.edge-big-threshold", 10));
        stepSmall = Math.max(1, plugin.getConfig().getInt("visual.edge-step-small", 3));
        stepBig = Math.max(1, plugin.getConfig().getInt("visual.edge-step-big", 5));
    }

    private Material configuredBlock(String path, Material fallback) {
        String name = plugin.getConfig().getString(path, fallback.name());
        Material material = name == null ? null : Material.matchMaterial(name);
        if (material == null || !material.isBlock() || material.isAir()) {
            plugin.getLogger().warning("Invalid block material at " + path + "; using " + fallback);
            material = fallback;
        }
        try {
            blockData.computeIfAbsent(material, Material::createBlockData);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid block material at " + path + "; using " + fallback);
            material = fallback;
            blockData.computeIfAbsent(material, Material::createBlockData);
        }
        return material;
    }

    public BoundaryStyle getDefaultBoundaryStyle() {
        return defaultStyle;
    }

    public Optional<BoundaryStyle> getBoundaryStyleOverride(ProtectedRegion region) {
        String value = BoundaryStyles.raw(region);
        if (value == null) {
            warnedStyles.remove(region.getId());
            return Optional.empty();
        }
        try {
            BoundaryStyle style = BoundaryStyles.decode(value);
            warnedStyles.remove(region.getId());
            return Optional.of(style);
        } catch (IllegalArgumentException ex) {
            if (!value.equals(warnedStyles.put(region.getId(), value))) {
                plugin.getLogger().warning("Invalid boundary style on WorldGuard region " + region.getId()
                        + "; using configured defaults: " + ex.getMessage());
            }
            return Optional.empty();
        }
    }

    public BoundaryStyle getBoundaryStyle(ProtectedRegion region) {
        return getBoundaryStyleOverride(region).orElse(defaultStyle);
    }

    public boolean isVisualizing(Player player) {
        return views.containsKey(player.getUniqueId());
    }

    public void refreshRegion(World world, ProtectedRegion region) {
        for (var entry : new ArrayList<>(views.entrySet())) {
            View view = entry.getValue();
            if (!view.worldId.equals(world.getUID())) continue;
            if (!containsRegion(view.normalRegions, region.getId()) && !containsRegion(view.errorRegions, region.getId())) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            if (!view.worldId.equals(player.getWorld().getUID())) {
                clear(player);
                continue;
            }
            view.normalRegions = replaceRegion(view.normalRegions, region);
            view.errorRegions = replaceRegion(view.errorRegions, region);
            view.normal = render(player, view.normalRegions, view, false);
            view.error = render(player, view.errorRegions, view, true);
            refresh(player, view);
        }
    }

    public void clearRegion(World world, String regionId) {
        for (var entry : new ArrayList<>(views.entrySet())) {
            View view = entry.getValue();
            if (!view.worldId.equals(world.getUID())) continue;
            if (!containsRegion(view.normalRegions, regionId) && !containsRegion(view.errorRegions, regionId)) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            if (!view.worldId.equals(player.getWorld().getUID())) {
                clear(player);
                continue;
            }
            view.normalRegions = view.normalRegions.stream().filter(region -> !region.getId().equals(regionId)).toList();
            view.errorRegions = view.errorRegions.stream().filter(region -> !region.getId().equals(regionId)).toList();
            if (regionId.equals(view.selectedRegion)) {
                view.selectedRegion = null;
                view.selectedCorner = null;
            }
            if (view.normalRegions.isEmpty()) {
                cancel(view.normalClear);
                view.normalClear = null;
                view.manualNormal = false;
            }
            if (view.errorRegions.isEmpty()) {
                cancel(view.errorClear);
                view.errorClear = null;
            }
            view.normal = render(player, view.normalRegions, view, false);
            view.error = render(player, view.errorRegions, view, true);
            refresh(player, view);
            discardIfEmpty(entry.getKey(), view);
        }
    }

    private static boolean containsRegion(List<ProtectedRegion> regions, String id) {
        return regions.stream().anyMatch(region -> region.getId().equals(id));
    }

    private static List<ProtectedRegion> replaceRegion(List<ProtectedRegion> regions, ProtectedRegion replacement) {
        return regions.stream().map(region -> region.getId().equals(replacement.getId()) ? replacement : region).toList();
    }

    public void show(Player player, ProtectedRegion region, CornerType selectedCorner) {
        show(player, region, selectedCorner, Math.max(0, plugin.getConfig().getLong("visual.visual-duration-ticks", 0)));
    }

    public void show(Player player, ProtectedRegion region, CornerType selectedCorner, long durationTicks) {
        if (durationTicks < 0 || durationTicks > Long.MAX_VALUE / 2) {
            throw new IllegalArgumentException("Invalid visualization duration");
        }
        View view = view(player);
        cancel(view.normalClear);
        view.normalClear = null;
        view.manualNormal = true;
        view.selectedRegion = selectedCorner == null ? null : region.getId();
        view.selectedCorner = selectedCorner;
        view.normalRegions = List.of(region);
        view.normal = render(player, view.normalRegions, view, false);
        refresh(player, view);

        if (durationTicks > 0) {
            UUID id = player.getUniqueId();
            view.normalClear = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                view.normalClear = null;
                view.manualNormal = false;
                view.normalRegions = List.of();
                view.normal.clear();
                view.selectedRegion = null;
                view.selectedCorner = null;
                refreshOnline(id, view);
                discardIfEmpty(id, view);
            }, durationTicks);
        }
    }

    public void showList(Player player, List<ProtectedRegion> regions, CornerType selectedCorner) {
        View view = view(player);
        cancel(view.normalClear);
        view.normalClear = null;
        view.manualNormal = false;
        if (selectedCorner != null) view.selectedCorner = selectedCorner;
        view.normalRegions = List.copyOf(regions);
        view.normal = render(player, view.normalRegions, view, false);
        refresh(player, view);
        discardIfEmpty(player.getUniqueId(), view);
    }

    public void showListTemporary(Player player, List<ProtectedRegion> regions, long durationTicks) {
        if (durationTicks < 0 || durationTicks > Long.MAX_VALUE / 2) {
            throw new IllegalArgumentException("Invalid visualization duration");
        }
        List<ProtectedRegion> requestedRegions = List.copyOf(regions);
        View view = view(player);
        cancel(view.normalClear);
        view.normalClear = null;
        view.manualNormal = true;
        view.selectedRegion = null;
        view.selectedCorner = null;
        view.normalRegions = requestedRegions;
        view.normal = render(player, view.normalRegions, view, false);
        refresh(player, view);
        if (durationTicks > 0) {
            UUID id = player.getUniqueId();
            view.normalClear = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                view.normalClear = null;
                view.manualNormal = false;
                view.normalRegions = List.of();
                view.normal.clear();
                view.selectedRegion = null;
                view.selectedCorner = null;
                refreshOnline(id, view);
                discardIfEmpty(id, view);
            }, durationTicks);
        }
    }

    public void showErrorTemporary(Player player, ProtectedRegion region, int seconds) {
        showError(player, region, seconds);
    }

    public void showErrorTemporary(Player player, ProtectedRegion region, long seconds) {
        showError(player, region, seconds);
    }

    public void showError(Player player, ProtectedRegion region) {
        showError(player, region, plugin.getConfig().getLong("visual.error-duration", 10));
    }

    private void showError(Player player, ProtectedRegion region, long seconds) {
        if (seconds <= 0) return;
        View view = view(player);
        cancel(view.errorClear);
        view.errorRegions = List.of(region);
        view.error = render(player, view.errorRegions, view, true);
        refresh(player, view);

        UUID id = player.getUniqueId();
        view.errorClear = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            view.errorClear = null;
            view.errorRegions = List.of();
            view.error.clear();
            refreshOnline(id, view);
            discardIfEmpty(id, view);
        }, Math.min(seconds, Long.MAX_VALUE / 40) * 20L);
    }

    public boolean isErrorActive(Player player) {
        View view = views.get(player.getUniqueId());
        return view != null && view.errorClear != null;
    }

    private View view(Player player) {
        UUID id = player.getUniqueId();
        View previous = views.get(id);
        if (previous != null && !previous.worldId.equals(player.getWorld().getUID())) {
            clear(player);
        }
        return views.computeIfAbsent(id, ignored -> new View(player.getWorld().getUID()));
    }

    private Map<Location, Material> render(Player player, List<ProtectedRegion> regions, View view, boolean error) {
        Map<Location, Material> markers = new LinkedHashMap<>();
        Location origin = player.getLocation();
        World world = player.getWorld();
        for (ProtectedRegion region : regions) {
            if (markers.size() >= MAX_MARKERS) break;
            BoundaryStyle style = getBoundaryStyle(region);
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            CornerType selected = region.getId().equals(view.selectedRegion) ? view.selectedCorner : null;
            boolean extendX = min.x() < max.x();
            boolean extendZ = min.z() < max.z();
            addCorner(markers, world, min.x(), min.z(), CornerType.MIN_MIN, selected, origin, error, extendX, extendZ, style);
            addCorner(markers, world, min.x(), max.z(), CornerType.MIN_MAX, selected, origin, error, extendX, extendZ, style);
            addCorner(markers, world, max.x(), min.z(), CornerType.MAX_MIN, selected, origin, error, extendX, extendZ, style);
            addCorner(markers, world, max.x(), max.z(), CornerType.MAX_MAX, selected, origin, error, extendX, extendZ, style);
            if ((long) max.x() - min.x() > 6 || (long) max.z() - min.z() > 6) {
                Material material = error ? style.errorEdge() : style.edge();
                addEdge(markers, world, min.x(), min.z(), max.x(), min.z(), material, origin);
                addEdge(markers, world, min.x(), max.z(), max.x(), max.z(), material, origin);
                addEdge(markers, world, min.x(), min.z(), min.x(), max.z(), material, origin);
                addEdge(markers, world, max.x(), min.z(), max.x(), max.z(), material, origin);
            }
            if (selected != null && !error) {
                int x = selected == CornerType.MIN_MIN || selected == CornerType.MIN_MAX ? min.x() : max.x();
                int z = selected == CornerType.MIN_MIN || selected == CornerType.MAX_MIN ? min.z() : max.z();
                addMarker(markers, world, x, z, style.selectedCorner(), origin, true);
            }
        }
        return markers;
    }

    private void addCorner(Map<Location, Material> markers, World world, int x, int z, CornerType type,
                           CornerType selected, Location origin, boolean error, boolean extendX, boolean extendZ,
                           BoundaryStyle style) {
        Material material = error ? style.errorCorner() : selected == type ? style.selectedCorner() : style.corner();
        addMarker(markers, world, x, z, material, origin, true);
        int dx = type == CornerType.MIN_MIN || type == CornerType.MIN_MAX ? 1 : -1;
        int dz = type == CornerType.MIN_MIN || type == CornerType.MAX_MIN ? 1 : -1;
        if (extendX) addMarker(markers, world, (long) x + dx, z, error ? style.errorEdge() : style.edge(), origin);
        if (extendZ) addMarker(markers, world, x, (long) z + dz, error ? style.errorEdge() : style.edge(), origin);
    }

    private void addEdge(Map<Location, Material> markers, World world, int x1, int z1, int x2, int z2,
                         Material material, Location origin) {
        long dx = (long) x2 - x1;
        long dz = (long) z2 - z1;
        long length = Math.abs(dx) + Math.abs(dz);
        int step = length > edgeThreshold ? stepBig : stepSmall;
        int sx = Long.signum(dx);
        int sz = Long.signum(dz);
        long first = step;
        long last = length - 1;
        if (maxDistance > 0) {
            double offset = sx == 0 ? origin.getZ() - z1 : origin.getX() - x1;
            int direction = sx == 0 ? sz : sx;
            long visibleStart = (long) Math.ceil(offset * direction - maxDistance);
            first = Math.max(first, alignedOffset(visibleStart, step));
            last = Math.min(last, (long) Math.floor(offset * direction + maxDistance));
        }
        int samples = 0;
        for (long i = first; i <= last && samples < MAX_EDGE_SAMPLES && markers.size() < MAX_MARKERS;
             i += step, samples++) {
            addMarker(markers, world, x1 + sx * i, z1 + sz * i, material, origin);
        }
    }

    static long alignedOffset(long start, int step) {
        long positiveStart = Math.max(1L, start);
        return ((positiveStart - 1) / Math.max(1, step) + 1) * Math.max(1, step);
    }

    private void addMarker(Map<Location, Material> markers, World world, long x, long z,
                           Material material, Location origin) {
        addMarker(markers, world, x, z, material, origin, false);
    }

    private void addMarker(Map<Location, Material> markers, World world, long x, long z,
                           Material material, Location origin, boolean corner) {
        if (markers.size() >= MAX_MARKERS || x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
                || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) return;
        double dx = origin.getX() - x;
        double dz = origin.getZ() - z;
        if (maxDistanceSq > 0 && dx * dx + dz * dz > maxDistanceSq) return;
        int blockX = (int) x;
        int blockZ = (int) z;
        if (onlyLoadedChunks && !world.isChunkLoaded(blockX >> 4, blockZ >> 4)) return;
        int y = world.getHighestBlockYAt(blockX, blockZ);
        Location location = new Location(world, blockX, y, blockZ);
        if (corner) markers.put(location, material);
        else markers.putIfAbsent(location, material);
    }

    private void refreshOnline(UUID id, View view) {
        Player player = Bukkit.getPlayer(id);
        if (player != null && player.isOnline()) refresh(player, view);
    }

    private void refresh(Player player, View view) {
        Map<Location, Material> next = new LinkedHashMap<>(view.normal);
        next.putAll(view.error);
        for (Location location : view.rendered.keySet()) {
            if (!next.containsKey(location)) restore(player, location);
        }
        for (var marker : next.entrySet()) {
            if (marker.getValue() != view.rendered.get(marker.getKey())) {
                player.sendBlockChange(marker.getKey(), blockData.computeIfAbsent(marker.getValue(), Material::createBlockData));
            }
        }
        view.rendered = next;
    }

    private void restore(Player player, Location location) {
        World world = location.getWorld();
        if (player.isOnline() && world != null && world.equals(player.getWorld())
                && world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            player.sendBlockChange(location, location.getBlock().getBlockData());
        }
    }

    public void clear(Player player) {
        View view = views.remove(player.getUniqueId());
        if (view == null) return;
        cancel(view.normalClear);
        cancel(view.errorClear);
        for (Location location : view.rendered.keySet()) restore(player, location);
    }

    public void clearAutomatic(Player player) {
        View view = views.get(player.getUniqueId());
        if (view == null || view.manualNormal) return;
        view.normal.clear();
        view.normalRegions = List.of();
        view.selectedRegion = null;
        view.selectedCorner = null;
        refresh(player, view);
        discardIfEmpty(player.getUniqueId(), view);
    }

    public void close() {
        for (var entry : views.entrySet()) {
            cancel(entry.getValue().normalClear);
            cancel(entry.getValue().errorClear);
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                for (Location location : entry.getValue().rendered.keySet()) restore(player, location);
            }
        }
        views.clear();
        warnedStyles.clear();
    }

    private void discardIfEmpty(UUID id, View view) {
        if (view.selectedRegion == null && view.normal.isEmpty() && view.error.isEmpty()
                && view.normalClear == null && view.errorClear == null) views.remove(id, view);
    }

    private static void cancel(BukkitTask task) {
        if (task != null) task.cancel();
    }

    private static final class View {
        private final UUID worldId;
        private Map<Location, Material> normal = new LinkedHashMap<>();
        private Map<Location, Material> error = new LinkedHashMap<>();
        private Map<Location, Material> rendered = new LinkedHashMap<>();
        private List<ProtectedRegion> normalRegions = List.of();
        private List<ProtectedRegion> errorRegions = List.of();
        private BukkitTask normalClear;
        private BukkitTask errorClear;
        private boolean manualNormal;
        private String selectedRegion;
        private CornerType selectedCorner;

        private View(UUID worldId) {
            this.worldId = worldId;
        }
    }
}
