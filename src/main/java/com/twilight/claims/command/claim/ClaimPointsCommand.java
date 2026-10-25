package com.twilight.claims.command.claim;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.config.Messages;
import com.twilight.claims.manager.ClaimManager;
import com.twilight.claims.wg.ClaimIdentity;
import com.twilight.claims.wg.WGHook;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;

public class ClaimPointsCommand {

    private final Messages msg;
    private final ClaimManager claim;
    private final WGHook wg;

    public ClaimPointsCommand(Messages msg, ClaimManager claim, WGHook wg) {
        this.msg = msg;
        this.claim = claim;
        this.wg = wg;
    }

    public void execute(Player player) {
        long used = claim.getUsed(player);
        long total = claim.getTotal(player);
        long free = claim.getFree(player);

        int purchased = claim.getPurchasedPoints(player);
        int perHour = claim.getPointsPerHourFromGroup(player);
        long hoursPoints = claim.getHoursPoints(player);


        List<ProtectedRegion> regions = wg.getRegionsOwnedBy(player);

        msg.send(player, "claim_points_header", s -> s);
        msg.send(player, "claim_points_free",
                s -> s.replace("%free_points%", String.valueOf(free)));
        msg.send(player, "claim_points_used",
                s -> s.replace("%points_used%", String.valueOf(used)));
        msg.send(player, "claim_points_total",
                s -> s.replace("%points_total%", String.valueOf(total)));
        msg.send(player, "claim_points_per_hour",
                s -> s.replace("%per_hour%", String.valueOf(perHour)));
        final int purchasedFinal = purchased;
        msg.send(player, "claim_points_purchased",
                s -> s.replace("%purchased%", String.valueOf(purchasedFinal)));
        msg.send(player, "claim_points_hours",
                s -> s.replace("%hours_points%", String.valueOf(hoursPoints)));

        int index = 1;

        for (ProtectedRegion r : regions) {

            final int idx = index;
            final long areaFinal = wg.getArea(r);
            final String id = ClaimIdentity.displayName(r);

            msg.send(player, "claim_points_entry",
                    s -> s.replace("%index%", String.valueOf(idx))
                            .replace("%area%", String.valueOf(areaFinal))
                            .replace("%id%", id)
                            .replace("%owner%", Objects.toString(player.getName(), "Unknown")));

            index++;
        }

    }
}
