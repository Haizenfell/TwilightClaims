package com.twilight.claims.command.claim;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.config.Messages;
import com.twilight.claims.manager.ClaimManager;
import com.twilight.claims.wg.WGHook;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

import java.util.List;

public class ClaimPointsCommand {

    private final ClaimsConfig cfg;
    private final Messages msg;
    private final ClaimManager claim;
    private final WGHook wg;

    public ClaimPointsCommand(ClaimsConfig cfg, Messages msg, ClaimManager claim, WGHook wg) {
        this.cfg = cfg;
        this.msg = msg;
        this.claim = claim;
        this.wg = wg;
    }

    public void execute(Player player) {
        int used = claim.getUsed(player);
        int total = claim.getTotal(player);
        int free = total - used;

        int purchased = 0;
        try {
            purchased = Integer.parseInt(
                    PlaceholderAPI.setPlaceholders(player, "%yov_player_key:claim_points%"));
        } catch (Exception ignored) {}

        int hours = 0;
        try {
            hours = Integer.parseInt(
                    PlaceholderAPI.setPlaceholders(player, "%statistic_hours_played%"));
        } catch (Exception ignored) {}

        int hoursPoints = hours * cfg.getPerHourPoints();

        List<ProtectedRegion> regions = wg.getRegionsOwnedBy(player);

        msg.send(player, "claim_points_header", s -> s);
        msg.send(player, "claim_points_free",
                s -> s.replace("%free_points%", String.valueOf(free)));
        msg.send(player, "claim_points_used",
                s -> s.replace("%points_used%", String.valueOf(used)));
        msg.send(player, "claim_points_total",
                s -> s.replace("%points_total%", String.valueOf(total)));

        final int purchasedFinal = purchased;
        msg.send(player, "claim_points_purchased",
                s -> s.replace("%purchased%", String.valueOf(purchasedFinal)));

        msg.send(player, "claim_points_hours",
                s -> s.replace("%hours_points%", String.valueOf(hoursPoints)));

        int index = 1;

        for (ProtectedRegion r : regions) {

            final int idx = index;
            final int areaFinal = wg.getArea(r);
            final String id = r.getId();

            msg.send(player, "claim_points_entry",
                    s -> s.replace("%index%", String.valueOf(idx))
                            .replace("%area%", String.valueOf(areaFinal))
                            .replace("%id%", id));

            index++;
        }

    }
}
