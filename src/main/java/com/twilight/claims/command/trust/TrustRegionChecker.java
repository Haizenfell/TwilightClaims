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
        return getRegionOwned(player, player);
    }

    public ProtectedRegion getRegionOwned(Player receiver, Player owner) {
        ProtectedRegion region = wg.getClaimAt(owner, owner.getLocation());

        if (region == null) {
            msg.send(receiver, "no_region", x -> x);
            return null;
        }

        if (!wg.isOwner(owner, region)) {
            msg.send(receiver, "not_owner", x -> x);
            return null;
        }

        return region;
    }
}
