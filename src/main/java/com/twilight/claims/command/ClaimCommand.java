package com.twilight.claims.command;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.twilight.claims.TwilightWGClaims;
import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.config.Messages;
import com.twilight.claims.manager.ClaimManager;
import com.twilight.claims.visual.RegionVisualizer;
import com.twilight.claims.wg.WGHook;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;

public class ClaimCommand {

    private final TwilightWGClaims plugin;
    private final ClaimsConfig cfg;
    private final Messages msg;
    private final WGHook wg;
    private final ClaimManager claim;
    private final RegionVisualizer visualizer;

    public ClaimCommand(TwilightWGClaims plugin,
                        ClaimsConfig cfg,
                        Messages msg,
                        WGHook wg,
                        ClaimManager claim,
                        RegionVisualizer visualizer) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.msg = msg;
        this.wg = wg;
        this.claim = claim;
        this.visualizer = visualizer;
    }

    public void create(Player p) {
        int radius = cfg.getMinRadius();
        int area = (radius * 2 + 1) * (radius * 2 + 1);
        int free = claim.getFree(p);

        if (free < area) {
            msg.send(p, "not_enough_points",
                    s -> s.replace("%need%", String.valueOf(area))
                            .replace("%free%", String.valueOf(free)));
            return;
        }

        ProtectedRegion r = wg.createRegion(p, radius);
        if (r == null) {
            return;
        }

        msg.send(p, "region_create",
                s -> s.replace("%id%", r.getId())
                        .replace("%area%", String.valueOf(area)));

        visualizer.show(p, r, null);
    }

    public void handleSubCommand(Player p, String sub, String[] args) {
        switch (sub.toLowerCase()) {
            case "points" -> points(p);
            case "info" -> info(p);
            case "list" -> list(p);
            case "unclaim" -> unclaim(p);
            default -> p.sendMessage(ChatColor.YELLOW + "/claim info | points | list | unclaim");
        }
    }

    private void points(Player p) {
        int used = claim.getUsed(p);
        int total = claim.getTotal(p);
        int free = total - used;

        int purchased = 0;
        try {
            purchased = Integer.parseInt(
                    PlaceholderAPI.setPlaceholders(p, "%yov_player_key:claim_points%")
            );
        } catch (Exception ignored) {}

        int hours = 0;
        try {
            hours = Integer.parseInt(
                    PlaceholderAPI.setPlaceholders(p, "%statistic_hours_played%")
            );
        } catch (Exception ignored) {}

        int hoursPoints = hours * cfg.getPerHourPoints();
        List<ProtectedRegion> regions = wg.getRegionsOwnedBy(p);

        msg.send(p, "claim_points_header", s -> s);
        msg.send(p, "claim_points_free",
                s -> s.replace("%free_points%", String.valueOf(free)));
        msg.send(p, "claim_points_used",
                s -> s.replace("%points_used%", String.valueOf(used)));
        msg.send(p, "claim_points_total",
                s -> s.replace("%points_total%", String.valueOf(total)));

        final int purchasedFinal = purchased;
        msg.send(p, "claim_points_purchased",
                s -> s.replace("%purchased%", String.valueOf(purchasedFinal)));

        msg.send(p, "claim_points_hours",
                s -> s.replace("%hours_points%", String.valueOf(hoursPoints)));

        int index = 1;
        for (ProtectedRegion r : regions) {
            final int idx = index;
            final int areaFinal = wg.getArea(r);

            msg.send(p, "claim_points_entry",
                    s -> s.replace("%index%", String.valueOf(idx))
                            .replace("%area%", String.valueOf(areaFinal))
                            .replace("%id%", r.getId()));

            index++;
        }
    }

    private void info(Player p) {
        ProtectedRegion r = wg.getRegionAt(p.getLocation());
        if (r == null) {
            msg.send(p, "no_region", s -> s);
            return;
        }

        int area = wg.getArea(r);
        int used = claim.getUsed(p);
        int total = claim.getTotal(p);
        int free = total - used;

        String ownerName = r.getOwners().getUniqueIds().stream()
                .findFirst()
                .map(uuid -> {
                    String n = Bukkit.getOfflinePlayer(uuid).getName();
                    return n == null ? "Unknown" : n;
                })
                .orElse("Unknown");

        msg.sendList(p, "region_info",
                s -> s.replace("%id%", r.getId())
                        .replace("%area%", String.valueOf(area))
                        .replace("%used%", String.valueOf(used))
                        .replace("%total%", String.valueOf(total))
                        .replace("%free%", String.valueOf(free))
                        .replace("%owner%", ownerName));
    }

    private void list(Player p) {
        List<ProtectedRegion> list = wg.getRegionsOwnedBy(p);

        if (list.isEmpty()) {
            msg.send(p, "claim_list_empty", s -> s);
            return;
        }

        p.sendMessage(msg.msg("claim_list_header"));

        for (ProtectedRegion r : list) {
            int area = wg.getArea(r);

            p.sendMessage(
                    msg.msg("claim_list_entry")
                            .replace("%id%", r.getId())
                            .replace("%area%", String.valueOf(area))
            );
        }
    }

    private void unclaim(Player p) {
        ProtectedRegion r = wg.getRegionAt(p.getLocation());
        if (r == null) {
            msg.send(p, "no_region", s -> s);
            return;
        }

        if (!r.getOwners().contains(p.getUniqueId())) {
            msg.send(p, "not_owner", s -> s);
            return;
        }

        int area = wg.getArea(r);
        World w = p.getWorld();

        wg.getManager(w).removeRegion(r.getId());
        wg.save(w);

        msg.send(p, "region_delete",
                s -> s.replace("%area%", String.valueOf(area)));
    }
}
