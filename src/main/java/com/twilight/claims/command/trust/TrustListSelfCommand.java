package com.twilight.claims.command.trust;

import com.twilight.claims.config.Messages;
import com.twilight.claims.wg.ClaimIdentity;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

public class TrustListSelfCommand {

    private final Messages msg;

    public TrustListSelfCommand(Messages msg) {
        this.msg = msg;
    }

    public void execute(Player player, ProtectedRegion region) {
        Set<UUID> trusted = region.getMembers().getUniqueIds();

        msg.send(player, "trust_list_header_region",
                x -> x.replace("%region%", ClaimIdentity.displayName(region))
                        .replace("%owner%", TrustTargetResolver.displayName(player, "Unknown")));

        if (trusted.isEmpty()) {
            msg.send(player, "trust_list_empty_region",
                    x -> x.replace("%region%", ClaimIdentity.displayName(region))
                            .replace("%owner%", TrustTargetResolver.displayName(player, "Unknown")));
            return;
        }

        for (UUID uid : trusted) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uid);
            msg.send(player, "trust_list_entry",
                    x -> x.replace("%player%", op.getName() == null ? "Unknown" : op.getName()));
        }
    }
}
