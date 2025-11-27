package com.twilight.claims.command.claim;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.config.Messages;
import com.twilight.claims.wg.WGHook;
import org.bukkit.entity.Player;

import java.util.List;

public class ClaimListCommand {

    private final Messages msg;
    private final WGHook wg;

    public ClaimListCommand(Messages msg, WGHook wg) {
        this.msg = msg;
        this.wg = wg;
    }

    public void execute(Player player) {
        List<ProtectedRegion> list = wg.getRegionsOwnedBy(player);

        if (list.isEmpty()) {
            msg.send(player, "claim_list_empty", s -> s);
            return;
        }

        player.sendMessage(msg.msg("claim_list_header"));

        for (ProtectedRegion region : list) {
            int area = wg.getArea(region);

            player.sendMessage(
                    msg.msg("claim_list_entry")
                            .replace("%id%", region.getId())
                            .replace("%area%", String.valueOf(area)));
        }
    }
}
