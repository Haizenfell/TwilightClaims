package com.twilight.claims.tool;

import com.twilight.claims.config.ClaimsConfig;
import com.twilight.claims.config.Messages;
import com.twilight.claims.wg.WGHook;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.util.Vector;

public class StickToolListener implements Listener {

    private final ClaimsConfig cfg;
    private final Messages msg;
    private final WGHook wg;

    public StickToolListener(ClaimsConfig cfg, Messages msg, WGHook wg) {
        this.cfg = cfg;
        this.msg = msg;
        this.wg = wg;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (!event.hasBlock()) return;
        if (event.getItem() == null) return;
        if (!cfg.isStick(event.getItem())) return;

        event.setCancelled(true);

        Player player = event.getPlayer();
        Location loc = event.getClickedBlock().getLocation();

        switch (event.getAction()) {
            case LEFT_CLICK_BLOCK -> handleLeftClick(player, loc);
            case RIGHT_CLICK_BLOCK -> handleRightClick(player, loc);
        }
    }

    private void handleLeftClick(Player player, Location loc) {
        ProtectedRegion region = wg.getRegionAt(loc);

        if (region == null) {
            sendAction(player, color(cfg.stickMsgUnclaimed()));
            return;
        }

        String owner = region.getOwners().getUniqueIds().stream()
                .findFirst()
                .map(id -> Bukkit.getOfflinePlayer(id).getName())
                .orElse("Unknown");

        int area = wg.getArea(region);

        msg.sendList(player, "inspect_claim",
                s -> s.replace("%id%", region.getId())
                        .replace("%owner%", owner)
                        .replace("%area%", String.valueOf(area)));
    }


    private void handleRightClick(Player player, Location origin) {

        ProtectedRegion nearest = wg.getClosestRegion(origin, cfg.stickScanRadius());

        if (nearest == null) {
            sendAction(player, color(cfg.stickMsgNoNearby()));
            return;
        }

        Location center = wg.getRegionCenter(nearest, origin.getWorld());
        double dist = center.distance(origin);

        String owner = nearest.getOwners().getUniqueIds().stream()
                .findFirst()
                .map(id -> Bukkit.getOfflinePlayer(id).getName())
                .orElse("Unknown");

        String arrow = getDirectionArrow(player, center);

        String text = cfg.stickMsgNearest()
                .replace("%owner%", owner)
                .replace("%distance%", String.valueOf((int) dist))
                .replace("%arrow%", arrow);

        sendAction(player, color(text));
    }


    private String getDirectionArrow(Player player, Location target) {

        Location loc = player.getLocation();
        Vector look = loc.getDirection().setY(0).normalize();
        Vector to = target.clone().subtract(loc).toVector().setY(0).normalize();

        double angle = look.angle(to);
        double cross = look.crossProduct(to).getY();

        if (angle < Math.toRadians(45)) {
            return cfg.getStickArrowFront();
        }
        if (angle > Math.toRadians(135)) {
            return cfg.getStickArrowBack();
        }

        return (cross > 0)
                ? cfg.getStickArrowLeft()
                : cfg.getStickArrowRight();
    }

    private void sendAction(Player p, String msg) {
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
    }

    private String color(String s) {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', s);
    }

}
