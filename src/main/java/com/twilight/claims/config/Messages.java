package com.twilight.claims.config;

import com.twilight.claims.TwilightWGClaims;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.List;

public class Messages {

    private final FileConfiguration cfg;

    public Messages(TwilightWGClaims plugin) {
        this.cfg = plugin.getConfig();
    }

    public String msg(String path) {
        return color(cfg.getString("messages." + path, "&cMissing: " + path));
    }
    public String get(String key) {
        return cfg.getString("messages." + key, "&cMissing message: " + key);
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

    public interface Placeholder {
        String apply(String s);
    }
}
