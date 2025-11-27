package com.twilight.claims.command.claim;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.config.Messages;
import com.twilight.claims.manager.ClaimManager;
import com.twilight.claims.visual.RegionVisualizer;
import com.twilight.claims.wg.WGHook;
import org.bukkit.entity.Player;

public class ClaimCreateCommand {

    private final ClaimsConfig cfg;
    private final Messages msg;
    private final WGHook wg;
    private final ClaimManager claim;
    private final RegionVisualizer visualizer;

    public ClaimCreateCommand(ClaimsConfig cfg, Messages msg, WGHook wg,
                              ClaimManager claim, RegionVisualizer visualizer) {
        this.cfg = cfg;
        this.msg = msg;
        this.wg = wg;
        this.claim = claim;
        this.visualizer = visualizer;
    }

    public void execute(Player player) {
        int radius = cfg.getMinRadius();
        int area = (radius * 2 + 1) * (radius * 2 + 1);
        int free = claim.getFree(player);

        if (free < area) {
            msg.send(player, "not_enough_points",
                    s -> s.replace("%need%", String.valueOf(area))
                            .replace("%free%", String.valueOf(free)));
            return;
        }

        ProtectedRegion region = wg.createRegion(player, radius);
        if (region == null) return;

        msg.send(player, "region_create",
                s -> s.replace("%id%", region.getId())
                        .replace("%area%", String.valueOf(area)));

        visualizer.show(player, region, null);
    }
}
