package com.twilight.claims.command.trust;

import com.twilight.claims.config.Messages;
import com.twilight.claims.wg.ClaimIdentity;
import com.twilight.claims.wg.WGHook;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class TrustAddCommand {

    private final Messages msg;
    private final WGHook wg;

    public TrustAddCommand(Messages msg, WGHook wg) {
        this.msg = msg;
        this.wg = wg;
    }

    public void execute(Player player, ProtectedRegion region, String targetName) {
        if (!wg.isOwner(player, region)) {
            msg.send(player, "not_owner", text -> text);
            return;
        }
        OfflinePlayer target = TrustTargetResolver.find(targetName);

        if (target == null) {
            msg.send(player, "trust_not_found", x -> x.replace("%player%", targetName));
            return;
        }

        String displayName = TrustTargetResolver.displayName(target, targetName);
        if (region.getMembers().contains(target.getUniqueId()) || region.getOwners().contains(target.getUniqueId())) {
            msg.send(player, "trust_already", x -> x.replace("%player%", displayName));
            return;
        }

        region.getMembers().addPlayer(target.getUniqueId());
        if (!wg.save(player.getWorld())) {
            region.getMembers().removePlayer(target.getUniqueId());
            msg.send(player, "region_save_failed", text -> text);
            return;
        }

        msg.send(player, "trust_add",
                x -> x.replace("%player%", displayName)
                        .replace("%region%", ClaimIdentity.displayName(region))
                        .replace("%owner%", TrustTargetResolver.displayName(player, "Unknown")));
    }
}
