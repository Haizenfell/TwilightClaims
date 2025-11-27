package com.twilight.claims.placeholder;

import com.twilight.claims.TwilightClaims;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.manager.ClaimManager;
import me.clip.placeholderapi.PlaceholderAPI;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

public class TwilightClaimsExpansion extends PlaceholderExpansion {

    private final TwilightClaims plugin;
    private final ClaimManager claim;
    private final ClaimsConfig cfg;

    public TwilightClaimsExpansion(TwilightClaims plugin,
                                   ClaimManager claim,
                                   ClaimsConfig cfg) {
        this.plugin = plugin;
        this.claim = claim;
        this.cfg = cfg;
    }

    @Override
    public String getIdentifier() {
        // %tc_*%
        return "tc";
    }

    @Override
    public String getAuthor() {
        return "Haizenfell";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onPlaceholderRequest(Player p, String params) {
        if (p == null) {
            return "";
        }

        params = params.toLowerCase();

        int used = claim.getUsed(p);
        int total = claim.getTotal(p);
        int free = total - used;

        int purchased = 0;
        try {
            String raw = PlaceholderAPI.setPlaceholders(p, "%yov_player_key:claim_points%");
            purchased = Integer.parseInt(raw);
        } catch (Exception ignored) {
        }

        int hours = 0;
        try {
            String raw = PlaceholderAPI.setPlaceholders(p, "%statistic_hours_played%");
            hours = Integer.parseInt(raw);
        } catch (Exception ignored) {
        }

        int hoursPoints = hours * cfg.getPerHourPoints();

        switch (params) {
            case "points_total":       // %tc_points_total%
                return String.valueOf(total);
            case "points_used":        // %tc_points_used%
                return String.valueOf(used);
            case "points_free":        // %tc_points_free%
                return String.valueOf(free);
            case "points_purchased":   // %tc_points_purchased%
                return String.valueOf(purchased);
            case "points_hours":       // %tc_points_hours%
                return String.valueOf(hoursPoints);
            case "points_hours_played":// %tc_points_hours_played%
                return String.valueOf(hours);
            case "points_per_hour":    // %tc_points_per_hour%
                return String.valueOf(cfg.getPerHourPoints());
            default:
                return null; // null placeholder
        }
    }
}
