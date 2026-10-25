package com.twilight.claims.command.claim;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.config.Messages;
import com.twilight.claims.manager.ClaimManager;
import com.twilight.claims.wg.ClaimIdentity;
import com.twilight.claims.wg.WGHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class ClaimInfoCommand {

    private final Messages msg;
    private final WGHook wg;
    private final ClaimManager claim;

    public ClaimInfoCommand(Messages msg, WGHook wg, ClaimManager claim) {
        this.msg = msg;
        this.wg = wg;
        this.claim = claim;
    }

    public void execute(Player player) {
        ProtectedRegion region = wg.getClaimAt(player, player.getLocation());
        if (region == null) {
            msg.send(player, "no_region", s -> s);
            return;
        }

        long area = wg.getArea(region);
        long used = claim.getUsed(player);
        long total = claim.getTotal(player);
        long free = claim.getFree(player);

        String ownerName = region.getOwners().getUniqueIds().stream()
                .findFirst()
                .map(id -> {
                    String name = Bukkit.getOfflinePlayer(id).getName();
                    return name == null ? id.toString() : name;
                })
                .orElse("Unknown");

        msg.sendList(player, "region_info",
                s -> s.replace("%id%", ClaimIdentity.displayName(region))
                        .replace("%area%", String.valueOf(area))
                        .replace("%used%", String.valueOf(used))
                        .replace("%total%", String.valueOf(total))
                        .replace("%free%", String.valueOf(free))
                        .replace("%owner%", ownerName));
    }
}
