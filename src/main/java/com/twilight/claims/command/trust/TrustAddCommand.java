package com.twilight.claims.command.trust;

import com.twilight.claims.config.Messages;
import com.twilight.claims.wg.WGHook;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.bukkit.Bukkit;
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
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

        if (target.getName() == null) {
            msg.send(player, "trust_not_found", x -> x.replace("%player%", targetName));
            return;
        }

        if (region.getMembers().contains(target.getUniqueId())) {
            msg.send(player, "trust_already", x -> x.replace("%player%", target.getName()));
            return;
        }

        region.getMembers().addPlayer(target.getUniqueId());
        wg.save(player.getWorld());

        msg.send(player, "trust_add",
                x -> x.replace("%player%", target.getName())
                        .replace("%region%", region.getId()));
    }
}
