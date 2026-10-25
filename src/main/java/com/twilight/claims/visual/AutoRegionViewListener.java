package com.twilight.claims.visual;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.TwilightClaims;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.wg.WGHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;

public class AutoRegionViewListener implements Listener {

    private final ClaimsConfig cfg;
    private final WGHook wg;
    private final RegionVisualizer visualizer;

    public AutoRegionViewListener(ClaimsConfig cfg, WGHook wg, RegionVisualizer visualizer) {
        this.cfg = cfg;
        this.wg = wg;
        this.visualizer = visualizer;

        Bukkit.getScheduler().runTaskTimer(
                TwilightClaims.getInstance(),
                this::tick,
                10,
                10
        );
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player);
        }
    }

    private void update(Player player) {
        if (visualizer.isErrorActive(player)) {
            return;
        }

        boolean holding = cfg.isShovel(player.getInventory().getItemInMainHand());

        if (!holding) {
            visualizer.clearAutomatic(player);
            return;
        }

        List<ProtectedRegion> owned = wg.getAllRegions(player.getWorld()).stream()
                .filter(wg::isClaim)
                .filter(region -> wg.isOwner(player, region))
                .toList();

        if (owned.isEmpty()) {
            visualizer.clearAutomatic(player);
            return;
        }

        visualizer.showList(player, owned, null);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        visualizer.clear(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        visualizer.clear(event.getPlayer());
    }
}
