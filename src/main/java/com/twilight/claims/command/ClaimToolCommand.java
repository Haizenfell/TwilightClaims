package com.twilight.claims.command;

import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.config.Messages;
import org.bukkit.entity.Player;

public class ClaimToolCommand {

    private final ClaimsConfig cfg;
    private final Messages msg;

    public ClaimToolCommand(ClaimsConfig cfg, Messages msg) {
        this.cfg = cfg;
        this.msg = msg;
    }

    public void giveShovel(Player player) {
        if (!player.hasPermission("twilight.claim.admin")) {
            msg.send(player, "no_permission", s -> s);
            return;
        }

        player.getInventory().addItem(cfg.getShovelItem());
        msg.send(player, "claimtool_give_shovel", s -> s);
    }

    public void giveStick(Player player) {
        if (!player.hasPermission("twilight.claim.admin")) {
            msg.send(player, "no_permission", s -> s);
            return;
        }

        player.getInventory().addItem(cfg.getStickItem());
        msg.send(player, "claimtool_give_stick", s -> s);
    }

    public void usage(Player player, String label) {
        msg.send(player, "claimtool_usage", s -> s.replace("%label%", label));
    }
}
