package com.twilight.claims.tool;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.config.Messages;
import com.twilight.claims.manager.ClaimManager;
import com.twilight.claims.visual.RegionVisualizer;
import com.twilight.claims.wg.WGHook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ShovelToolListener implements Listener {

    private final ClaimsConfig cfg;
    private final Messages msg;
    private final WGHook wg;
    private final ClaimManager claim;
    private final RegionVisualizer visualizer;

    private final Map<UUID, CornerSelection> selectedCorners = new HashMap<>();
    private final Map<UUID, SideSelection> selectedSides = new HashMap<>();

    public ShovelToolListener(ClaimsConfig cfg, Messages msg, WGHook wg, ClaimManager claim, RegionVisualizer visualizer) {
        this.cfg = cfg;
        this.msg = msg;
        this.wg = wg;
        this.claim = claim;
        this.visualizer = visualizer;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (!event.hasBlock()) return;
        if (event.getItem() == null) return;
        if (!cfg.isShovel(event.getItem())) return;
        if (!event.getAction().toString().contains("RIGHT_CLICK")) return;

        Player player = event.getPlayer();
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

        ProtectedRegion region = wg.getRegionAt(player, click);
        if (region == null) {
            msg.send(player, "no_region", s -> s);
            event.setCancelled(true);
            return;
        }

        UUID ownerId = player.getUniqueId();
        if (!region.getOwners().contains(ownerId)) {
            visualizer.showError(player, region);
            msg.send(player, "not_owner", s -> s);
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
            selectedCorners.put(id, new CornerSelection(region.getId(), clickedCorner));

            msg.send(player, "select_corner",
                    s -> s.replace("%corner%", formatCornerName(clickedCorner)));

            visualizer.show(player, region, clickedCorner);
            event.setCancelled(true);
            return;
        }

        if (clickedSide != null) {
            selectedCorners.remove(id);
            selectedSides.put(id, new SideSelection(region.getId(), clickedSide));

            msg.send(player, "select_side",
                    s -> s.replace("%side%", formatSideName(clickedSide)));

            visualizer.show(player, region, null);
            event.setCancelled(true);
            return;
        }

        visualizer.show(player, region, null);
        msg.send(player, "click_inside_region", s -> s);
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
        ProtectedRegion old = wg.getManager(loc.getWorld()).getRegion(sel.regionId);
        if (old == null) return;

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
        ProtectedRegion old = wg.getManager(loc.getWorld()).getRegion(sel.regionId);
        if (old == null) return;

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
        int minX = Math.min(min.x(), max.x());
        int maxX = Math.max(min.x(), max.x());
        int minZ = Math.min(min.z(), max.z());
        int maxZ = Math.max(min.z(), max.z());

        int sizeX = maxX - minX + 1;
        int sizeZ = maxZ - minZ + 1;

        if (sizeX < 10 || sizeZ < 10) {
            msg.send(player, "region_too_small",
                    s -> s.replace("%min%", "10x10"));
            return;
        }

        World world = player.getWorld();
        var manager = wg.getManager(world);

        int oldArea = wg.getArea(old);
        int newArea = sizeX * sizeZ;
        int delta = newArea - oldArea;

        if (!claim.canExpand(player, delta)) {
            msg.send(player, "not_enough_points",
                    s -> s.replace("%need%", String.valueOf(delta))
                            .replace("%free%", String.valueOf(claim.getFree(player))));
            return;
        }

        BlockVector3 newMin = BlockVector3.at(minX, min.y(), minZ);
        BlockVector3 newMax = BlockVector3.at(maxX, max.y(), maxZ);

        WGHook.ProximityResult pr = wg.checkProximity(world, newMin, newMax, old);
        if (pr != null) {
            visualizer.showError(player, pr.getOther());

            String ownerName = pr.getOther().getOwners().getUniqueIds().stream()
                    .findFirst()
                    .map(id -> Bukkit.getOfflinePlayer(id).getName())
                    .orElse("Unknown");

            msg.send(player, "region_too_close",
                    s -> s.replace("%other%", pr.getOther().getId())
                            .replace("%distance%", "0")
                            .replace("%required%", "0")
                            .replace("%owner%", ownerName));
            return;
        }

        String id = old.getId();
        var owners = old.getOwners();
        var members = old.getMembers();
        var flags = old.getFlags();

        manager.removeRegion(id);

        ProtectedRegion nr = new ProtectedCuboidRegion(id, newMin, newMax);
        nr.setOwners(owners);
        nr.setMembers(members);
        nr.setFlags(flags);

        manager.addRegion(nr);
        wg.save(world);

        msg.send(player, "moved_corner",
                s -> s.replace("%area%", String.valueOf(newArea)));

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

    private record CornerSelection(String regionId, CornerType type) {}
    private record SideSelection(String regionId, SideType type) {}
}
