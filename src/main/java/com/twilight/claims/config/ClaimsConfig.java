package com.twilight.claims.config;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import com.twilight.claims.TwilightClaims;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ClaimsConfig {

    private final TwilightClaims plugin;
    private final FileConfiguration cfg;

    private ItemStack shovelItem;
    private ItemStack stickItem;

    private int minRadius;
    private int basePoints;
    private int perHourPoints;

    public ClaimsConfig(TwilightClaims plugin) {
        this.plugin = plugin;
        this.cfg = plugin.getConfig();
        load();
    }

    private void load() {
        this.minRadius = cfg.getInt("claim.min-radius", 5);
        this.basePoints = cfg.getInt("claim.base-claim-points", 2000);
        this.perHourPoints = cfg.getInt("claim.per-hour-points", 100);

        this.shovelItem = createTool("tools.shovel");
        this.stickItem = createTool("tools.stick");
    }

    public Map<String, Integer> getGroupPointsMap() {
        Map<String, Integer> map = new HashMap<>();

        if (cfg.isConfigurationSection("points.groups")) {
            for (String key : cfg.getConfigurationSection("points.groups").getKeys(false)) {
                int val = cfg.getInt("points.groups." + key);
                map.put(key, val);
            }
        }
        return map;
    }

    private ItemStack createTool(String path) {
        String matName = cfg.getString(path + ".material", "STICK");
        Material mat = Material.matchMaterial(matName.toUpperCase());
        if (mat == null) mat = Material.STICK;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        String name = cfg.getString(path + ".name", "&fTool");
        meta.setDisplayName(color(name));
        List<String> lore = cfg.getStringList(path + ".lore").stream()
                .map(this::color)
                .collect(Collectors.toList());
        meta.setLore(lore);
        meta.setUnbreakable(true);
        item.setItemMeta(meta);
        return item;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
    public boolean isShovel(ItemStack item) {
        if (item == null) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.hasDisplayName() && meta.getDisplayName().equals(shovelItem.getItemMeta().getDisplayName());
    }

    public boolean isStick(ItemStack item) {
        if (item == null) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta.hasDisplayName() && meta.getDisplayName().equals(stickItem.getItemMeta().getDisplayName());
    }

    public int getMinRadius() {
        return minRadius;
    }

    public int getPerHourPoints() {
        return perHourPoints;
    }

    public String stickMsgUnclaimed() {
        return plugin.getConfig().getString("stick-tool.messages.unclaimed", "&cThis block is not claimed.");
    }

    public String stickMsgNearest() {
        return plugin.getConfig().getString("stick-tool.messages.nearest-format", "&eNearest region: &f%owner% &7(%distance%m) %arrow%");
    }

    public String stickMsgNoNearby() {
        return plugin.getConfig().getString("stick-tool.messages.no-nearby", "&cNo claimed regions nearby.");
    }

    public int stickScanRadius() {
        return plugin.getConfig().getInt("stick-tool.scan-radius", 100);
    }
    public int stickVisualizeRadius() {
        return plugin.getConfig().getInt("stick.visualize-radius", 50);
    }

    public int stickVisualizeDuration() {
        return plugin.getConfig().getInt("stick.visualize-duration", 3);
    }

    public String getStickArrowFront() {
        return plugin.getConfig().getString("stick-tool.arrows.front", "↑");
    }

    public String getStickArrowBack() {
        return plugin.getConfig().getString("stick-tool.arrows.back", "↓");
    }

    public String getStickArrowLeft() {
        return plugin.getConfig().getString("stick-tool.arrows.left", "←");
    }

    public String getStickArrowRight() {
        return plugin.getConfig().getString("stick-tool.arrows.right", "→");
    }

}
