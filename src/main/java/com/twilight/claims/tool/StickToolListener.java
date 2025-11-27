package com.twilight.claims.tool;

import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.config.Messages;
import com.twilight.claims.wg.WGHook;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;

public class StickToolListener implements Listener {

    private final ClaimsConfig cfg;
    private final Messages msg;
    private final WGHook wg;

    public StickToolListener(ClaimsConfig cfg, Messages msg, WGHook wg) {
        this.cfg = cfg;
        this.msg = msg;
        this.wg = wg;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (!event.hasBlock()) return;
        if (event.getItem() == null) return;
        if (!cfg.isStick(event.getItem())) return;

        event.setCancelled(true);

        Player player = event.getPlayer();
        Location location = event.getClickedBlock().getLocation();

        ProtectedRegion region = wg.getRegionAt(location);
        if (region == null) {
            msg.send(player, "inspect_unclaimed", s -> s);
            return;
        }

        String owner = region.getOwners().getUniqueIds().stream()
                .findFirst()
                .map(id -> Bukkit.getOfflinePlayer(id).getName())
                .orElse("Unknown");

        int area = wg.getArea(region);

        msg.sendList(player, "inspect_claim",
                s -> s.replace("%id%", region.getId())
                        .replace("%owner%", owner)
                        .replace("%area%", String.valueOf(area)));
    }
}
