package com.twilight.claims.command.claim;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.config.Messages;
import com.twilight.claims.wg.WGHook;
import org.bukkit.World;
import org.bukkit.entity.Player;

public class ClaimUnclaimCommand {

    private final Messages msg;
    private final WGHook wg;

    public ClaimUnclaimCommand(Messages msg, WGHook wg) {
        this.msg = msg;
        this.wg = wg;
    }

    public void execute(Player player) {
        ProtectedRegion region = wg.getRegionAt(player.getLocation());
        if (region == null) {
            msg.send(player, "no_region", s -> s);
            return;
        }

        if (!region.getOwners().contains(player.getUniqueId())) {
            msg.send(player, "not_owner", s -> s);
            return;
        }

        int area = wg.getArea(region);
        World world = player.getWorld();

        wg.getManager(world).removeRegion(region.getId());
        wg.save(world);

        msg.send(player, "region_delete",
                s -> s.replace("%area%", String.valueOf(area)));
    }
}
