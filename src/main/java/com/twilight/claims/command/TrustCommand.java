package com.twilight.claims.command;

import com.twilight.claims.command.trust.*;
import com.twilight.claims.config.Messages;
import com.twilight.claims.manager.ClaimManager;
import com.twilight.claims.wg.WGHook;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class TrustCommand {

    private final TrustRegionChecker regionChecker;
    private final TrustAddCommand add;
    private final TrustRemoveCommand remove;
    private final TrustListSelfCommand listSelf;
    private final TrustListOtherCommand listOther;

    public TrustCommand(Messages msg, WGHook wg, ClaimManager claim) {
        this.regionChecker = new TrustRegionChecker(msg, wg);
        this.add = new TrustAddCommand(msg, wg);
        this.remove = new TrustRemoveCommand(msg, wg);
        this.listSelf = new TrustListSelfCommand(msg);
        this.listOther = new TrustListOtherCommand(msg);
    }

    public boolean handle(Player player, String command, String[] args) {

        switch (command.toLowerCase()) {

            case "trust" -> {
                if (args.length < 1) return usage(player, "trust");
                var region = regionChecker.getRegionOwned(player);
                if (region != null) add.execute(player, region, args[0]);
                return true;
            }

            case "untrust" -> {
                if (args.length < 1) return usage(player, "untrust");
                var region = regionChecker.getRegionOwned(player);
                if (region != null) remove.execute(player, region, args[0]);
                return true;
            }

            case "trustlist" -> {
                if (args.length == 0) {
                    var region = regionChecker.getRegionOwned(player);
                    if (region != null) listSelf.execute(player, region);
                    return true;
                }

                OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
                Player online = target.getPlayer();

                if (online == null) {
                    var msg = regionChecker.getClass().getSimpleName();
                }

                var region = regionChecker.getRegionOwned(online);
                if (region != null) listOther.execute(player, target, region);
                return true;
            }

            default -> {
                player.sendMessage("§c[⚐] Unknown subcommand");
                return true;
            }
        }
    }

    private boolean usage(Player player, String label) {
        player.sendMessage("§c[⚐] /"+ label + " <player>");
        return true;
    }
}
