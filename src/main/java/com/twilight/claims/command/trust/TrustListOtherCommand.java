package com.twilight.claims.command.trust;

import com.twilight.claims.config.Messages;
import com.twilight.claims.wg.ClaimIdentity;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

public class TrustListOtherCommand {

    private final Messages msg;

    public TrustListOtherCommand(Messages msg) {
        this.msg = msg;
    }

    public void execute(Player player, OfflinePlayer target, ProtectedRegion region) {
        Set<UUID> trusted = region.getMembers().getUniqueIds();
        String targetName = target.getName() == null ? "Unknown" : target.getName();

        msg.send(player, "trust_list_other_header",
                x -> x.replace("%player%", targetName)
                        .replace("%region%", ClaimIdentity.displayName(region))
                        .replace("%owner%", targetName));

        if (trusted.isEmpty()) {
            msg.send(player, "trust_list_other_empty",
                    x -> x.replace("%player%", targetName)
                            .replace("%region%", ClaimIdentity.displayName(region))
                            .replace("%owner%", targetName));
            return;
        }

        for (UUID uid : trusted) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uid);
            msg.send(player, "trust_list_entry",
                    x -> x.replace("%player%", op.getName() == null ? "Unknown" : op.getName()));
        }
    }
}
