package com.twilight.claims.command;

import com.twilight.claims.command.trust.*;
import com.twilight.claims.config.Messages;
import com.twilight.claims.wg.WGHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.Locale;

public class TrustCommand {

    private final Messages msg;
    private final TrustRegionChecker regionChecker;
    private final TrustAddCommand add;
    private final TrustRemoveCommand remove;
    private final TrustListSelfCommand listSelf;
    private final TrustListOtherCommand listOther;

    public TrustCommand(Messages msg, WGHook wg) {
        this.msg = msg;
        this.regionChecker = new TrustRegionChecker(msg, wg);
        this.add = new TrustAddCommand(msg, wg);
        this.remove = new TrustRemoveCommand(msg, wg);
        this.listSelf = new TrustListSelfCommand(msg);
        this.listOther = new TrustListOtherCommand(msg);
    }

    public boolean handle(Player player, String command, String[] args) {

        switch (command.toLowerCase(Locale.ROOT)) {

            case "trust" -> {
                if (args.length != 1) return usage(player, "trust");
                var region = regionChecker.getRegionOwned(player);
                if (region != null) add.execute(player, region, args[0]);
                return true;
            }

            case "untrust" -> {
                if (args.length != 1) return usage(player, "untrust");
                var region = regionChecker.getRegionOwned(player);
                if (region != null) remove.execute(player, region, args[0]);
                return true;
            }

            case "trustlist" -> {
                if (args.length > 1) return usage(player, "trustlist");
                if (args.length == 0) {
                    var region = regionChecker.getRegionOwned(player);
                    if (region != null) listSelf.execute(player, region);
                    return true;
                }

                Player online = findOnlinePlayer(args[0]);
                if (online == null) {
                    msg.send(player, "trust_target_not_online",
                            x -> x.replace("%player%", args[0]));
                    return true;
                }

                var region = regionChecker.getRegionOwned(player, online);
                if (region != null) listOther.execute(player, online, region);
                return true;
            }

            default -> {
                player.sendMessage("§c[⚐] Unknown subcommand");
                return true;
            }
        }
    }

    private Player findOnlinePlayer(String name) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(name)) {
                return online;
            }
        }
        return null;
    }

    private boolean usage(Player player, String label) {
        msg.send(player, "trust_usage", text -> text.replace("%label%", label));
        return true;
    }
}
