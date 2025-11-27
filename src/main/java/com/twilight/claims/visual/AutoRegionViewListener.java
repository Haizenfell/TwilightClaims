package com.twilight.claims.visual;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.TwilightClaims;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.tool.ShovelToolListener.CornerType;
import com.twilight.claims.wg.WGHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AutoRegionViewListener implements Listener {

    private final ClaimsConfig cfg;
    private final WGHook wg;
    private final RegionVisualizer visualizer;

    private final Map<UUID, CornerType> selectedCorner = new HashMap<>();

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
            visualizer.clear(player, false);
            return;
        }

        List<ProtectedRegion> owned = wg.getRegionsOwnedBy(player);

        if (owned.isEmpty()) {
            visualizer.clear(player, false);
            return;
        }

        CornerType sel = selectedCorner.get(player.getUniqueId());
        visualizer.showList(player, owned, sel);
    }
}
