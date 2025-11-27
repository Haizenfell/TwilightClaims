package com.twilight.claims.manager;

import com.twilight.claims.TwilightClaims;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.wg.WGHook;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

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
        int base = 0;
        try {
            String val = PlaceholderAPI.setPlaceholders(player, "%yov_player_key:claim_points%");
            base = Integer.parseInt(val);
        } catch (Exception ignored) {}

        int hours = 0;
        try {
            String h = PlaceholderAPI.setPlaceholders(player, "%statistic_hours_played%");
            hours = Integer.parseInt(h);
        } catch (Exception ignored) {}

        return base + hours * cfg.getPerHourPoints();
    }

    public int getFree(Player player) {
        return getTotal(player) - getUsed(player);
    }

    public boolean canExpand(Player player, int deltaArea) {
        if (deltaArea <= 0) return true;
        return getFree(player) >= deltaArea;
    }
}
