package com.twilight.claims.command.claim;

import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.config.Messages;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Map;

public class ClaimToolCommand {

    private final ClaimsConfig cfg;
    private final Messages msg;

    public ClaimToolCommand(ClaimsConfig cfg, Messages msg) {
        this.cfg = cfg;
        this.msg = msg;
    }

    public void execute(Player player, String[] args) {
        if (args.length != 2) {
            usage(player);
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "shovel" -> {
                give(player, cfg.getShovelItem());
                msg.send(player, "claimtool_give_shovel", s -> s);
            }
            case "stick" -> {
                give(player, cfg.getStickItem());
                msg.send(player, "claimtool_give_stick", s -> s);
            }
            default -> usage(player);
        }
    }

    private void usage(Player player) {
        msg.send(player, "claimtool_usage",
                s -> s.replace("%label%", "claim tool"));
    }

    private void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack stack : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), stack);
        }
    }
}
