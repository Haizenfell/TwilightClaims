package com.twilight.claims.placeholder;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.ChatColor;

public class PlaceholderResolver {

    private final JavaPlugin plugin;
    private Method setPlaceholdersMethod;
    private boolean reflectionFailed;
    private final ThreadLocal<Set<String>> resolving = ThreadLocal.withInitial(HashSet::new);
    private final Set<String> warnedPlaceholders = new HashSet<>();

    public PlaceholderResolver(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isAvailable() {
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        return papi != null && papi.isEnabled();
    }

    public int parseInt(Player player, String placeholder) {
        return parseIntOrDefault(player, placeholder, 0);
    }

    public int parseIntOrDefault(Player player, String placeholder, int fallback) {
        return parseNonNegativeNumber(setPlaceholders(player, placeholder), fallback);
    }

    static int parseNonNegativeNumber(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            BigDecimal number = new BigDecimal(ChatColor.stripColor(raw).trim());
            if (number.signum() < 0) return 0;
            return number.min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValue();
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public String setPlaceholders(Player player, String text) {
        if (!Bukkit.isPrimaryThread() || !isAvailable() || reflectionFailed) {
            return "";
        }

        Set<String> active = resolving.get();
        if (!active.add(text)) return "";
        try {
            Method method = getSetPlaceholdersMethod();
            Object value = method.invoke(null, player, text);
            return value instanceof String string ? string : "";
        } catch (InvocationTargetException ex) {
            if (warnedPlaceholders.add(text)) {
                plugin.getLogger().warning("Placeholder expansion failed for " + text + ": " + ex.getCause());
            }
            return "";
        } catch (ReflectiveOperationException | LinkageError ex) {
            reflectionFailed = true;
            plugin.getLogger().warning("PlaceholderAPI is installed, but placeholders could not be resolved: " + ex.getMessage());
            return "";
        } finally {
            active.remove(text);
            if (active.isEmpty()) resolving.remove();
        }
    }

    private Method getSetPlaceholdersMethod() throws ReflectiveOperationException {
        if (setPlaceholdersMethod == null) {
            Class<?> placeholderApi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            setPlaceholdersMethod = placeholderApi.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
        }
        return setPlaceholdersMethod;
    }
}
