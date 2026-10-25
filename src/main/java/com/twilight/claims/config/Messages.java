package com.twilight.claims.config;

import com.twilight.claims.TwilightClaims;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.World;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;

public class Messages {

    private final FileConfiguration cfg;

    public Messages(TwilightClaims plugin) {
        this.cfg = plugin.getConfig();
    }

    public String msg(String path) {
        String message = cfg.getString("messages." + path);
        return color(message == null ? "&cMissing: " + path : message);
    }

    public List<String> msgList(String path) {
        return cfg.getStringList("messages." + path).stream()
                .map(this::color)
                .toList();
    }

    public void send(Player p, String path, Placeholder placeholders) {
        p.sendMessage(placeholders.apply(msg(path)));
    }

    public void sendList(Player p, String path, Placeholder placeholders) {
        for (String line : msgList(path)) {
            p.sendMessage(placeholders.apply(line));
        }
    }

    public String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public Component text(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(legacy);
    }

    public Component component(String path, Placeholder placeholders) {
        return text(placeholders.apply(msg(path)));
    }

    public String getWorldName(World world) {
        var names = cfg.getConfigurationSection("world-names");
        Object configured = names == null ? null : names.getValues(true).get(world.getName());
        return configured instanceof String name ? color(name) : world.getName();
    }

    public interface Placeholder {
        String apply(String s);
    }
}
