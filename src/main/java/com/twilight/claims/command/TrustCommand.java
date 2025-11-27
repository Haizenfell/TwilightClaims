package com.twilight.claims.command;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.config.Messages;
import com.twilight.claims.manager.ClaimManager;
import com.twilight.claims.wg.WGHook;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.UUID;

public class TrustCommand {

    private final Messages msg;
    private final WGHook wg;
    private final ClaimManager claim;

    public TrustCommand(Messages msg, WGHook wg, ClaimManager claim) {
        this.msg = msg;
        this.wg = wg;
        this.claim = claim;
    }

    public boolean handle(Player player, String command, String[] args) {
        switch (command.toLowerCase()) {

            case "trust" -> {
                if (args.length < 1) {
                    msg.send(player, "trust_usage", s -> s.replace("%label%", "trust"));
                    return true;
                }
                trust(player, args[0]);
                return true;
            }

            case "untrust" -> {
                if (args.length < 1) {
                    msg.send(player, "trust_usage", s -> s.replace("%label%", "untrust"));
                    return true;
                }
                untrust(player, args[0]);
                return true;
            }

            case "trustlist" -> {
                if (args.length == 0) {
                    trustlistSelf(player);
                    return true;
                }
                trustlistOther(player, args[0]);
                return true;
            }

            default -> {
                player.sendMessage("§c[!] Unknown subcommand");
                return true;
            }
        }
    }

    private void trust(Player player, String targetName) {
        ProtectedRegion region = checkRegionAndOwner(player);
        if (region == null) return;

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (target.getName() == null) {
            msg.send(player, "trust_not_found", s -> s.replace("%player%", targetName));
            return;
        }

        if (region.getMembers().contains(target.getUniqueId())) {
            msg.send(player, "trust_already", s -> s.replace("%player%", target.getName()));
            return;
        }

        region.getMembers().addPlayer(target.getUniqueId());
        wg.save(player.getWorld());

        msg.send(player, "trust_add",
                s -> s.replace("%player%", target.getName())
                        .replace("%region%", region.getId()));
    }

    private void untrust(Player player, String targetName) {
        ProtectedRegion region = checkRegionAndOwner(player);
        if (region == null) return;

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (target.getName() == null) {
            msg.send(player, "trust_not_found", s -> s.replace("%player%", targetName));
            return;
        }

        if (!region.getMembers().contains(target.getUniqueId())) {
            msg.send(player, "trust_not_in_list", s -> s.replace("%player%", target.getName()));
            return;
        }

        region.getMembers().removePlayer(target.getUniqueId());
        wg.save(player.getWorld());

        msg.send(player, "trust_remove",
                s -> s.replace("%player%", target.getName())
                        .replace("%region%", region.getId()));
    }

    private void trustlistSelf(Player player) {
        ProtectedRegion region = wg.getRegionAt(player.getLocation());
        if (region == null) {
            msg.send(player, "no_region", s -> s);
            return;
        }

        Set<UUID> trusted = region.getMembers().getUniqueIds();
        msg.send(player, "trust_list_header_region",
                s -> s.replace("%region%", region.getId()));

        if (trusted.isEmpty()) {
            msg.send(player, "trust_list_empty_region",
                    s -> s.replace("%region%", region.getId()));
            return;
        }

        for (UUID uid : trusted) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uid);
            msg.send(player, "trust_list_entry",
                    s -> s.replace("%player%", op.getName() == null ? "Unknown" : op.getName()));
        }
    }

    private void trustlistOther(Player player, String targetName) {
        if (!player.hasPermission("twilight.claim.admin")) {
            msg.send(player, "no_permission", s -> s);
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
        if (target.getName() == null) {
            msg.send(player, "trust_not_found", s -> s.replace("%player%", targetName));
            return;
        }

        Player online = target.getPlayer();
        if (online == null) {
            msg.send(player, "trust_target_not_online",
                    s -> s.replace("%player%", target.getName()));
            return;
        }

        ProtectedRegion region = wg.getRegionAt(online.getLocation());
        if (region == null) {
            msg.send(player, "no_region_other",
                    s -> s.replace("%player%", target.getName()));
            return;
        }

        Set<UUID> trusted = region.getMembers().getUniqueIds();
        msg.send(player, "trust_list_other_header",
                s -> s.replace("%player%", target.getName())
                        .replace("%region%", region.getId()));

        if (trusted.isEmpty()) {
            msg.send(player, "trust_list_other_empty",
                    s -> s.replace("%player%", target.getName()));
            return;
        }

        for (UUID uid : trusted) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(uid);
            msg.send(player, "trust_list_entry",
                    s -> s.replace("%player%", op.getName() == null ? "Unknown" : op.getName()));
        }
    }

    private ProtectedRegion checkRegionAndOwner(Player player) {
        ProtectedRegion region = wg.getRegionAt(player.getLocation());

        if (region == null) {
            msg.send(player, "no_region", s -> s);
            return null;
        }

        if (!region.getOwners().contains(player.getUniqueId())) {
            msg.send(player, "not_owner", s -> s);
            return null;
        }

        return region;
    }
}
