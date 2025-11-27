package com.twilight.claims.config;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import com.twilight.claims.TwilightWGClaims;

import java.util.List;
import java.util.stream.Collectors;

public class ClaimsConfig {

    private final TwilightWGClaims plugin;
    private final FileConfiguration cfg;

    private ItemStack shovelItem;
    private ItemStack stickItem;

    private int minRadius;
    private int basePoints;
    private int perHourPoints;

    public ClaimsConfig(TwilightWGClaims plugin) {
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

    public ItemStack getShovelItem() {
        return shovelItem.clone();
    }

    public ItemStack getStickItem() {
        return stickItem.clone();
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

    public int getBasePoints() {
        return basePoints;
    }

    public int getPerHourPoints() {
        return perHourPoints;
    }
}
