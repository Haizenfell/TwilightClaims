package com.twilight.claims.config;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import com.twilight.claims.TwilightClaims;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;

public class ClaimsConfig {

    private final TwilightClaims plugin;
    private final FileConfiguration cfg;
    private final NamespacedKey toolKey;

    private ItemStack shovelItem;
    private ItemStack stickItem;

    private int minRadius;
    private int perHourPoints;

    public ClaimsConfig(TwilightClaims plugin) {
        this.plugin = plugin;
        this.cfg = plugin.getConfig();
        this.toolKey = new NamespacedKey(plugin, "claim_tool");
        load();
    }

    private void load() {
        this.minRadius = Math.clamp(cfg.getInt("claim.min-radius", 5), 0, 29_999_983);
        this.perHourPoints = Math.max(0, cfg.getInt("points.per-hour-default", 50));

        this.shovelItem = createTool("tools.shovel");
        this.stickItem = createTool("tools.stick");
    }

    public Map<String, Integer> getGroupPointsMap() {
        Map<String, Integer> map = new HashMap<>();

        if (!cfg.isConfigurationSection("points.groups")) {
            return map;
        }
        ConfigurationSection section = cfg.getConfigurationSection("points.groups");
        if (section == null) {
            return map;
        }

        for (String key : section.getKeys(false)) {
            int val = Math.max(0, section.getInt(key, 0));
            map.put(key, val);
        }

        return map;
    }


    private ItemStack createTool(String path) {
        Material fallback = path.equals("tools.shovel") ? Material.GOLDEN_SHOVEL : Material.STICK;
        String matName = cfg.getString(path + ".material", fallback.name());
        Material mat = Material.matchMaterial(matName.toUpperCase(Locale.ROOT));
        if (mat == null || !mat.isItem() || mat.isAir()) {
            plugin.getLogger().warning("Invalid tool material at " + path + ".material: " + matName
                    + "; using " + fallback);
            mat = fallback;
        }
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
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING,
                path.equals("tools.shovel") ? "shovel" : "stick");
        item.setItemMeta(meta);
        return item;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
    public boolean isShovel(ItemStack item) {
        return isTool(item, shovelItem, "shovel");
    }

    public boolean isStick(ItemStack item) {
        return isTool(item, stickItem, "stick");
    }

    private boolean isTool(ItemStack item, ItemStack expectedItem, String type) {
        if (item == null || item.getType() != expectedItem.getType() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        String storedType = meta.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        if (storedType != null) return storedType.equals(type);
        ItemMeta expected = expectedItem.getItemMeta();
        return expected != null && meta.hasDisplayName()
                && meta.getDisplayName().equals(expected.getDisplayName());
    }

    public ItemStack getShovelItem() {
        return shovelItem.clone();
    }

    public ItemStack getStickItem() {
        return stickItem.clone();
    }

    public int getMinRadius() {
        return minRadius;
    }

    public int getMinClaimSize() {
        return minRadius * 2 + 1;
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
        return Math.max(0, cfg.getInt("stick-tool.scan-radius", 100));
    }

    public boolean isStickToolEnabled() {
        return plugin.getConfig().getBoolean("stick-tool.enabled", true);
    }

    public int stickVisualizeRadius() {
        return Math.max(0, cfg.getInt("stick-tool.visualize-radius", 50));
    }

    public int stickVisualizeDuration() {
        return Math.clamp(cfg.getInt("stick-tool.visualize-duration", 3), 1, 3600);
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
