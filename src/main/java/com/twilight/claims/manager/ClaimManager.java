package com.twilight.claims.manager;

import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.placeholder.PlaceholderResolver;
import com.twilight.claims.wg.WGHook;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ClaimManager {

    private final WGHook wg;
    private final ClaimsConfig cfg;
    private final PlaceholderResolver placeholders;
    private final Map<UUID, PointsSnapshot> snapshots = new ConcurrentHashMap<>();

    public record PointsSnapshot(long used, long total, int purchased, int hoursPlayed, int perHour) {
        public long free() { return total - used; }
        public long hoursPoints() { return (long) hoursPlayed * perHour; }
    }

    public ClaimManager(WGHook wg, ClaimsConfig cfg, PlaceholderResolver placeholders) {
        this.wg = wg;
        this.cfg = cfg;
        this.placeholders = placeholders;
    }

    public long getUsed(Player player) {
        long used = 0;
        for (var region : wg.getRegionsOwnedBy(player)) {
            long area = wg.getArea(region);
            if (Long.MAX_VALUE - used < area) return Long.MAX_VALUE;
            used += area;
        }
        return used;
    }

    public long getTotal(Player player) {
        int purchased = getPurchasedPoints(player);
        int hoursPlayed = getHoursPlayed(player);

        int pointsPerHour = getPointsPerHourFromGroup(player);

        return purchased + (long) hoursPlayed * pointsPerHour;
    }

    public long getFree(Player player) {
        return getTotal(player) - getUsed(player);
    }

    public boolean canExpand(Player player, long deltaArea) {
        if (deltaArea <= 0) return true;
        return getFree(player) >= deltaArea;
    }

    public int getPurchasedPoints(Player player) {
        return placeholders.parseInt(player, "%yov_player_key:claim_points%");
    }

    public int getHoursPlayed(Player player) {
        int external = placeholders.parseIntOrDefault(player, "%statistic_hours_played%", -1);
        return external >= 0 ? external : Math.max(0, player.getStatistic(Statistic.PLAY_ONE_MINUTE)) / 72_000;
    }

    public long getHoursPoints(Player player) {
        return (long) getHoursPlayed(player) * getPointsPerHourFromGroup(player);
    }

    public void refresh(Player player) {
        int purchased = getPurchasedPoints(player);
        int hours = getHoursPlayed(player);
        int perHour = getPointsPerHourFromGroup(player);
        snapshots.put(player.getUniqueId(), new PointsSnapshot(
                getUsed(player), purchased + (long) hours * perHour, purchased, hours, perHour));
    }

    public PointsSnapshot getSnapshot(Player player) {
        UUID id = player.getUniqueId();
        PointsSnapshot snapshot = snapshots.get(id);
        if (snapshot == null && Bukkit.isPrimaryThread()) {
            refresh(player);
            snapshot = snapshots.get(id);
        }
        return snapshot == null ? new PointsSnapshot(0, 0, 0, 0, 0) : snapshot;
    }

    public void forget(Player player) {
        snapshots.remove(player.getUniqueId());
    }

    public int getPointsPerHourFromGroup(Player player) {
        Map<String, Integer> groups = cfg.getGroupPointsMap();
        int max = -1;
        String basePerm = "twilight.claim.points.";
        for (Map.Entry<String, Integer> entry : groups.entrySet()) {
            String key = entry.getKey();
            int value = entry.getValue();

            String permission = basePerm + key;

            if (player.hasPermission(permission) && value > max) {
                max = value;
            }
        }
        if (max < 0) {
            return cfg.getPerHourPoints();
        }
        return max;
    }

}
