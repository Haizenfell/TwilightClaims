package com.twilight.claims.tool;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.TwilightClaims;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.config.Messages;
import com.twilight.claims.manager.ClaimManager;
import com.twilight.claims.visual.RegionVisualizer;
import com.twilight.claims.wg.ClaimIdentity;
import com.twilight.claims.wg.WGHook;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class ShovelToolListener implements Listener {

    private final ClaimsConfig cfg;
    private final Messages msg;
    private final WGHook wg;
    private final ClaimManager claim;
    private final RegionVisualizer visualizer;

    private final Map<UUID, CornerSelection> selectedCorners = new HashMap<>();
    private final Map<UUID, SideSelection> selectedSides = new HashMap<>();

    public ShovelToolListener(ClaimsConfig cfg, Messages msg, WGHook wg,
                              ClaimManager claim, RegionVisualizer visualizer) {
        this.cfg = cfg;
        this.msg = msg;
        this.wg = wg;
        this.claim = claim;
        this.visualizer = visualizer;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (!event.hasBlock()) return;
        if (event.getItem() == null) return;
        if (!cfg.isShovel(event.getItem())) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        event.setCancelled(true);
        if (!player.hasPermission("twilight.claim.command.claim")) {
            sendAction(player, msg.msg("no_permission"));
            return;
        }
        UUID id = player.getUniqueId();
        Location click = event.getClickedBlock().getLocation();

        CornerSelection cornerSel = selectedCorners.remove(id);
        if (cornerSel != null) {
            moveCorner(player, cornerSel, click);
            event.setCancelled(true);
            return;
        }

        SideSelection sideSel = selectedSides.remove(id);
        if (sideSel != null) {
            moveSide(player, sideSel, click);
            event.setCancelled(true);
            return;
        }

        ProtectedRegion region = wg.getClaimAt(player, click);
        if (region == null) {
            sendAction(player, msg.msg("no_region"));
            event.setCancelled(true);
            return;
        }

        if (!wg.isOwner(player, region)) {
            visualizer.showError(player, region);
            sendAction(player, msg.msg("not_owner"));
            event.setCancelled(true);
            return;
        }

        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();

        int x = click.getBlockX();
        int z = click.getBlockZ();

        CornerType clickedCorner = detectCorner(min, max, x, z);
        SideType clickedSide = clickedCorner == null ? detectSide(min, max, x, z) : null;

        if (clickedCorner != null) {
            selectedSides.remove(id);
            selectedCorners.put(id, new CornerSelection(player.getWorld().getUID(), region.getId(), clickedCorner));

            String text = formatRegionMessage(msg.msg("select_corner"), player, region)
                    .replace("%corner%", formatCornerName(clickedCorner));
            sendAction(player, text);

            visualizer.show(player, region, clickedCorner);
            event.setCancelled(true);
            return;
        }

        if (clickedSide != null) {
            selectedCorners.remove(id);
            selectedSides.put(id, new SideSelection(player.getWorld().getUID(), region.getId(), clickedSide));

            String text = formatRegionMessage(msg.msg("select_side"), player, region)
                    .replace("%side%", formatSideName(clickedSide));
            sendAction(player, text);

            visualizer.show(player, region, null);
            event.setCancelled(true);
            return;
        }

        visualizer.show(player, region, null);
        sendAction(player, formatRegionMessage(msg.msg("click_inside_region"), player, region));
        event.setCancelled(true);
    }

    private CornerType detectCorner(BlockVector3 min, BlockVector3 max, int x, int z) {
        if (x == min.x() && z == min.z()) return CornerType.MIN_MIN;
        if (x == min.x() && z == max.z()) return CornerType.MIN_MAX;
        if (x == max.x() && z == min.z()) return CornerType.MAX_MIN;
        if (x == max.x() && z == max.z()) return CornerType.MAX_MAX;
        return null;
    }

    private SideType detectSide(BlockVector3 min, BlockVector3 max, int x, int z) {
        if (z == min.z() && x > min.x() && x < max.x()) return SideType.MIN_Z;
        if (z == max.z() && x > min.x() && x < max.x()) return SideType.MAX_Z;
        if (x == min.x() && z > min.z() && z < max.z()) return SideType.MIN_X;
        if (x == max.x() && z > min.z() && z < max.z()) return SideType.MAX_X;
        return null;
    }

    private void moveCorner(Player player, CornerSelection sel, Location loc) {
        if (!sel.worldId.equals(loc.getWorld().getUID())) {
            sendAction(player, msg.msg("no_region"));
            return;
        }
        var manager = wg.getManager(loc.getWorld());
        if (manager == null) {
            sendAction(player, msg.msg("region_manager_null")
                    .replace("%world%", loc.getWorld().getName()));
            return;
        }

        ProtectedRegion old = manager.getRegion(sel.regionId);
        if (!canResize(player, old)) return;

        BlockVector3 min = old.getMinimumPoint();
        BlockVector3 max = old.getMaximumPoint();

        int x = loc.getBlockX();
        int z = loc.getBlockZ();

        switch (sel.type) {
            case MIN_MIN -> min = BlockVector3.at(x, min.y(), z);
            case MIN_MAX -> {
                min = BlockVector3.at(x, min.y(), min.z());
                max = BlockVector3.at(max.x(), max.y(), z);
            }
            case MAX_MIN -> {
                max = BlockVector3.at(x, max.y(), max.z());
                min = BlockVector3.at(min.x(), min.y(), z);
            }
            case MAX_MAX -> max = BlockVector3.at(x, max.y(), z);
        }

        recreate(player, old, min, max);
    }

    private void moveSide(Player player, SideSelection sel, Location loc) {
        if (!sel.worldId.equals(loc.getWorld().getUID())) {
            sendAction(player, msg.msg("no_region"));
            return;
        }
        var manager = wg.getManager(loc.getWorld());
        if (manager == null) {
            sendAction(player, msg.msg("region_manager_null")
                    .replace("%world%", loc.getWorld().getName()));
            return;
        }

        ProtectedRegion old = manager.getRegion(sel.regionId);
        if (!canResize(player, old)) return;

        BlockVector3 min = old.getMinimumPoint();
        BlockVector3 max = old.getMaximumPoint();

        int x = loc.getBlockX();
        int z = loc.getBlockZ();

        switch (sel.type) {
            case MIN_X -> min = BlockVector3.at(x, min.y(), min.z());
            case MAX_X -> max = BlockVector3.at(x, max.y(), max.z());
            case MIN_Z -> min = BlockVector3.at(min.x(), min.y(), z);
            case MAX_Z -> max = BlockVector3.at(max.x(), max.y(), z);
        }

        recreate(player, old, min, max);
    }

    private void recreate(Player player, ProtectedRegion old, BlockVector3 min, BlockVector3 max) {
        if (!canResize(player, old)) return;
        int minX = Math.min(min.x(), max.x());
        int maxX = Math.max(min.x(), max.x());
        int minZ = Math.min(min.z(), max.z());
        int maxZ = Math.max(min.z(), max.z());

        long sizeX = (long) maxX - minX + 1;
        long sizeZ = (long) maxZ - minZ + 1;

        int minimumSize = cfg.getMinClaimSize();
        if (sizeX < minimumSize || sizeZ < minimumSize) {
            String text = msg.msg("region_too_small")
                    .replace("%size%", String.valueOf(minimumSize))
                    .replace("%min%", minimumSize + "x" + minimumSize);
            sendAction(player, text);
            return;
        }

        World world = player.getWorld();
        int limit = 29_999_984;
        double y = player.getLocation().getY();
        if (minX < -limit || maxX > limit || minZ < -limit || maxZ > limit
                || !world.getWorldBorder().isInside(new Location(world, minX + 0.5, y, minZ + 0.5))
                || !world.getWorldBorder().isInside(new Location(world, maxX + 0.5, y, maxZ + 0.5))) {
            sendAction(player, msg.msg("region_invalid_size"));
            return;
        }
        var manager = wg.getManager(world);
        if (manager == null) {
            sendAction(player, msg.msg("region_manager_null")
                    .replace("%world%", world.getName()));
            return;
        }

        long oldArea = wg.getArea(old);
        long newArea;
        try {
            newArea = Math.multiplyExact(sizeX, sizeZ);
        } catch (ArithmeticException ex) {
            sendAction(player, msg.msg("not_enough_points")
                    .replace("%need%", String.valueOf(Long.MAX_VALUE))
                    .replace("%free%", String.valueOf(claim.getFree(player))));
            return;
        }
        long delta = newArea - oldArea;

        if (!claim.canExpand(player, delta)) {
            String text = msg.msg("not_enough_points")
                    .replace("%need%", String.valueOf(delta))
                    .replace("%free%", String.valueOf(claim.getFree(player)));
            sendAction(player, text);
            return;
        }

        BlockVector3 newMin = BlockVector3.at(minX, min.y(), minZ);
        BlockVector3 newMax = BlockVector3.at(maxX, max.y(), maxZ);

        WGHook.ProximityResult pr = wg.checkProximity(world, newMin, newMax, old);
        if (pr != null) {
            visualizer.showError(player, pr.getOther());

            String ownerName = pr.getOther().getOwners().getUniqueIds().stream()
                    .findFirst()
                    .map(id -> {
                        String name = Bukkit.getOfflinePlayer(id).getName();
                        return name == null ? id.toString() : name;
                    })
                    .orElse("Unknown");

            String text = msg.msg("region_too_close")
                    .replace("%other%", ClaimIdentity.displayName(pr.getOther()))
                    .replace("%distance%", "0")
                    .replace("%required%", "0")
                    .replace("%owner%", ownerName);

            sendAction(player, text);
            return;
        }

        ProtectedRegion nr = new ProtectedCuboidRegion(old.getId(), newMin, newMax);
        nr.copyFrom(old);

        manager.addRegion(nr);
        if (!wg.save(world)) {
            manager.addRegion(old);
            sendAction(player, msg.msg("region_save_failed"));
            return;
        }

        String text = formatRegionMessage(msg.msg("moved_corner"), player, nr)
                .replace("%area%", String.valueOf(newArea));
        sendAction(player, text);

        visualizer.show(player, nr, null);
    }

    public enum CornerType {
        MIN_MIN, MIN_MAX, MAX_MIN, MAX_MAX
    }

    private enum SideType {
        MIN_X, MAX_X, MIN_Z, MAX_Z
    }

    private String formatCornerName(CornerType type) {
        return switch (type) {
            case MIN_MIN -> "X : X";
            case MIN_MAX -> "X : Z";
            case MAX_MAX -> "Z : Z";
            case MAX_MIN -> "Z : X";
        };
    }

    private String formatSideName(SideType type) {
        return switch (type) {
            case MIN_X -> "X1";
            case MAX_X -> "X2";
            case MIN_Z -> "Z1";
            case MAX_Z -> "Z2";
        };
    }

    private boolean canResize(Player player, ProtectedRegion region) {
        if (region == null || !wg.isClaim(region)) {
            sendAction(player, msg.msg("no_region"));
            return false;
        }
        if (!wg.isOwner(player, region)) {
            sendAction(player, msg.msg("not_owner"));
            return false;
        }
        return true;
    }

    private String formatRegionMessage(String text, Player owner, ProtectedRegion region) {
        return text.replace("%id%", ClaimIdentity.displayName(region))
                .replace("%region%", ClaimIdentity.displayName(region))
                .replace("%owner%", Objects.toString(owner.getName(), "Unknown"));
    }

    private final Map<UUID, BukkitTask> actionTasks = new HashMap<>();

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearPlayer(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        clearPlayer(event.getPlayer());
    }

    private void clearPlayer(Player player) {
        UUID id = player.getUniqueId();
        selectedCorners.remove(id);
        selectedSides.remove(id);
        BukkitTask task = actionTasks.remove(id);
        if (task != null) task.cancel();
    }

    private void sendAction(Player player, String text) {
        if (text == null || text.isEmpty()) return;

        int duration = Math.max(0, TwilightClaims.getInstance().getConfig()
                .getInt("actionbar.duration-ticks", 40));
        UUID id = player.getUniqueId();
        BukkitTask previous = actionTasks.remove(id);
        if (previous != null) previous.cancel();

        var component = LegacyComponentSerializer.legacySection().deserialize(text);
        player.sendActionBar(component);
        if (duration <= 10) return;

        BukkitTask task = new BukkitRunnable() {
            private long elapsed = 10;

            @Override
            public void run() {
                if (!player.isOnline() || elapsed >= duration) {
                    actionTasks.remove(id);
                    cancel();
                    return;
                }
                player.sendActionBar(component);
                elapsed += 10;
            }
        }.runTaskTimer(TwilightClaims.getInstance(), 10L, 10L);
        actionTasks.put(id, task);
    }

    private record CornerSelection(UUID worldId, String regionId, CornerType type) {}
    private record SideSelection(UUID worldId, String regionId, SideType type) {}
}
