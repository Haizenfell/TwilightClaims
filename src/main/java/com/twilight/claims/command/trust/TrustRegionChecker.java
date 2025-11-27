package com.twilight.claims.command.trust;

import com.twilight.claims.config.Messages;
import com.twilight.claims.wg.WGHook;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.entity.Player;

public class TrustRegionChecker {

    private final Messages msg;
    private final WGHook wg;

    public TrustRegionChecker(Messages msg, WGHook wg) {
        this.msg = msg;
        this.wg = wg;
    }

    public ProtectedRegion getRegionOwned(Player player) {
        ProtectedRegion region = wg.getRegionAt(player.getLocation());

        if (region == null) {
            msg.send(player, "no_region", x -> x);
            return null;
        }

        if (!region.getOwners().contains(player.getUniqueId())) {
            msg.send(player, "not_owner", x -> x);
            return null;
        }

        return region;
    }
}
