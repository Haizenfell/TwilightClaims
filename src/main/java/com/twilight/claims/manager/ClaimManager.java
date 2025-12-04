package com.twilight.claims.manager;

import com.twilight.claims.TwilightClaims;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.wg.WGHook;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

import java.util.Map;

public class ClaimManager {

    private final TwilightClaims plugin;
    private final WGHook wg;
    private final ClaimsConfig cfg;

    public ClaimManager(TwilightClaims plugin, WGHook wg, ClaimsConfig cfg) {
        this.plugin = plugin;
        this.wg = wg;
        this.cfg = cfg;
    }

    public int getUsed(Player player) {
        return wg.getRegionsOwnedBy(player).stream()
                .mapToInt(wg::getArea)
                .sum();
    }

    public int getTotal(Player player) {
        int purchased = getPurchasedPoints(player);
        int hoursPlayed = getHoursPlayed(player);

        int pointsPerHour = getPointsPerHourFromGroup(player);

        return purchased + hoursPlayed * pointsPerHour;
    }

    public int getFree(Player player) {
        return getTotal(player) - getUsed(player);
    }

    public boolean canExpand(Player player, int deltaArea) {
        if (deltaArea <= 0) return true;
        return getFree(player) >= deltaArea;
    }

    private int getPurchasedPoints(Player player) {
        try {
            return Integer.parseInt(
                    PlaceholderAPI.setPlaceholders(player, "%yov_player_key:claim_points%")
            );
        } catch (Exception ignored) {}
        return 0;
    }

    private int getHoursPlayed(Player player) {
        try {
            return Integer.parseInt(
                    PlaceholderAPI.setPlaceholders(player, "%statistic_hours_played%")
            );
        } catch (Exception ignored) {}
        return 0;
    }

    public int getPointsPerHourFromGroup(Player player) {
        Map<String, Integer> groups = cfg.getGroupPointsMap();
        int max = 0;
        String basePerm = "twilight.claim.points.";
        for (Map.Entry<String, Integer> entry : groups.entrySet()) {
            String key = entry.getKey();
            int value = entry.getValue();

            String permission = basePerm + key;

            if (player.hasPermission(permission) && value > max) {
                max = value;
            }
        }
        if (max == 0) {
            return cfg.getPerHourPoints();
        }
        return max;
    }

}
