package com.twilight.claims.command.trust;

import com.twilight.claims.config.Messages;
import com.twilight.claims.TwilightClaims;
import com.twilight.claims.api.ClaimKey;
import com.twilight.claims.api.ClaimOperationException;
import com.twilight.claims.wg.ClaimIdentity;
import com.twilight.claims.wg.WGHook;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class TrustRemoveCommand {

    private final Messages msg;
    private final WGHook wg;

    public TrustRemoveCommand(Messages msg, WGHook wg) {
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
        if (!region.getMembers().contains(target.getUniqueId())) {
            msg.send(player, "trust_not_in_list", x -> x.replace("%player%", displayName));
            return;
        }

        try {
            TwilightClaims.getInstance().getApi().removeMember(new ClaimKey(player.getWorld().getUID(), region.getId()), target.getUniqueId());
        } catch (ClaimOperationException exception) {
            msg.send(player, exception.getReason() == ClaimOperationException.Reason.CANCELLED
                    ? "claim_action_cancelled" : "region_save_failed", text -> text);
            return;
        }

        msg.send(player, "trust_remove",
                x -> x.replace("%player%", displayName)
                        .replace("%region%", ClaimIdentity.displayName(region))
                        .replace("%owner%", TrustTargetResolver.displayName(player, "Unknown")));
    }
}
