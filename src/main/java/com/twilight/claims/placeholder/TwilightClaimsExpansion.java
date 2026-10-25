package com.twilight.claims.placeholder;

import com.twilight.claims.TwilightClaims;
import com.twilight.claims.manager.ClaimManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import java.util.Locale;

public class TwilightClaimsExpansion extends PlaceholderExpansion {

    private final TwilightClaims plugin;
    private final ClaimManager claim;

    public TwilightClaimsExpansion(TwilightClaims plugin,
                                   ClaimManager claim) {
        this.plugin = plugin;
        this.claim = claim;
    }

    @Override
    public String getIdentifier() {
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

        if (p == null || params == null) return "";

        params = params.toLowerCase(Locale.ROOT);
        if (!params.matches("points_(total|used|free|purchased|hours_played|hours|per_hour)(_raw)?")) return null;
        ClaimManager.PointsSnapshot snapshot = claim.getSnapshot(p);
        long used = snapshot.used();
        long total = snapshot.total();
        long free = snapshot.free();
        int purchased = snapshot.purchased();
        int hours = snapshot.hoursPlayed();
        int perHour = snapshot.perHour();
        long hoursPoints = snapshot.hoursPoints();

        switch (params) {

            case "points_total":
                return formatCompact(total);

            case "points_total_raw":
                return String.valueOf(total);

            case "points_used":
                return formatCompact(used);

            case "points_used_raw":
                return String.valueOf(used);

            case "points_free":
                return formatCompact(free);

            case "points_free_raw":
                return String.valueOf(free);

            case "points_purchased":
                return formatCompact(purchased);

            case "points_purchased_raw":
                return String.valueOf(purchased);

            case "points_hours_played":
                return formatCompact(hours);

            case "points_hours_played_raw":
                return String.valueOf(hours);

            case "points_hours":
                return formatCompact(hoursPoints);

            case "points_hours_raw":
                return String.valueOf(hoursPoints);

            case "points_per_hour":
                return formatCompact(perHour);

            case "points_per_hour_raw":
                return String.valueOf(perHour);

            default:
                return null;
        }
    }

    static String formatCompact(long value) {
        if (value > -1000 && value < 1000) {
            return String.valueOf(value);
        }

        long thousands = Math.abs(value / 1000) + (value % 1000 == 0 ? 0 : 1);
        return (value < 0 ? "-" : "") + thousands + "k";
    }
}
